package dev.aiverify.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aiverify.json.Json;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The released ISO 8583 authorization service: runs only the code in release/iso8583/, which a named human
 * approved and the CD pipeline verified. This is what Cloud Run serves. Public, read-only, no console actions.
 */
public final class ReleaseServer {

    private final Path root;
    private final Path dir;
    private final LiveService live;

    private ReleaseServer(Path root, Path dir) {
        this.root = root;
        this.dir = dir;
        this.live = new LiveService(root, dir.resolve("AuthorizationPrecheckImpl.java"));
    }

    public static void start(Path root, Path dir, int port) throws IOException {
        Path d = dir.isAbsolute() ? dir : root.resolve(dir);
        if (!Files.exists(d.resolve("AuthorizationPrecheckImpl.java")) || !Files.exists(d.resolve("approval.json"))) {
            throw new IllegalStateException("No approved release in " + d + ". Nothing to serve.");
        }
        ReleaseServer app = new ReleaseServer(root, d);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Released ISO 8583 service on port " + port);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/healthz" -> send(ex, 200, "text/plain; charset=utf-8", "ok");
                case "/" -> send(ex, 200, "text/html; charset=utf-8", Files.readString(root.resolve("report/release.html")));
                case "/api/release" -> send(ex, 200, "application/json; charset=utf-8",
                        Files.readString(dir.resolve("approval.json")));
                case "/api/authorize" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                    q.put("version", "release");
                    Object[] r = live.authorize(q);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) r[1];
                    json(ex, (Integer) r[0], body);
                }
                default -> json(ex, 404, Map.of("error", "not found"));
            }
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", String.valueOf(e.getMessage()));
            json(ex, 500, m);
        } finally {
            ex.close();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null) {
            return q;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i > 0) {
                q.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return q;
    }

    private static void json(HttpExchange ex, int code, Map<String, Object> body) throws IOException {
        send(ex, code, "application/json; charset=utf-8", Json.write(body));
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }
}
