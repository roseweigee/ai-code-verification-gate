package dev.aiverify.wealth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.SubscriptionPrecheck;
import dev.aiverify.contract.SubscriptionRequest;
import dev.aiverify.json.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * {@code ./run.sh wealth-console}: a public web demo of the wealth-management scenario.
 *
 * <p>Every visitor gets an own workspace (cookie) with own runs and an own audit log. The visitor clicks through
 * the three attempts and makes the human decisions, then sends fund orders to the code of each attempt. No AI key
 * is used: the AI answers are the planted-defect scenario in replay-wealth/, and every verdict is computed live by
 * the same gates as the CLI. The only code a visitor can run is the code those attempts produced; order fields are
 * plain values passed to it.
 */
public final class WealthServer {

    private static final int MAX_SESSIONS = 60;
    private static final int MAX_ACTIONS_PER_SESSION = 120;
    private static final String REJECT_NOTE = "The AI reviewer's HIGH finding is real: Integer.parseInt accepts risk "
            + "levels such as C03 or RR+2, so malformed KYC data passes AC-01. AC-01 allows only the exact codes C1-C5 "
            + "and RR1-RR5. Regression tests G-32 and G-33 added to the golden set (v1.1).";

    private final Path root;
    private final Path sessionsDir;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Object pipelineLock = new Object();
    private final ExecutorService orderExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wealth-order-check");
        t.setDaemon(true);
        return t;
    });

    private static final class Session {
        final String id;
        final Path runs;
        volatile long used = System.currentTimeMillis();
        int actions;
        final Map<String, SubscriptionPrecheck> compiled = new ConcurrentHashMap<>();

        Session(String id, Path runs) {
            this.id = id;
            this.runs = runs;
        }
    }

    private WealthServer(Path root) throws IOException {
        this.root = root;
        this.sessionsDir = Files.createTempDirectory("wealth-sessions");
    }

    public static void start(Path root, int port) throws IOException {
        WealthServer ws = new WealthServer(root);
        String envPort = System.getenv("PORT");
        int p = envPort != null ? Integer.parseInt(envPort) : port;
        HttpServer server = HttpServer.create(new InetSocketAddress(envPort != null ? "0.0.0.0" : "127.0.0.1", p), 0);
        server.createContext("/", ws::handle);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Wealth demo on http://localhost:" + p);
    }

    // ---------------------------------------------------------------- HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if ("/healthz".equals(path)) {
                send(ex, 200, "text/plain; charset=utf-8", "ok");
                return;
            }
            Session s = session(ex);
            switch (path) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8",
                        Files.readString(root.resolve("report/wealth-console.html")));
                case "/report" -> {
                    Path r = s.runs.resolve("report.html");
                    send(ex, 200, "text/html; charset=utf-8", Files.exists(r) ? Files.readString(r)
                            : "<p style='font-family:sans-serif;color:#666;padding:24px'>No runs yet. "
                            + "Start with step 1.</p>");
                }
                case "/api/state" -> json(ex, 200, state(s));
                case "/api/action" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Map<String, Object> body = Json.parseObject(readBody(ex));
                    json(ex, 200, action(s, String.valueOf(body.get("action")), body));
                }
                case "/api/order" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    json(ex, 200, order(s, Json.parseObject(readBody(ex))));
                }
                default -> send(ex, 404, "text/plain; charset=utf-8", "Not found");
            }
        } catch (RuntimeException e) {
            json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private Session session(HttpExchange ex) throws IOException {
        String cookie = ex.getRequestHeaders().getFirst("Cookie");
        String sid = null;
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && "wealth_sid".equals(kv[0])) {
                    sid = kv[1];
                }
            }
        }
        Session s = sid == null ? null : sessions.get(sid);
        if (s == null) {
            while (sessions.size() >= MAX_SESSIONS) {
                Session old = sessions.values().stream().min(Comparator.comparingLong(x -> x.used)).orElseThrow();
                sessions.remove(old.id);
                deleteTree(old.runs.getParent());
            }
            String id = UUID.randomUUID().toString();
            Path dir = Files.createDirectories(sessionsDir.resolve(id).resolve("runs-wealth"));
            s = new Session(id, dir);
            sessions.put(id, s);
            boolean secure = System.getenv("PORT") != null;
            ex.getResponseHeaders().add("Set-Cookie", "wealth_sid=" + id + "; Path=/; HttpOnly; SameSite=Lax"
                    + (secure ? "; Secure" : ""));
        }
        s.used = System.currentTimeMillis();
        return s;
    }

    // ---------------------------------------------------------------- the three attempts

    /** Each run in this session, oldest first: attempt, verdict, run id. */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> runs(Session s) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(s.runs)) {
            return out;
        }
        try (Stream<Path> st = Files.list(s.runs)) {
            for (Path d : st.filter(Files::isDirectory).sorted().toList()) {
                Path r = d.resolve("result.json");
                if (Files.exists(r)) {
                    Map<String, Object> res = Json.parseObject(Files.readString(r));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("runId", res.get("runId"));
                    m.put("attempt", ((Number) res.get("attempt")).intValue());
                    m.put("verdict", res.get("verdict"));
                    m.put("summary", gateLine(res));
                    out.add(m);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String gateLine(Map<String, Object> res) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> g : (List<Map<String, Object>>) res.get("gates")) {
            parts.add(g.get("id") + " " + g.get("status"));
        }
        return String.join(" · ", parts);
    }

    /** The next step the visitor may take, so the demo always follows the same story. */
    private String next(List<Map<String, Object>> runs) {
        if (runs.isEmpty()) {
            return "run1";
        }
        Map<String, Object> last = runs.get(runs.size() - 1);
        int a = (int) last.get("attempt");
        String v = String.valueOf(last.get("verdict"));
        if (a == 1) {
            return "run2";
        }
        if (a == 2) {
            return WealthPipeline.VERDICT_READY.equals(v) ? "reject2" : "run3";
        }
        return WealthPipeline.VERDICT_READY.equals(v) ? "approve3" : "done";
    }

    private Map<String, Object> state(Session s) throws IOException {
        List<Map<String, Object>> runs = runs(s);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("runs", runs);
        st.put("next", next(runs));
        return st;
    }

    private Map<String, Object> action(Session s, String action, Map<String, Object> body) throws IOException {
        if (++s.actions > MAX_ACTIONS_PER_SESSION) {
            throw new IllegalStateException("Too many actions in this session. Reset and start again.");
        }
        if ("reset".equals(action)) {
            deleteTree(s.runs);
            Files.createDirectories(s.runs);
            s.compiled.clear();
            return state(s);
        }
        List<Map<String, Object>> runs = runs(s);
        String expected = next(runs);
        if (!expected.equals(action)) {
            throw new IllegalStateException("Next step is " + expected + ", not " + action + ".");
        }
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        if ((action.startsWith("reject") || action.startsWith("approve")) && name.isBlank()) {
            throw new IllegalArgumentException("Type your name: a named person makes this decision.");
        }
        String log;
        synchronized (pipelineLock) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream old = System.out;
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            try {
                switch (action) {
                    case "run1" -> WealthMain.run(root, s.runs, 1, false, "1.0");
                    case "run2" -> WealthMain.run(root, s.runs, 2, false, "1.0");
                    case "run3" -> WealthMain.run(root, s.runs, 3, false, "1.1");
                    case "reject2" -> WealthMain.decide(root, s.runs, Map.of("by", name, "note", REJECT_NOTE), false);
                    case "approve3" -> WealthMain.decide(root, s.runs, Map.of("by", name, "note",
                            "Accepted. All gates passed, including the NF-05 null guard the gate added. The LOW "
                                    + "overflow note is outside any real portfolio size."), true);
                    default -> throw new IllegalArgumentException("Unknown action " + action);
                }
                // run ids are per second: keep two clicks in one second from sharing a folder
                Thread.sleep(1100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                System.setOut(old);
            }
            log = buf.toString(StandardCharsets.UTF_8).replaceAll("Report: .*\\R?", "")
                    .replace("runs-wealth/audit-log.jsonl", "this session's audit log");
        }
        Map<String, Object> st = state(s);
        st.put("log", log.strip());
        return st;
    }

    // ---------------------------------------------------------------- send a fund order to each attempt's code

    private Map<String, Object> order(Session s, Map<String, Object> b) throws IOException {
        if (++s.actions > MAX_ACTIONS_PER_SESSION * 3) {
            throw new IllegalStateException("Too many orders in this session. Reset and start again.");
        }
        SubscriptionRequest req = new SubscriptionRequest(
                str(b.get("clientRiskLevel")), str(b.get("fundRiskLevel")), date(b.get("kycAssessedOn")),
                minor(b.get("amount")), minor(b.get("minimum")), minor(b.get("holding")), minor(b.get("portfolio")));
        LocalDate tradeDate = date(b.get("tradeDate"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> r : runs(s)) {
            String runId = String.valueOf(r.get("runId"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("attempt", r.get("attempt"));
            m.put("verdict", r.get("verdict"));
            SubscriptionPrecheck impl = load(s, runId);
            Future<String> f = orderExecutor.submit(() -> impl.check(req, tradeDate));
            try {
                m.put("code", f.get(1000, TimeUnit.MILLISECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                f.cancel(true);
                m.put("code", "TIMEOUT");
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                m.put("code", "CRASH: " + c.getClass().getSimpleName());
            }
            results.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", results);
        return out;
    }

    private SubscriptionPrecheck load(Session s, String runId) throws IOException {
        SubscriptionPrecheck c = s.compiled.get(runId);
        if (c != null) {
            return c;
        }
        String src = Files.readString(s.runs.resolve(runId).resolve(WealthPipeline.CANDIDATE_FILE));
        InMemoryCompiler.Result r = new InMemoryCompiler().compile(WealthGates.CANDIDATE_CLASS, src);
        if (!r.ok()) {
            throw new IllegalStateException("Code of " + runId + " does not compile.");
        }
        try {
            c = (SubscriptionPrecheck) r.loadedClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot load " + runId);
        }
        s.compiled.put(runId, c);
        return c;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(o));
    }

    /** Amounts arrive in whole currency units with up to two decimals; the contract uses minor units (x100). */
    private static long minor(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return 0;
        }
        return new java.math.BigDecimal(String.valueOf(o)).movePointRight(2)
                .setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
    }

    // ---------------------------------------------------------------- plumbing

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(16_384);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.writeCompact(body));
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

    private static void deleteTree(Path p) {
        if (p == null || !Files.exists(p)) {
            return;
        }
        try (Stream<Path> st = Files.walk(p)) {
            for (Path x : st.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x);
            }
        } catch (IOException ignored) {
            // best effort: the temp dir is discarded with the container anyway
        }
    }
}
