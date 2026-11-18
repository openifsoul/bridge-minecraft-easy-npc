package org.ifsoul.easynpc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DialogueService {
    private final URI origin;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private String code;
    private String token;
    private boolean actionGrant;
    private final String npcId;
    private final Set<String> capabilities;
    private final Set<String> sharedChannels;
    private static final Map<String, String> NATIVE_ACTIONS = Map.of("come", "Walk near the selected player");
    private JsonObject capabilitySession;
    private JsonObject manifest;
    private long manifestRevision;
    private record Heartbeat(String credential, String worldId, JsonObject body) {}
    private volatile Heartbeat heartbeat;

    private void publishHeartbeat() {
        if (token == null || manifest == null || capabilitySession == null) return;
        var body = new JsonObject();
        body.add("providerId", capabilitySession.get("providerId"));
        body.add("capabilityManifest", manifest.deepCopy());
        heartbeat = new Heartbeat(token, manifest.get("worldId").getAsString(), body);
    }

    /** Renew the exact published revision during a blocking provider call. */
    void keepCapabilityLeaseAlive(String worldId) throws IOException, InterruptedException {
        var current = heartbeat;
        if (current != null && current.worldId().equals(worldId)) {
            post("capabilities", current.body(), current.credential(), 5);
        }
    }
    private final Map<UUID, NativeAction> activeActions = new HashMap<>();

    private static final class NativeAction {
        final JsonObject checkpoint;
        int sequence;
        NativeAction(JsonObject checkpoint) { this.checkpoint = checkpoint; }
    }

    record Decision(UUID id, String speech, String choice) {}

    DialogueService(String address, String code, String npcId) {
        this(address, code, npcId, Set.of());
    }

    DialogueService(String address, String code, String npcId, Set<String> capabilities) {
        this(address, code, npcId, capabilities, Set.of());
    }

    DialogueService(String address, String code, String npcId, Set<String> capabilities, Set<String> sharedChannels) {
        this.capabilities = Set.copyOf(capabilities);
        this.sharedChannels = Set.copyOf(sharedChannels);
        origin = URI.create(address);
        if (origin.getHost() == null || origin.getRawUserInfo() != null
            || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null
            || origin.getRawFragment() != null || !(
                "https".equals(origin.getScheme()) || ("http".equals(origin.getScheme())
                    && Set.of("127.0.0.1", "[::1]").contains(origin.getHost())))) {
            throw new IllegalArgumentException("Use an HTTPS or literal loopback HTTP service origin.");
        }
        if (!code.matches("[A-Za-z0-9_-]{24}")) {
            throw new IllegalArgumentException("A fresh service pairing code is required.");
        }
        this.code = code;
        this.npcId = npcId;
    }

    synchronized String converse(UUID player, String name, String text)
        throws IOException, InterruptedException {
        return decide(player, name, text, Map.of()).speech();
    }

    synchronized Decision decide(UUID player, String name, String text, Map<String, String> options)
        throws IOException, InterruptedException {
        return decide(player, name, text, options, null, null);
    }

    synchronized Decision decide(UUID player, String name, String text, Map<String, String> options,
                                 String worldId, String embodimentId)
        throws IOException, InterruptedException {
        boolean readyNow = !options.isEmpty();
        ensurePaired(player, name, worldId, embodimentId, readyNow);
        if (capabilitySession != null && worldId != null) {
            refreshCapabilities(worldId, readyNow);
            if (!channelAllowed("listen")) {
                return new Decision(UUID.randomUUID(), "", "none");
            }
            var permitted = new HashMap<String, String>();
            for (var entry : options.entrySet()) {
                if (granted(entry.getKey())) permitted.put(entry.getKey(), entry.getValue());
            }
            options = permitted;
        }
        UUID request = UUID.randomUUID();
        if (manifest != null && capabilitySession == null) return new Decision(request, "", "none");
        var body = new JsonObject();
        body.addProperty("requestId", request.toString());
        body.addProperty("text", text);
        boolean actionTurn = !options.isEmpty() && (manifest == null ? checkActions() : actionGrant && capabilitySession != null);
        if (actionTurn) {
            body.addProperty("operation", "decide");
            var choices = new JsonArray();
            choices.add(choice("none", "Reply without taking any action"));
            options.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> choices.add(choice(entry.getKey(), entry.getValue())));
            body.add("choices", choices);
        }
        var response = post("dialogue", body, token, 62);
        if (response.has("unavailable") && response.get("unavailable").getAsBoolean()) {
            return new Decision(request, "", "none");
        }
        String choice = actionTurn ? string(response, "choiceId") : "none";
        if (!"none".equals(choice) && !options.containsKey(choice)) {
            throw new IOException("Invalid action choice.");
        }
        String line = string(response, "speech");
        if (!line.isEmpty() && manifest != null) {
            refreshCapabilities(worldId, readyNow);
            if (!channelAllowed("speak")) line = "";
        }
        return new Decision(request, safeText(line, 180) && !line.stripLeading().startsWith("/") ? line : "", choice);
    }

    synchronized boolean checkActions() throws IOException, InterruptedException {
        if (token == null || !actionGrant) return false;
        var body = new JsonObject();
        body.addProperty("operation", "action-check");
        var value = post("dialogue", body, token, 10).get("authorized");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            && value.getAsBoolean();
    }

    private static String intent(String action) {
        return "minecraft." + action;
    }

    private boolean granted(String action) {
        return capabilitySession != null && capabilitySession.get("expiresAt").getAsLong() > System.currentTimeMillis()
            && capabilitySession.getAsJsonArray("allowedIntentIds").contains(new com.google.gson.JsonPrimitive(intent(action)));
    }

    private boolean channelAllowed(String permission) {
        return channelAllowed("private", permission);
    }

    private boolean channelAllowed(String channel, String permission) {
        if (capabilitySession == null || capabilitySession.get("expiresAt").getAsLong() <= System.currentTimeMillis()) return false;
        var policy = capabilitySession.getAsJsonObject("channelPolicy");
        if (policy == null) return false;
        for (var value : policy.getAsJsonArray("channels")) {
            var row = value.getAsJsonObject();
            if (channel.equals(row.get("channel").getAsString())) {
                return row.has(permission) && row.get(permission).getAsBoolean();
            }
        }
        return false;
    }

    private JsonObject manifest(String worldId, String embodimentId, boolean ready, long revision) {
        var result = new JsonObject();
        result.addProperty("worldId", worldId);
        result.addProperty("embodimentId", embodimentId);
        result.addProperty("revision", Long.toString(revision));
        var rows = new JsonArray();
        if (!capabilities.isEmpty()) NATIVE_ACTIONS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var row = new JsonObject();
            row.addProperty("intentId", intent(entry.getKey()));
            row.addProperty("category", "minecraft.movement");
            row.addProperty("description", entry.getValue());
            row.add("parameters", new JsonArray());
            row.addProperty("cancellable", true);
            row.addProperty("support", "partial");
            row.addProperty("readiness", ready ? "ready" : "unavailable");
            row.addProperty("reason", ready ? "Bounded action for the configured native NPC and selected player."
                : "Requires the configured NPC and selected player in native interaction range.");
            rows.add(row);
        });
        result.add("capabilities", rows);
        var channels = new JsonArray();
        channels.add(channelDescriptor("private"));
        sharedChannels.stream().sorted().forEach(channel -> channels.add(channelDescriptor(channel)));
        result.add("channels", channels);
        return result;
    }

    private static JsonObject channelDescriptor(String channel) {
        var descriptor = new JsonObject();
        descriptor.addProperty("channel", channel);
        for (var permission : Set.of("listen", "react", "speak")) descriptor.addProperty(permission, true);
        return descriptor;
    }

    record ChannelReply(String speech, String destination) {}

    synchronized ChannelReply converseShared(UUID player, String name, String text, String worldId,
                                              String embodimentId, String channel, String audienceKey,
                                              boolean ready) throws IOException, InterruptedException {
        if (!sharedChannels.contains(channel)) return null;
        ensurePaired(player, name, worldId, embodimentId, ready);
        if (manifest == null || !worldId.equals(string(manifest, "worldId"))) return null;
        refreshCapabilities(worldId, ready);
        if (!channelAllowed(channel, "listen")) return null;
        var requestId = UUID.randomUUID().toString();
        var body = new JsonObject();
        body.addProperty("requestId", requestId);
        body.addProperty("text", text);
        body.addProperty("channel", channel);
        body.addProperty("audienceKey", audienceKey);
        var response = post("dialogue", body, token, 62);
        if (response.has("unavailable") && response.get("unavailable").getAsBoolean()) return null;
        var destination = string(response, "destination");
        var speech = string(response, "speech");
        if (!channel.equals(destination)
            || !safeText(speech, 180) || speech.stripLeading().startsWith("/")) return null;
        var authority = response.getAsJsonObject("authority");
        if (authority == null) return null;
        var delivery = new JsonObject();
        delivery.addProperty("operation", "channel-delivery-check");
        delivery.addProperty("requestId", requestId);
        delivery.addProperty("channel", destination);
        delivery.addProperty("audienceKey", audienceKey);
        for (var key : Set.of("providerId", "manifestRevision", "policyRevision")) {
            if (!authority.has(key)) return null;
            delivery.add(key, authority.get(key));
        }
        var authorized = post("dialogue", delivery, token, 5).get("authorized");
        return authorized != null && authorized.getAsBoolean() ? new ChannelReply(speech, destination) : null;
    }

    synchronized void refreshCapabilities(String worldId, boolean ready) throws IOException, InterruptedException {
        if (capabilitySession == null || manifest == null) return;
        var originalWorld = manifest.get("worldId").getAsString();
        var actor = manifest.get("embodimentId").getAsString();
        var next = manifest(originalWorld, actor, originalWorld.equals(worldId) && ready, manifestRevision);
        if (!next.equals(manifest)) next.addProperty("revision", Long.toString(manifestRevision + 1));
        var body = new JsonObject();
        body.add("providerId", capabilitySession.get("providerId"));
        body.add("capabilityManifest", next);
        capabilitySession = post("capabilities", body, token, 5).getAsJsonObject("connection").getAsJsonObject("capability");
        manifest = next;
        manifestRevision = next.get("revision").getAsLong();
        publishHeartbeat();
    }

    synchronized boolean checkActions(Decision decision) throws IOException, InterruptedException {
        if (capabilitySession == null) return manifest == null && checkActions();
        var action = activeActions.get(decision.id());
        if (action == null) {
            if (!actionGrant || !granted(decision.choice())) return false;
            var body = new JsonObject();
            body.addProperty("operation", "action-check");
            body.addProperty("id", decision.id().toString());
            body.add("providerId", capabilitySession.get("providerId"));
            body.addProperty("manifestRevision", manifest.get("revision").getAsString());
            body.add("policyRevision", capabilitySession.get("policyRevision"));
            body.addProperty("intentId", intent(decision.choice()));
            body.add("arguments", new JsonArray());
            action = new NativeAction(body);
        }
        action.checkpoint.addProperty("checkpoint", ++action.sequence);
        var authorized = post("dialogue", action.checkpoint, token, 2).get("authorized");
        if (authorized == null || !authorized.getAsBoolean()) return false;
        activeActions.put(decision.id(), action);
        return true;
    }

    synchronized void outcome(UUID id, String action, String result) throws IOException, InterruptedException {
        var typed = activeActions.remove(id);
        if (capabilitySession != null) {
            if (typed == null) return;
            var report = new JsonObject();
            report.addProperty("operation", "action-report");
            report.addProperty("id", id.toString());
            report.addProperty("reportId", UUID.randomUUID().toString());
            report.add("providerId", typed.checkpoint.get("providerId"));
            report.addProperty("intentId", intent(action));
            report.addProperty("status", switch (result) {
                case "arrived" -> "succeeded";
                case "stopped", "interrupted" -> "cancelled";
                default -> "failed";
            });
            var steps = new JsonArray();
            var step = new JsonObject();
            step.addProperty("stepId", "native-result");
            step.addProperty("summary", "Native easy-npc result for " + action + ": " + result + ".");
            steps.add(step);
            report.add("steps", steps);
            post("dialogue", report, token, 10);
            return;
        }
        var body = new JsonObject();
        body.addProperty("operation", "action-outcome");
        body.addProperty("id", id.toString());
        body.addProperty("action", action);
        body.addProperty("result", result);
        post("dialogue", body, token, 10);
    }

    private static JsonObject choice(String id, String label) {
        var choice = new JsonObject();
        choice.addProperty("id", id);
        choice.addProperty("label", label);
        return choice;
    }

    private static String string(JsonObject response, String field) throws IOException {
        var value = response.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Invalid dialogue response.");
        }
        return value.getAsString();
    }

    private void ensurePaired(UUID player, String name, String worldId, String embodimentId, boolean readyNow) throws IOException, InterruptedException {
        if (token == null) {
            if (code == null) throw new IOException("Pairing renewal required.");
            var pair = new JsonObject();
            pair.addProperty("code", code);
            code = null;
            String issuedToken = post("redeem", pair, null, 10).get("token").getAsString();
            if (!issuedToken.matches("[A-Za-z0-9_-]{43}")) {
                throw new IOException("Invalid grant.");
            }
            var playerInfo = new JsonObject();
            playerInfo.addProperty("uuid", player.toString());
            playerInfo.addProperty("name", name);
            var ready = new JsonObject();
            ready.add("player", playerInfo);
            var minecraft = new JsonObject();
            minecraft.addProperty("connector", "minecraft-easy-npc");
            minecraft.addProperty("version", "1.21.1");
            minecraft.addProperty("loader", "NeoForge");
            minecraft.addProperty("upstream", "6.0.2");
            minecraft.addProperty("npcId", npcId);
            if ((readyNow || worldId != null) && !capabilities.isEmpty()) {
                var available = new JsonArray();
                capabilities.stream().sorted().forEach(available::add);
                ready.add("actionCapabilities", available);
            }
            ready.add("minecraft", minecraft);
            if (worldId != null && embodimentId != null) {
                manifestRevision = 1;
                manifest = manifest(worldId, embodimentId, readyNow, manifestRevision);
                ready.add("capabilityManifest", manifest);
            }
            var connection = post("ready", ready, issuedToken, 10).get("connection");
            if (connection != null && connection.isJsonObject()) {
                capabilitySession = connection.getAsJsonObject().getAsJsonObject("capability");
                var permitted = connection.getAsJsonObject().get("activeActions");
                actionGrant = permitted != null && permitted.isJsonArray() && !permitted.getAsJsonArray().isEmpty();
            }
            token = issuedToken;
            publishHeartbeat();
        }
    }

    static boolean safeText(String text, int maxLength) {
        return !text.isBlank() && text.length() <= maxLength && text.codePoints().noneMatch(cp ->
            Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                || Character.getType(cp) == Character.LINE_SEPARATOR
                || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR);
    }

    private JsonObject post(String action, JsonObject body, String credential, int seconds)
        throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(origin.resolve("/api/game/device/" + action))
            .timeout(Duration.ofSeconds(seconds))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        if (credential != null) request.header("Authorization", "Bearer " + credential);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Service request unavailable.");
            byte[] bytes = stream.readNBytes(32769);
            if (bytes.length > 32768) throw new IOException("Service response too large.");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
