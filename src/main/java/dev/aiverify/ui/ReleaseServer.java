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
    private final DemoMailer mailer;

    private ReleaseServer(Path root, Path dir) {
        this.root = root;
        this.dir = dir;
        this.live = new LiveService(root, dir.resolve("AuthorizationPrecheckImpl.java"));
        this.mailer = new DemoMailer(root);
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
        System.out.println("Released ISO 8583 service on port " + port
                + (app.mailer.enabled() ? " - demo email on" : " - demo email off (no SMTP_USER / SMTP_PASSWORD)"));
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
                    String email = q.remove("email");
                    boolean zh = "zh".equals(q.remove("lang"));
                    q.put("version", "release");
                    Object[] r = live.authorize(q);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) r[1];
                    if (email != null && !email.isBlank()) {
                        Map<String, Object> out = new LinkedHashMap<>(body);
                        String why = mailCheck(ex, email.trim(), 1, false);
                        if (why == null) {
                            mailer.sendResult(email.trim(), zh, q, (Integer) r[0], body, release());
                        }
                        out.put("mail", why == null ? "queued" : why);
                        body = out;
                    }
                    json(ex, (Integer) r[0], body);
                }
                case "/api/mail/status" -> json(ex, 200, Map.of("enabled", mailer.enabled()));
                case "/api/mail/flow" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                    String email = q.getOrDefault("email", "").trim();
                    String why = mailCheck(ex, email, 5, true);
                    if (why == null) {
                        int n = mailer.sendFlow(email, "zh".equals(q.get("lang")));
                        json(ex, 200, Map.of("mail", "queued", "count", n));
                    } else {
                        json(ex, "invalid".equals(why) || "off".equals(why) ? 400 : 429, Map.of("mail", why));
                    }
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

    /** null when this email may be sent now, else why not: off, invalid, too-many, daily-limit. */
    private String mailCheck(HttpExchange ex, String email, int count, boolean flow) {
        if (!mailer.enabled()) {
            return "off";
        }
        if (!DemoMailer.valid(email)) {
            return "invalid";
        }
        return mailer.allow(email, client(ex), count, flow);
    }

    /** The visitor's address as Cloud Run reports it (first X-Forwarded-For entry). Kept in memory only. */
    private static String client(HttpExchange ex) {
        String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (f != null && !f.isBlank()) {
            return f.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private Map<String, Object> release() {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(dir.resolve("approval.json")));
            return m;
        } catch (Exception e) {
            return Map.of();
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
