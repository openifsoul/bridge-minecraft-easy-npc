package org.ifsoul.easynpc;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SharedDialogueTest {
    public static void main(String[] args) throws Exception {
        var delivered = new AtomicInteger();
        var heard = new AtomicInteger();
        var allowed = new AtomicBoolean(true);
        var key = "b".repeat(64);
        var session = """
            {"connection":{"capability":{"expiresAt":9999999999999,"providerId":"fixture",
            "policyRevision":8,"channelPolicy":{"channels":[{"channel":"group","listen":true},
            {"channel":"public","listen":true}]}}}}
            """;
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/game/device/", exchange -> {
            try (exchange) {
                var path = exchange.getRequestURI().getPath();
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
                String response;
                if (path.endsWith("redeem")) response = "{\"token\":\"" + "a".repeat(43) + "\"}";
                else if (path.endsWith("ready") || path.endsWith("capabilities")) {
                    assert body.getAsJsonObject("capabilityManifest").getAsJsonArray("channels").size() == 3;
                    response = session;
                } else {
                    assert key.equals(body.get("audienceKey").getAsString());
                    if (body.has("operation")) {
                        assert "channel-delivery-check".equals(body.get("operation").getAsString());
                        assert body.get("policyRevision").getAsInt() == 8;
                        delivered.incrementAndGet();
                        response = "{\"authorized\":" + allowed.get() + "}";
                    } else {
                        heard.incrementAndGet();
                        var channel = body.get("channel").getAsString();
                        response = switch (body.get("text").getAsString()) {
                            case "observe" -> "{\"unavailable\":true,\"reason\":\"channel-policy\"}";
                            case "unsafe" -> "{\"speech\":\"Wrong audience\",\"destination\":\"public\"}";
                            default -> "{\"speech\":\"Approved reply\",\"destination\":\"" + channel + "\","
                                + "\"authority\":{\"providerId\":\"fixture\",\"manifestRevision\":\"1\",\"policyRevision\":8}}";
                        };
                    }
                }
                var bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            var service = new DialogueService("http://127.0.0.1:" + server.getAddress().getPort(),
                "z".repeat(24), "fixture", Set.of(), Set.of("group", "public"));
            var player = UUID.randomUUID();
            assert service.converseShared(player, "Fixture", "hello", "world", "actor", "group", key, false)
                .destination().equals("group");
            assert service.converseShared(player, "Fixture", "hello", "world", "actor", "public", key, false)
                .destination().equals("public");
            assert service.converseShared(player, "Fixture", "observe", "world", "actor", "group", key, false) == null;
            assert service.converseShared(player, "Fixture", "unsafe", "world", "actor", "group", key, false) == null;
            allowed.set(false);
            assert service.converseShared(player, "Fixture", "revoked", "world", "actor", "group", key, false) == null;
            assert service.converseShared(player, "Fixture", "moved", "other", "actor", "group", key, false) == null;
            assert heard.get() == 5 && delivered.get() == 3;
        } finally {
            server.stop(0);
        }
    }
}
