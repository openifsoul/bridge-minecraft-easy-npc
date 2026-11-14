package org.ifsoul.easynpc;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
    private final String npcId;

    DialogueService(String address, String code, String npcId) {
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
            ready.add("minecraft", minecraft);
            post("ready", ready, issuedToken, 10);
            token = issuedToken;
        }
        var body = new JsonObject();
        body.addProperty("requestId", UUID.randomUUID().toString());
        body.addProperty("text", text);
        var response = post("dialogue", body, token, 62);
        var speech = response.get("speech");
        if (speech == null || !speech.isJsonPrimitive() || !speech.getAsJsonPrimitive().isString()) {
            throw new IOException("Invalid dialogue response.");
        }
        String line = speech.getAsString();
        return safeText(line, 180) && !line.stripLeading().startsWith("/") ? line : "";
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
