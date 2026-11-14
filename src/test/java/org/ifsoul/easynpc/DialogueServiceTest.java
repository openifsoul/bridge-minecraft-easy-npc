package org.ifsoul.easynpc;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class DialogueServiceTest {
    public static void main(String[] args) throws Exception {
        var calls = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/game/device/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String action = path.substring(path.lastIndexOf('/') + 1);
                calls.add(action);
                assert "POST".equals(exchange.getRequestMethod());
                byte[] response = switch (action) {
                    case "redeem" -> ("{\"token\":\"" + "a".repeat(43) + "\"}").getBytes(StandardCharsets.UTF_8);
                    case "ready" -> "{}".getBytes(StandardCharsets.UTF_8);
                    case "dialogue" -> "{\"speech\":\"Private reply\"}".getBytes(StandardCharsets.UTF_8);
                    default -> throw new AssertionError(action);
                };
                if (!"redeem".equals(action)) {
                    assert ("Bearer " + "a".repeat(43)).equals(exchange.getRequestHeaders().getFirst("Authorization"));
                }
                assert exchange.getRequestBody().readAllBytes().length > 0;
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        try {
            var service = new DialogueService("http://127.0.0.1:" + server.getAddress().getPort(), "z".repeat(24), "fixture-npc");
            var user = UUID.randomUUID();
            assert "Private reply".equals(service.converse(user, "Fixture", "Hello"));
            assert "Private reply".equals(service.converse(user, "Fixture", "Again"));
            assert calls.equals(List.of("redeem", "ready", "dialogue", "dialogue"));
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
