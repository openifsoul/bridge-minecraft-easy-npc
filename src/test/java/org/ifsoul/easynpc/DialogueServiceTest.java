package org.ifsoul.easynpc;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DialogueServiceTest {
    public static void main(String[] args) throws Exception {
        assert Bridge.isCancellation(" STOP ");
        assert Bridge.isCancellation("wait");
        assert !Bridge.isCancellation("wait here");
        var calls = new ArrayList<String>();
        var nativeReadiness = new ArrayList<String>();
        var currentlyDenied = """
            {"connection":{"capability":{"providerId":"fixture","expiresAt":9999999999999,"allowedIntentIds":[],
            "channelPolicy":{"channels":[{"channel":"private","listen":true,"speak":true}]}}}}
            """;
        var actionRequests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/game/device/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String action = path.substring(path.lastIndexOf('/') + 1);
                calls.add(action);
                assert "POST".equals(exchange.getRequestMethod());
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
                String response = switch (action) {
                    case "redeem" -> "{\"token\":\"" + "a".repeat(43) + "\"}";
                    case "ready" -> {
                        if (body.has("capabilityManifest")) yield currentlyDenied;
                        if (!body.has("actionCapabilities")) yield "{}";
                        assert body.getAsJsonArray("actionCapabilities").get(0).getAsString().equals("movement");
                        yield "{\"connection\":{\"activeActions\":[\"movement\"]}}";
                    }
                    case "capabilities" -> {
                        var manifest = body.getAsJsonObject("capabilityManifest");
                        for (var capability : manifest.getAsJsonArray("capabilities")) {
                            nativeReadiness.add(capability.getAsJsonObject().get("readiness").getAsString());
                        }
                        yield currentlyDenied;
                    }
                    case "dialogue" -> {
                        if (!body.has("operation")) yield "{\"speech\":\"Private reply\"}";
                        String operation = body.get("operation").getAsString();
                        actionRequests.add(operation);
                        yield switch (operation) {
                            case "action-check" -> "{\"authorized\":true,\"revision\":1}";
                            case "decide" -> {
                                var choices = body.getAsJsonArray("choices");
                                assert choices.size() == 2;
                                assert "none".equals(choices.get(0).getAsJsonObject().get("id").getAsString());
                                assert "come".equals(choices.get(1).getAsJsonObject().get("id").getAsString());
                                String selected = body.get("text").getAsString().equals("Invalid") ? "unknown" : "come";
                                yield "{\"speech\":\"Private reply\",\"choiceId\":\"" + selected + "\"}";
                            }
                            case "action-outcome" -> {
                                assert "come".equals(body.get("action").getAsString());
                                assert "arrived".equals(body.get("result").getAsString());
                                UUID.fromString(body.get("id").getAsString());
                                yield "{\"ok\":true}";
                            }
                            default -> throw new AssertionError(operation);
                        };
                    }
                    default -> throw new AssertionError(action);
                };
                if (!"redeem".equals(action)) {
                    assert ("Bearer " + "a".repeat(43)).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var service = new DialogueService("http://127.0.0.1:" + server.getAddress().getPort(), "z".repeat(24), "fixture-npc");
            var user = UUID.randomUUID();
            assert "Private reply".equals(service.converse(user, "Fixture", "Hello"));
            assert "Private reply".equals(service.converse(user, "Fixture", "Again"));
            assert calls.equals(List.of("redeem", "ready", "dialogue", "dialogue"));
            var options = Map.of("come", "Bounded action");
            assert "none".equals(service.decide(user, "Fixture", "Legacy grant", options).choice());
            assert actionRequests.isEmpty();
            var actionService = new DialogueService("http://127.0.0.1:" + server.getAddress().getPort(),
                "z".repeat(24), "fixture-npc", Set.of("movement"));
            var decision = actionService.decide(user, "Fixture", "Act", options);
            assert "come".equals(decision.choice());
            assert actionService.checkActions();
            actionService.outcome(decision.id(), decision.choice(), "arrived");
            try {
                actionService.decide(user, "Fixture", "Invalid", options);
                throw new AssertionError("Unknown host choices must reject");
            } catch (java.io.IOException expected) {
                assert expected.getMessage().contains("Invalid action choice");
            }
            assert actionRequests.equals(List.of("action-check", "decide", "action-check", "action-outcome",
                "action-check", "decide"));
            var nativeService = new DialogueService("http://127.0.0.1:" + server.getAddress().getPort(),
                "z".repeat(24), "fixture-npc", Set.of("movement"), Set.of());
            var denied = nativeService.decide(user, "Fixture", "Act", options, "test-world", "fixture-npc");
            assert "none".equals(denied.choice());
            assert "Private reply".equals(denied.speech());
            assert nativeReadiness.size() == 2 && nativeReadiness.stream().allMatch("ready"::equals);
            assert !DialogueService.safeText("bad\nline", 100);
            assert !DialogueService.safeText("bad\u202Eline", 100);
            assert !DialogueService.safeText("123", 2);
            try {
                new DialogueService("http://example.com", "z".repeat(24), "fixture-npc");
                throw new AssertionError("Expected remote HTTP rejection");
            } catch (IllegalArgumentException expected) {
                // Remote services must use HTTPS.
            }
        } finally {
            server.stop(0);
        }
    }
}
