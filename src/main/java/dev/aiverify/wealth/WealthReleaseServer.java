package dev.aiverify.wealth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aiverify.contract.SubscriptionPrecheck;
import dev.aiverify.contract.SubscriptionRequest;
import dev.aiverify.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * {@code ./run.sh wealth-serve-release}: the production fund-order pre-check service.
 *
 * <p>It serves only the code in release/wealth/, and only if its SHA-256 is the one a named person approved and it
 * passes every golden test; otherwise it refuses to start. It is a separate Cloud Run service from the demo console,
 * deployed by GitHub Actions after an approval.
 */
public final class WealthReleaseServer {

    private final Path root;
    private final Map<String, Object> release;
    private final SubscriptionPrecheck impl;
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fund-precheck");
        t.setDaemon(true);
        return t;
    });

    private WealthReleaseServer(Path root) throws IOException {
        this.root = root;
        Path dir = root.resolve(WealthRelease.DIR);
        this.release = WealthRelease.check(root, dir);   // throws: the service does not start with unapproved code
        this.impl = WealthRelease.load(Files.readString(dir.resolve(WealthRelease.CODE)));
        release.put("deployedBy", env("DEPLOYED_BY"));
        release.put("deployedRun", env("DEPLOYED_RUN"));
        release.put("deployedAt", env("DEPLOYED_AT"));
        release.put("deployedCommit", env("DEPLOYED_COMMIT"));
        release.put("actionsRunUrl", env("ACTIONS_RUN_URL"));
        release.put("consoleUrl", env("CONSOLE_URL"));
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v;
    }

    public static void start(Path root, int port) throws IOException {
        WealthReleaseServer s = new WealthReleaseServer(root);
        String envPort = System.getenv("PORT");
        int p = envPort != null ? Integer.parseInt(envPort) : port;
        HttpServer server = HttpServer.create(new InetSocketAddress(envPort != null ? "0.0.0.0" : "127.0.0.1", p), 0);
        server.createContext("/", s::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Fund pre-check (production) on http://localhost:" + p + "  serving " + s.release.get("runId")
                + ", SHA-256 " + s.release.get("candidateSha256"));
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/health" -> send(ex, 200, "text/plain; charset=utf-8", "ok");
                case "/" -> send(ex, 200, "text/html; charset=utf-8",
                        Files.readString(root.resolve("report/wealth-release.html")));
                case "/api/release" -> send(ex, 200, "application/json; charset=utf-8", Json.writeCompact(release));
                case "/api/check" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        send(ex, 405, "application/json; charset=utf-8", "{\"error\":\"POST only\"}");
                        return;
                    }
                    send(ex, 200, "application/json; charset=utf-8", Json.writeCompact(check(readBody(ex))));
                }
                default -> send(ex, 404, "text/plain; charset=utf-8", "Not found");
            }
        } catch (RuntimeException e) {
            send(ex, 400, "application/json; charset=utf-8", Json.writeCompact(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private Map<String, Object> check(String body) {
        Map<String, Object> b = Json.parseObject(body);
        SubscriptionRequest req = new SubscriptionRequest(str(b.get("clientRiskLevel")), str(b.get("fundRiskLevel")),
                date(b.get("kycAssessedOn")), minor(b.get("amount")), minor(b.get("minimum")), minor(b.get("holding")),
                minor(b.get("portfolio")));
        LocalDate tradeDate = date(b.get("tradeDate"));
        Future<String> f = exec.submit(() -> impl.check(req, tradeDate));
        String code;
        try {
            code = f.get(1000, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            code = "TIMEOUT";
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            code = "CRASH: " + c.getClass().getSimpleName();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", code);
        out.put("servedBy", release.get("runId"));
        out.put("sha256", release.get("candidateSha256"));
        return out;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static LocalDate date(Object o) {
        return o == null || String.valueOf(o).isBlank() ? null : LocalDate.parse(String.valueOf(o));
    }

    private static long minor(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return 0;
        }
        return new BigDecimal(String.valueOf(o)).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readNBytes(16_384), StandardCharsets.UTF_8);
        }
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
