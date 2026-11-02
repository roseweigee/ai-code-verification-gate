package dev.aiverify.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.aiverify.json.Json;
import dev.aiverify.pipeline.AuditLog;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * A local web console for presenting the gate. Every button runs the real CLI command in a
 * separate process, so what the screen shows is exactly what the command line produces.
 *
 * <p>Locally it binds to 127.0.0.1 only. In a container (the PORT environment variable is set, as on Cloud Run
 * or Render) it listens on all interfaces, and then refuses to start without DEMO_PASSWORD: every page and API
 * call needs that password (HTTP Basic, any user name), because the buttons run real commands.
 */
public final class ConsoleServer {

    private static final List<String> PROJECTS = List.of("before", "after");
    /** Only CD runs and drills started after this moment are shown: every console session starts empty. */
    private static final java.time.Instant SESSION_START = java.time.Instant.now().minusSeconds(5);
    private static final String SPECBLAST_PROJECT = "examples/specblast-iso8583/project";

    /** Adds --run for a run id the console knows (three-attempt runs, or specblast/ runs). Anything else is refused. */
    private static boolean addRun(List<String> args, Map<String, String> q) {
        String run = q.getOrDefault("run", "");
        if (run.isEmpty()) {
            return false;
        }
        if (!run.matches("(specblast/)?run-[0-9]{8}-[0-9]{6}-(a[0-9]+|sb)")) {
            throw new IllegalArgumentException("unknown run");
        }
        args.addAll(List.of("--run", run));
        return true;
    }

    private final Path root;
    private final byte[] password;
    private final boolean hosted;
    private final CicdStatus cicd = new CicdStatus();
    private final ChainConsole chain;
    /** Locally: one session over the repository itself. Hosted: one per visitor (cookie), each with its own runs/. */
    private final Session local;
    private final Map<String, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path sessionsDir;
    private final DemoMailer mailLimit;
    private final boolean mailOn;
    /** At most two gate runs at the same time on the hosted demo (each one compiles and runs mutation tests). */
    private final java.util.concurrent.Semaphore cpu = new java.util.concurrent.Semaphore(2, true);

    static final int MAX_SESSIONS = 60;
    static final long SESSION_IDLE_MS = 3 * 3_600_000L;
    static final int ACTIONS_PER_HOUR = 60;
    private static final List<String> MAIL_ACTIONS = List.of("attempt1", "attempt2", "attempt3", "approve", "reject", "evidence");
    /** The cloud demo is public: a visitor's workspace may email at most this many recipients. */
    static final int MAX_RECIPIENTS_HOSTED = 5;
    static final int MAX_RECIPIENTS_LOCAL = 30;

    /** One visitor's workspace: the shared, read-only repository plus a runs/ folder of its own. */
    static final class Session {
        final String id;
        final Path root;
        final LiveService live;
        final java.time.Instant start = java.time.Instant.now().minusSeconds(5);
        final java.util.ArrayDeque<Long> actions = new java.util.ArrayDeque<>();
        volatile long used = System.currentTimeMillis();
        volatile String email = "";
        /** Local console only: email settings typed in the page, kept in memory unless the person saves them. */
        volatile String mailUser = "";
        volatile String mailPassword = "";
        /** Cloud demo: addresses over their rate limit for the current action (not emailed this time). */
        volatile String mailSkip = "";
        /** Cloud demo: documents this visitor uploaded to the chain page. */
        final java.util.concurrent.atomic.AtomicInteger uploads = new java.util.concurrent.atomic.AtomicInteger();

        Session(String id, Path root) {
            this.id = id;
            this.root = root;
            this.live = new LiveService(root);
        }
    }

    private ConsoleServer(Path root, String password) throws IOException {
        this.root = root;
        this.password = password == null ? null : password.getBytes(StandardCharsets.UTF_8);
        this.hosted = password != null;
        this.local = new Session("local", root);
        this.sessionsDir = hosted ? Files.createTempDirectory("gate-sessions") : null;
        this.mailLimit = new DemoMailer(root, 15);
        this.chain = new ChainConsole(root, ChainConsole.findSpecForge(root));
        this.mailOn = hosted && new dev.aiverify.notify.Notifier(root).canDeliver();
    }

    /** The visitor's session, from the gate_sid cookie; a new one (with a Set-Cookie header) when there is none. */
    private Session session(HttpExchange ex) throws IOException {
        if (!hosted) {
            return local;
        }
        String sid = null;
        for (String h : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String c : h.split(";")) {
                String t = c.trim();
                if (t.startsWith("gate_sid=") && t.substring(9).matches("[0-9a-f]{32}")) {
                    sid = t.substring(9);
                }
            }
        }
        Session s = sid == null ? null : sessions.get(sid);
        if (s == null) {
            s = newSession();
            ex.getResponseHeaders().add("Set-Cookie", "gate_sid=" + s.id + "; Path=/; HttpOnly; Secure; SameSite=Lax");
        }
        s.used = System.currentTimeMillis();
        return s;
    }

    private synchronized Session newSession() throws IOException {
        long now = System.currentTimeMillis();
        for (Session old : new ArrayList<>(sessions.values())) {
            if (now - old.used > SESSION_IDLE_MS) {
                dropSession(old);
            }
        }
        while (sessions.size() >= MAX_SESSIONS) {
            dropSession(sessions.values().stream().min(Comparator.comparingLong(x -> x.used)).orElseThrow());
        }
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder id = new StringBuilder();
        for (byte x : b) {
            id.append(String.format("%02x", x));
        }
        Path dir = Files.createDirectories(sessionsDir.resolve(id.toString()));
        // everything is shared read-only through links, except runs/ (this visitor's own) and private files
        try (Stream<Path> st = Files.list(root)) {
            for (Path e : st.toList()) {
                String n = e.getFileName().toString();
                if (n.equals("config")) {
                    // a real folder: this visitor's recipients.json is written here, the shared files are linked
                    Path cfg = Files.createDirectories(dir.resolve("config"));
                    try (Stream<Path> cs = Files.list(e)) {
                        for (Path f : cs.toList()) {
                            if (!f.getFileName().toString().equals("recipients.json")) {
                                Files.createSymbolicLink(cfg.resolve(f.getFileName().toString()), f);
                            }
                        }
                    }
                } else if (!n.equals("runs") && !n.equals(".git") && !n.equals("notify.properties")) {
                    Files.createSymbolicLink(dir.resolve(n), e);
                }
            }
        }
        Session s = new Session(id.toString(), dir);
        sessions.put(s.id, s);
        System.out.println("New demo session (" + sessions.size() + " open).");
        return s;
    }

    private void dropSession(Session s) {
        sessions.remove(s.id);
        try (Stream<Path> st = Files.walk(s.root)) {
            for (Path p : st.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p); // links are removed, never followed
            }
        } catch (IOException ignored) {
            // the temporary folder is gone with the container anyway
        }
    }

    public static void start(Path root, int port) throws IOException {
        String envPort = System.getenv("PORT");
        boolean hosted = envPort != null && !envPort.isBlank();
        String password = System.getenv("DEMO_PASSWORD");
        if (hosted) {
            port = Integer.parseInt(envPort.trim());
            if (password == null || password.length() < 8) {
                throw new IllegalStateException("Hosted mode (PORT is set) needs DEMO_PASSWORD with at least 8 "
                        + "characters: the buttons run real commands, so the page must not be open to everyone.");
            }
        }
        ConsoleServer app = new ConsoleServer(root, hosted ? password : null);
        startSpecBlast(root);
        if (!hosted && !"1".equals(System.getenv("KEEP_RUNS"))) {
            // every console session starts empty: no attempts, no decisions, an empty audit log
            app.deleteRuns();
            System.out.println("Started fresh: previous runs and the audit log were cleared (set KEEP_RUNS=1 to keep them).");
        }
        HttpServer server = HttpServer.create(new InetSocketAddress(hosted ? "0.0.0.0" : "127.0.0.1", port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(hosted ? Executors.newFixedThreadPool(6) : Executors.newSingleThreadExecutor());
        server.start();
        if (hosted) {
            System.out.println("Console listening on port " + port + " (password required, one workspace per visitor, demo email "
                    + (app.mailOn ? "on" : "off") + ").");
            return;
        }
        String url = "http://127.0.0.1:" + port + "/";
        System.out.println("Console running at " + url);
        System.out.println("Press Ctrl+C to stop.");
        app.openBrowser(url);
    }

    /** HTTP Basic check against DEMO_PASSWORD; the user name is ignored. Constant-time comparison. */
    private boolean authorized(HttpExchange ex) {
        if (password == null) {
            return true;
        }
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(h.substring(6).trim()),
                    StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            byte[] given = (colon < 0 ? decoded : decoded.substring(colon + 1)).getBytes(StandardCharsets.UTF_8);
            return java.security.MessageDigest.isEqual(given, password);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if ("/healthz".equals(path)) {
                send(ex, 200, "text/plain; charset=utf-8", "ok");
                return;
            }
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"AI Code Verification Gate demo\", charset=\"UTF-8\"");
                send(ex, 401, "text/plain; charset=utf-8", "Password required.");
                return;
            }
            Map<String, String> q = query(ex.getRequestURI());
            Session ss = session(ex);
            switch (path) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8",
                        Files.readString(root.resolve("report/console.html")));
                case "/api/state" -> json(ex, state(ss));
                case "/chain" -> send(ex, 200, "text/html; charset=utf-8", Files.readString(root.resolve("report/chain.html")));
                case "/api/chain/state" -> {
                    Map<String, Object> cs = new LinkedHashMap<>(chain.state(ss.root));
                    cs.put("hosted", hosted);
                    cs.put("mailEnabled", hosted ? mailOn : true);
                    cs.put("running", chain.running(ss.root));
                    cs.put("aiEnabled", aiKey());
                    cs.put("aiLeft", hosted ? aiLeft() : null);
                    cs.put("uploadsLeft", hosted ? Math.max(0, UPLOADS_PER_SESSION - ss.uploads.get()) : null);
                    cs.put("uploadMaxBytes", hosted ? UPLOAD_MAX_BYTES_HOSTED : 2_000_000);
                    cs.put("specblastLive", specblastUrl != null);
                    json(ex, cs);
                }
                case "/api/chain/upload" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    json(ex, chainUpload(ss, ex, q));
                }
                case "/api/chain/pipeline" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Map<String, String> p = new LinkedHashMap<>(q);
                    byte[] raw = ex.getRequestBody().readNBytes(200_000);
                    if (raw.length > 0) {
                        Object b = Json.parse(new String(raw, StandardCharsets.UTF_8));
                        if (b instanceof Map<?, ?> m) {
                            m.forEach((k, v) -> p.put(String.valueOf(k), v instanceof String sv ? sv : Json.write(v)));
                        }
                    }
                    json(ex, chainPipeline(ss, p, client(ex)));
                }
                case "/api/chain/evidence" -> {
                    Path f = chain.evidenceFile(ss.root);
                    if (f == null) {
                        json(ex, 404, Map.of("error", "no evidence pack yet"));
                    } else {
                        send(ex, 200, "text/html; charset=utf-8", Files.readString(f));
                    }
                }
                case "/api/chain/act" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    json(ex, chainAct(ss, q, client(ex)));
                }
                case "/api/inbox" -> json(ex, Map.of("mails", inbox(ss)));
                case "/api/recipients" -> {
                    int max = hosted ? MAX_RECIPIENTS_HOSTED : MAX_RECIPIENTS_LOCAL;
                    if ("POST".equals(ex.getRequestMethod())) {
                        try {
                            Object list = Json.parse(q.getOrDefault("list", "[]"));
                            if (!(list instanceof List<?>)) {
                                throw new IllegalArgumentException("invalid:list");
                            }
                            @SuppressWarnings("unchecked")
                            List<Object> l = (List<Object>) list;
                            json(ex, Map.of("recipients", dev.aiverify.notify.Recipients.toList(
                                    dev.aiverify.notify.Recipients.save(ss.root, l, max)), "max", max));
                        } catch (IllegalArgumentException e) {
                            json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
                        }
                    } else {
                        json(ex, Map.of("recipients", dev.aiverify.notify.Recipients.toList(
                                dev.aiverify.notify.Recipients.load(ss.root)), "max", max,
                                "events", dev.aiverify.notify.Recipients.EVENTS));
                    }
                }
                case "/api/evidence" -> {
                    String run = q.getOrDefault("run", "");
                    if (!run.matches("(specblast/)?run-[0-9]{8}-[0-9]{6}-(a[0-9]+|sb)")) {
                        json(ex, 400, Map.of("error", "unknown run"));
                        return;
                    }
                    Path f = ss.root.resolve("runs").resolve(run).resolve("evidence/evidence.html").normalize();
                    if (!f.startsWith(ss.root.resolve("runs")) || !Files.exists(f)) {
                        json(ex, 404, Map.of("error", "no evidence pack for this run yet"));
                        return;
                    }
                    send(ex, 200, "text/html; charset=utf-8", Files.readString(f));
                }
                case "/api/mail/settings" -> {
                    if (hosted) {
                        json(ex, 405, Map.of("error", "local console only"));
                        return;
                    }
                    if ("POST".equals(ex.getRequestMethod())) {
                        json(ex, saveMailSettings(ss, q));
                    } else {
                        json(ex, mailSettings(ss));
                    }
                }
                case "/api/mail/test" -> {
                    if (hosted || !"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only, local console only"));
                        return;
                    }
                    synchronized (ss) {
                        json(ex, runCli(ss, List.of("notify-test"), null));
                    }
                }
                case "/api/session/email" -> {
                    if (!"POST".equals(ex.getRequestMethod()) || !hosted) {
                        json(ex, 405, Map.of("error", "POST only, hosted demo only"));
                        return;
                    }
                    String e = q.getOrDefault("email", "").trim();
                    if (!e.isEmpty() && !DemoMailer.valid(e)) {
                        json(ex, 400, Map.of("error", "invalid"));
                        return;
                    }
                    ss.email = e;
                    json(ex, Map.of("email", e));
                }
                case "/api/action" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    json(ex, action(ss, q, client(ex)));
                }
                case "/api/snippet" -> json(ex, snippet(q));
                case "/api/tamper-test" -> json(ex, tamperTest(ss));
                case "/api/cicd" -> json(ex, cicd.get("1".equals(q.get("refresh"))));
                case "/api/drill" -> {
                    if (!"POST".equals(ex.getRequestMethod()) || password != null) {
                        json(ex, 405, Map.of("error", "POST only, local console only"));
                        return;
                    }
                    json(ex, cicd.dispatch(!"good".equals(q.get("version"))));
                }
                case "/api/monitor" -> {
                    String st = q.getOrDefault("stable", ""), ca = q.getOrDefault("canary", "");
                    String re = "run-[0-9]{8}-[0-9]{6}-a[0-9]+";
                    if (!st.matches(re) || !ca.matches(re)) {
                        throw new IllegalArgumentException("unknown run");
                    }
                    json(ex, MonitorSim.run(ss.live, "run:" + st, "run:" + ca));
                }
                case "/pay" -> {
                    ex.getResponseHeaders().set("Location", "/");
                    ex.sendResponseHeaders(302, -1);
                }
                case "/try" -> send(ex, 200, "text/html; charset=utf-8", Files.readString(root.resolve("report/release.html")));
                case "/api/try/info" -> json(ex, tryInfo(ss, q.getOrDefault("run", "")));
                case "/api/try/authorize" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Map<String, String> tq = new java.util.HashMap<>(q);
                    tq.put("version", "run:" + q.getOrDefault("run", ""));
                    Object[] r = ss.live.authorize(tq);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) r[1];
                    json(ex, (Integer) r[0], body);
                }
                case "/api/pay" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Object[] r = ss.live.authorize(q);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) r[1];
                    json(ex, (Integer) r[0], body);
                }
                default -> json(ex, 404, Map.of("error", "not found"));
            }
        } catch (Exception e) {
            json(ex, 500, Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            ex.close();
        }
    }

    // ---- actions: each one runs the real CLI ----------------------------------------------------

    private Map<String, Object> action(Session ss, Map<String, String> q, String client) throws IOException, InterruptedException {
        String name = q.getOrDefault("name", "");
        if (hosted) {
            synchronized (ss.actions) {
                long now = System.currentTimeMillis();
                while (!ss.actions.isEmpty() && now - ss.actions.peekFirst() > 3_600_000L) {
                    ss.actions.pollFirst();
                }
                if (ss.actions.size() >= ACTIONS_PER_HOUR) {
                    return Map.of("error", "Too many actions in this session for now. Please try again later.");
                }
                ss.actions.add(now);
            }
        }
        synchronized (ss) {
            return actionLocked(ss, q, name, client);
        }
    }

    private Map<String, Object> chainUpload(Session ss, HttpExchange ex, Map<String, String> q) throws IOException {
        String name = q.getOrDefault("name", "").replaceAll("[^A-Za-z0-9._\\-\\u4e00-\\u9fff]", "_");
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!List.of("md", "txt", "html", "htm", "docx", "pdf").contains(ext)) {
            return Map.of("error", "Upload a .md, .txt, .html, .docx or .pdf file.");
        }
        int max = hosted ? UPLOAD_MAX_BYTES_HOSTED : 2_000_000;
        byte[] data = ex.getRequestBody().readNBytes(max + 1);
        if (data.length > max) {
            return Map.of("error", "The file is larger than " + max / 1000 + " KB.");
        }
        if (data.length == 0) {
            return Map.of("error", "The file is empty.");
        }
        if (hosted && ss.uploads.incrementAndGet() > UPLOADS_PER_SESSION) {
            return Map.of("error", "This demo allows " + UPLOADS_PER_SESSION + " documents per visitor.");
        }
        Path dir = Files.createDirectories(ss.root.resolve("runs/chain-upload"));
        Files.write(dir.resolve(name), data);
        return Map.of("upload", name, "bytes", data.length);
    }

    private Map<String, Object> chainPipeline(Session ss, Map<String, String> p, String client) throws IOException, InterruptedException {
        if (hosted) {
            synchronized (ss.actions) {
                long now = System.currentTimeMillis();
                while (!ss.actions.isEmpty() && now - ss.actions.peekFirst() > 3_600_000L) {
                    ss.actions.pollFirst();
                }
                if (ss.actions.size() >= ACTIONS_PER_HOUR) {
                    return Map.of("error", "Too many actions in this session for now. Please try again later.");
                }
                ss.actions.add(now);
            }
        }
        String up = p.remove("upload");
        if (up != null && !up.isBlank()) {
            Path f = ss.root.resolve("runs/chain-upload").resolve(up).normalize();
            if (!f.startsWith(ss.root.resolve("runs/chain-upload")) || !Files.exists(f)) {
                return Map.of("error", "Upload the document again.");
            }
            if (!aiKey() || (hosted && aiLeft() <= 0)) {
                return Map.of("error", !aiKey() ? "No AI key on this server: use one of the examples."
                        : "Today's AI budget for this demo is used up. The examples still work.");
            }
            p.put("file", f.toString());
        }
        try {
            return chain.pipeline(ss.root, p.getOrDefault("action", ""), p, chainCli(ss, client));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> chainAct(Session ss, Map<String, String> q, String client) throws IOException, InterruptedException {
        if (hosted) {
            synchronized (ss.actions) {
                long now = System.currentTimeMillis();
                while (!ss.actions.isEmpty() && now - ss.actions.peekFirst() > 3_600_000L) {
                    ss.actions.pollFirst();
                }
                if (ss.actions.size() >= ACTIONS_PER_HOUR) {
                    return Map.of("error", "Too many actions in this session for now. Please try again later.");
                }
                ss.actions.add(now);
            }
        }
        ChainConsole.Cli cli = chainCli(ss, client);
        synchronized (ss) {
            try {
                return chain.act(ss.root, q.getOrDefault("step", ""), q, cli);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return Map.of("error", String.valueOf(e.getMessage()));
            }
        }
    }

    private ChainConsole.Cli chainCli(Session ss, String client) {
        return new ChainConsole.Cli() {
            @Override
            public Map<String, Object> gate(Path gateRoot, List<String> args, boolean mail) throws IOException, InterruptedException {
                String mailTo = null;
                ss.mailSkip = "";
                String note = null;
                if (hosted && mail) {
                    String[] m = mailFor(ss, client);
                    mailTo = m[0];
                    note = m[1];
                }
                Map<String, Object> r = runCli(ss, args, mailTo, gateRoot);
                if (note != null) {
                    r.put("mail", note);
                }
                return r;
            }

            @Override
            public Map<String, Object> specforge(List<String> args) throws IOException, InterruptedException {
                return runSpecForge(args, false);
            }

            @Override
            public Map<String, Object> specforgeAi(List<String> args) throws IOException, InterruptedException {
                spendAi();
                return runSpecForge(args, true);
            }

            @Override
            public Map<String, Object> specblast(Path requirements, Path outDir) throws IOException, InterruptedException {
                if (specblastUrl == null) {
                    return null;
                }
                spendAi();
                return callSpecBlast(requirements, outDir);
            }
        };
    }

    // ---- AI on the chain page: the owner's key, strictly limited ------------------------------------------------

    static final int UPLOADS_PER_SESSION = 3;
    static final int UPLOAD_MAX_BYTES_HOSTED = 20_000;
    private final int aiCallsPerDay = Integer.parseInt(System.getenv().getOrDefault("AI_CALLS_PER_DAY", "30").trim());
    private java.time.LocalDate aiDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
    private int aiUsed;
    /** SpecBlast's own service (SPECBLAST_URL, or the copy this console started on 127.0.0.1), or null. */
    static volatile String specblastUrl;

    static boolean aiKey() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return k != null && !k.isBlank();
    }

    private synchronized int aiLeft() {
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        if (!today.equals(aiDay)) {
            aiDay = today;
            aiUsed = 0;
        }
        return hosted ? Math.max(0, aiCallsPerDay - aiUsed) : Integer.MAX_VALUE;
    }

    /** One AI call (SpecForge extract/refine, SpecBlast). The cloud demo allows AI_CALLS_PER_DAY a day, site-wide. */
    private synchronized void spendAi() {
        if (!aiKey()) {
            throw new IllegalStateException("No AI key on this server (ANTHROPIC_API_KEY), so documents cannot be read by the AI here.");
        }
        if (aiLeft() <= 0) {
            throw new IllegalStateException("Today's AI budget for this demo is used up (" + aiCallsPerDay
                    + " calls a day). The examples still work; try your own document tomorrow.");
        }
        aiUsed++;
    }

    /** POST requirements.md to SpecBlast, exactly as its web page does, and unzip the project it returns. */
    private Map<String, Object> callSpecBlast(Path requirements, Path outDir) throws IOException, InterruptedException {
        String boundary = "----chain" + Long.toHexString(System.nanoTime());
        byte[] file = Files.readAllBytes(requirements);
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"requirements.md\"\r\n"
                + "Content-Type: text/markdown\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(file);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        long t0 = System.currentTimeMillis();
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(specblastUrl + "/api/v1/generate/zip"))
                .timeout(java.time.Duration.ofMinutes(10))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        java.net.http.HttpResponse<byte[]> res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("command", "POST SpecBlast /api/v1/generate/zip (requirements.md)");
        if (res.statusCode() != 200) {
            r.put("exitCode", 1);
            r.put("output", "SpecBlast answered HTTP " + res.statusCode() + ": "
                    + new String(res.body(), StandardCharsets.UTF_8).replaceAll("sk-ant[A-Za-z0-9_-]+", "***"));
            return r;
        }
        Files.createDirectories(outDir);
        Path base = outDir.toAbsolutePath().normalize();
        List<String> names = new ArrayList<>();
        try (java.util.zip.ZipInputStream z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(res.body()))) {
            java.util.zip.ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                Path t = base.resolve(e.getName()).normalize();
                if (!t.startsWith(base) || e.isDirectory()) {
                    continue; // never outside the project folder
                }
                Files.createDirectories(t.getParent());
                Files.write(t, z.readNBytes(2_000_000));
                names.add(base.relativize(t).toString());
            }
        }
        r.put("exitCode", names.isEmpty() ? 1 : 0);
        r.put("output", "SpecBlast generated " + names.size() + " files in " + (System.currentTimeMillis() - t0) / 1000 + " s:\n"
                + String.join("\n", names));
        return r;
    }

    /** Starts SpecBlast's own service next to the console (127.0.0.1 only) when its jar is in the image. */
    static void startSpecBlast(Path root) {
        String url = System.getenv("SPECBLAST_URL");
        if (url != null && !url.isBlank()) {
            specblastUrl = url.strip().replaceAll("/+$", "");
            System.out.println("SpecBlast: " + specblastUrl);
            return;
        }
        Path jar = root.resolve("specblast/specblast.jar");
        if (!Files.exists(jar) || !aiKey()) {
            System.out.println("SpecBlast: not started (" + (Files.exists(jar) ? "no ANTHROPIC_API_KEY" : "no specblast/specblast.jar") + ").");
            return;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx384m", "-jar", jar.toString(), "--server.port=8091", "--server.address=127.0.0.1")
                    .redirectErrorStream(true).redirectOutput(java.nio.file.Files.createTempFile("specblast", ".log").toFile());
            pb.environment().put("SPECBLAST_CLOUD_ENABLED", "false");
            pb.environment().remove("PORT");
            pb.environment().remove("DEMO_PASSWORD");
            pb.environment().remove("GH_DISPATCH_TOKEN");
            Process p = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(p::destroy));
            specblastUrl = "http://127.0.0.1:8091";
            System.out.println("SpecBlast: started next to the console on 127.0.0.1:8091.");
        } catch (IOException e) {
            System.out.println("SpecBlast: could not start (" + e.getMessage() + ").");
        }
    }

    /** SpecForge's CLI in its own folder (its glossary is config/glossary.json there). The AI key only for extract/refine. */
    private Map<String, Object> runSpecForge(List<String> args, boolean withKey) throws IOException, InterruptedException {
        Path sf = chain.specforgeHome();
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp", sf.resolve("build/classes").toString(), "dev.specforge.Main"));
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(sf.toFile()).redirectErrorStream(true);
        if (!withKey) {
            pb.environment().remove("ANTHROPIC_API_KEY");
        }
        if (hosted) {
            pb.environment().remove("GH_DISPATCH_TOKEN");
            pb.environment().remove("DEMO_PASSWORD");
            pb.environment().remove("SMTP_PASSWORD");
            cpu.acquire();
        }
        String out;
        int code;
        try {
            Process p = pb.start();
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            code = p.waitFor();
        } finally {
            if (hosted) {
                cpu.release();
            }
        }
        StringBuilder shown = new StringBuilder("specforge");
        for (String a : args) {
            String b = a.startsWith("/") ? Path.of(a).getFileName().toString() : a;
            shown.append(' ').append(b.contains(" ") ? "\"" + b + "\"" : b);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("command", shown.toString());
        r.put("exitCode", code);
        r.put("output", out.lines().filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS")).reduce("", (a, b) -> a + b + "\n"));
        return r;
    }

    private Map<String, Object> actionLocked(Session ss, Map<String, String> q, String name, String client)
            throws IOException, InterruptedException {
        String by = q.getOrDefault("by", "").trim();
        String note = q.getOrDefault("note", "").trim();
        List<String> args = new ArrayList<>();
        switch (name) {
            case "reset" -> {
                deleteRuns(ss.root);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("command", "(cleared runs/)");
                r.put("exitCode", 0);
                r.put("output", "All previous runs and the audit log were cleared.");
                return r;
            }
            case "attempt1" -> args.addAll(List.of("run", "--attempt", "1", "--golden", "1.0"));
            case "attempt2" -> args.addAll(List.of("run", "--attempt", "2", "--golden", "1.0"));
            case "attempt3" -> args.addAll(List.of("run", "--attempt", "3", "--golden", "1.1"));
            case "reject" -> {
                args.addAll(List.of("reject", "--by", by, "--note", note));
                addRun(args, q);
            }
            case "approve" -> {
                args.addAll(List.of("approve", "--by", by));
                addRun(args, q);
                if (!note.isEmpty()) {
                    args.addAll(List.of("--note", note));
                }
                if ("true".equals(q.get("ackHigh"))) {
                    args.add("--ack-high");
                }
                if (password == null) {
                    // local console: approval hands the code to the CD pipeline (git push -> GitHub Actions)
                    args.add("--release");
                }
            }
            case "project" -> {
                String which = q.getOrDefault("which", "");
                if ("iso".equals(which)) {
                    args.addAll(List.of("verify-project", SPECBLAST_PROJECT, "--label", "specblast-iso8583"));
                } else if ("iso-fixed".equals(which)) {
                    args.addAll(List.of("verify-project", SPECBLAST_PROJECT + "-fixed", "--label", "specblast-iso8583-fixed"));
                } else if (PROJECTS.contains(which)) {
                    args.addAll(List.of("verify-project", "examples/specblast-product/" + which));
                } else {
                    throw new IllegalArgumentException("unknown project");
                }
            }
            case "production" -> {
                args.add("production");
                if (!addRun(args, q)) {
                    throw new IllegalArgumentException("unknown run");
                }
            }
            case "specblast" -> args.addAll(List.of("verify-specblast", SPECBLAST_PROJECT, "--code-only"));
            case "evidence" -> {
                args.add("evidence");
                if (!addRun(args, q)) {
                    throw new IllegalArgumentException("unknown run");
                }
            }
            case "audit" -> args.add("audit");
            default -> throw new IllegalArgumentException("unknown action");
        }
        String mailTo = null;
        String mailNote = null;
        ss.mailSkip = "";
        if (hosted && MAIL_ACTIONS.contains(name)) {
            String[] m = mailFor(ss, client);
            mailTo = m[0];
            mailNote = m[1];
        }
        Map<String, Object> r = runCli(ss, args, mailTo);
        if (mailNote != null) {
            r.put("mail", mailNote);
        }
        if (hosted && "approve".equals(name) && Integer.valueOf(0).equals(r.get("exitCode"))) {
            r.putAll(demoRelease(ss, by));
        }
        return r;
    }

    /**
     * Cloud demo: which addresses this action may email (the visitor's own and their recipients, each rate limited).
     * Returns {NOTIFY_TO or null, a note for the page or null}; sets ss.mailSkip to the addresses over their limit.
     */
    private String[] mailFor(Session ss, String client) {
        ss.mailSkip = "";
        List<String> addrs = new ArrayList<>();
        if (!ss.email.isEmpty()) {
            addrs.add(ss.email);
        }
        for (dev.aiverify.notify.Recipients.Recipient rc : dev.aiverify.notify.Recipients.load(ss.root)) {
            if (addrs.stream().noneMatch(a -> a.equalsIgnoreCase(rc.email()))) {
                addrs.add(rc.email());
            }
        }
        if (!mailOn) {
            return new String[] {null, "off"};
        }
        if (addrs.isEmpty()) {
            return new String[] {null, "no-address"};
        }
        List<String> skip = new ArrayList<>();
        String why = null;
        for (String a : addrs) {
            String w = mailLimit.allow(a, client, 1, false);
            if (w != null) {
                skip.add(a);
                why = w;
            }
        }
        ss.mailSkip = String.join(",", skip);
        if (skip.size() == addrs.size()) {
            return new String[] {null, why};
        }
        return new String[] {!ss.email.isEmpty() && !skip.contains(ss.email) ? ss.email : "", null};
    }

    /** Cloud demo: one real release at a time (never while one is running on GitHub), at most 20 a day. */
    static final long RELEASE_GAP_MS = 60_000L; // only against double clicks
    static final int RELEASES_PER_DAY = 20;
    private final java.util.ArrayDeque<Long> releases = new java.util.ArrayDeque<>();

    /**
     * After a visitor's approval: if the approved code is byte-for-byte the code already approved in
     * release/iso8583/ (same SHA-256), start the real CD pipeline for it. Anything else is never deployed.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> demoRelease(Session ss, String by) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Path latest;
            try (Stream<Path> st = Files.list(ss.root.resolve("runs"))) {
                latest = st.filter(p -> p.getFileName().toString().startsWith("run-")).max(Comparator.naturalOrder())
                        .orElse(null);
            }
            Path approved = ss.root.resolve("release/iso8583/approval.json");
            if (latest == null || !Files.exists(approved)) {
                out.put("release", "not-approved-code");
                return out;
            }
            Map<String, Object> res = (Map<String, Object>) Json.parse(Files.readString(latest.resolve("result.json")));
            Map<String, Object> app = (Map<String, Object>) Json.parse(Files.readString(approved));
            if (!"APPROVED".equals(res.get("verdict"))) {
                out.put("release", "not-approved-code");
                return out;
            }
            boolean sameCode = String.valueOf(app.get("candidateSha256")).equals(String.valueOf(res.get("candidateSha256")));
            // attempt 2 approved over its HIGH finding: a canary drill that always switches back (never a release)
            boolean demoDrill = !sameCode && Integer.valueOf(2).equals(((Number) res.getOrDefault("attempt", 0)).intValue());
            if (!sameCode && !demoDrill) {
                out.put("release", "not-approved-code");
                return out;
            }
            long now = System.currentTimeMillis();
            synchronized (releases) {
                while (!releases.isEmpty() && now - releases.peekFirst() > 86_400_000L) {
                    releases.pollFirst();
                }
                if ((!releases.isEmpty() && now - releases.peekLast() < RELEASE_GAP_MS) || cicd.releaseRunning()) {
                    out.put("release", "busy");
                    return out;
                }
                if (releases.size() >= RELEASES_PER_DAY) {
                    out.put("release", "day");
                    return out;
                }
                String who = by.replaceAll("[^\\p{L}\\p{N} .'-]", "").strip();
                if (who.length() > 40) {
                    who = who.substring(0, 40);
                }
                Map<String, Object> d = demoDrill ? cicd.dispatchDemoDrill()
                        : cicd.dispatchRelease(who.isEmpty() ? "a visitor" : who, latest.getFileName().toString());
                if ("started".equals(d.get("release")) || "drill-started".equals(d.get("release"))) {
                    releases.add(now);
                }
                out.putAll(d);
            }
        } catch (Exception e) {
            out.put("release", "error");
            out.put("releaseError", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private Map<String, Object> runCli(Session ss, List<String> args, String mailTo) throws IOException, InterruptedException {
        return runCli(ss, args, mailTo, ss.root);
    }

    private Map<String, Object> runCli(Session ss, List<String> args, String mailTo, Path gateRoot)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        StringBuilder cp = new StringBuilder();
        for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            cp.append(cp.length() == 0 ? "" : java.io.File.pathSeparator).append(Path.of(e).toAbsolutePath());
        }
        cmd.add(cp.toString());
        cmd.add("-Daiverify.root=" + gateRoot);
        cmd.add("dev.aiverify.Main");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(gateRoot.toFile()).redirectErrorStream(true);
        if (hosted) {
            // the demo runs the seeded-defect attempts: no AI key, no GitHub token, and mail only to this visitor
            Map<String, String> env = pb.environment();
            env.remove("ANTHROPIC_API_KEY");
            env.remove("GITHUB_TOKEN");
            env.remove("GH_DISPATCH_TOKEN");
            env.remove("DEMO_PASSWORD");
            env.put("NOTIFY_TO", mailTo == null ? "" : mailTo);
            env.put("NOTIFY_SKIP", ss.mailSkip);
            if (mailTo == null) {
                env.put("NOTIFY_ENABLED", "false");
            }
        } else if (!ss.email.isEmpty()) {
            // local console: settings typed in the page win over notify.properties, for this run only
            Map<String, String> env = pb.environment();
            env.put("NOTIFY_TO", ss.email);
            if (!ss.mailUser.isEmpty()) {
                env.put("SMTP_USER", ss.mailUser);
                env.put("SMTP_PASSWORD", ss.mailPassword);
                env.put("NOTIFY_ENABLED", "true");
            }
        }
        if (hosted) {
            cpu.acquire();
        }
        String out;
        int code;
        try {
            Process p = pb.start();
            out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            code = p.waitFor();
        } finally {
            if (hosted) {
                cpu.release();
            }
        }
        StringBuilder shown = new StringBuilder("./run.sh");
        for (String a0 : args) {
            String a = a0.replace(gateRoot + "/", "").replace(ss.root + "/", "");
            shown.append(' ').append(a.contains(" ") || a.isEmpty() ? "\"" + a + "\"" : a);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("command", shown.toString());
        r.put("exitCode", code);
        r.put("output", out.lines().filter(l -> !l.startsWith("Picked up JAVA_TOOL_OPTIONS")).reduce("", (a, b) -> a + b + "\n"));
        return r;
    }

    /** What the live-test page shows above the form: which run, what the gate and the human decided. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> tryInfo(Session ss, String run) throws IOException {
        if (!run.matches("run-[0-9]{8}-[0-9]{6}-a[0-9]+")) {
            throw new IllegalArgumentException("unknown run");
        }
        Map<String, Object> r = (Map<String, Object>) Json.parse(
                Files.readString(ss.root.resolve("runs").resolve(run).resolve("result.json")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "run");
        out.put("runId", r.get("runId"));
        out.put("attempt", r.get("attempt"));
        out.put("verdict", r.get("verdict"));
        out.put("candidateSha256", r.get("candidateSha256"));
        out.put("autoFixed", r.get("autoFix") != null);
        List<Object> gates = new ArrayList<>();
        for (Object o : (List<Object>) r.get("gates")) {
            Map<String, Object> g = (Map<String, Object>) o;
            gates.add(Map.of("id", String.valueOf(g.get("id")), "status", String.valueOf(g.get("status"))));
            if ("G6".equals(g.get("id")) && g.get("details") instanceof Map<?, ?> d) {
                out.put("decision", d.get("decision"));
                out.put("approvedBy", d.get("decidedBy"));
                out.put("approvedAt", d.get("decidedAt"));
                out.put("note", d.get("note"));
                out.put("acceptedHighFindings", d.get("acceptedHighFindings"));
            }
        }
        out.put("gates", gates);
        return out;
    }

    private void deleteRuns() throws IOException {
        deleteRuns(root);
    }

    private static void deleteRuns(Path base) throws IOException {
        Path runs = base.resolve("runs");
        if (!Files.exists(runs)) {
            return;
        }
        try (Stream<Path> s = Files.walk(runs)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    // ---- state -----------------------------------------------------------------------------------

    private Map<String, Object> state(Session ss) throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        if (hosted) {
            // like the local console, the CD section starts empty: it shows the runs started in this session
            s.put("sessionStart", ss.start.toString());
            s.put("deployOn", System.getenv("GH_DISPATCH_TOKEN") != null && !System.getenv("GH_DISPATCH_TOKEN").isBlank());
            s.put("hosted", true);
            s.put("email", ss.email);
            s.put("mailEnabled", mailOn);
            s.put("productionUrl", System.getenv().getOrDefault("PRODUCTION_URL", ""));
        } else {
            s.put("sessionStart", SESSION_START.toString());
        }
        List<Object> runs = new ArrayList<>();
        Path runsDir = ss.root.resolve("runs");
        if (Files.isDirectory(runsDir)) {
            try (Stream<Path> st = Files.list(runsDir)) {
                for (Path d : st.filter(p -> p.getFileName().toString().startsWith("run-")).sorted().toList()) {
                    Path r = d.resolve("result.json");
                    if (Files.exists(r)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(r));
                        Path pc = d.resolve("production-check.json");
                        if (Files.exists(pc)) {
                            m.put("productionCheck", Json.parse(Files.readString(pc)));
                        }
                        m.put("evidence", Files.exists(d.resolve("evidence/evidence.html")));
                        runs.add(m);
                    }
                }
            }
        }
        s.put("runs", runs);

        Map<String, Object> projects = new LinkedHashMap<>();
        Path pdir = runsDir.resolve("projects");
        if (Files.isDirectory(pdir)) {
            try (Stream<Path> st = Files.list(pdir)) {
                for (Path d : st.filter(Files::isDirectory).sorted().toList()) {
                    Path r = d.resolve("result.json");
                    if (Files.exists(r)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(r));
                        projects.put(String.valueOf(m.get("label")), m);
                    }
                }
            }
        }
        s.put("projects", projects);

        Map<String, Object> sb = null;
        Path sbDir = runsDir.resolve("specblast");
        if (Files.isDirectory(sbDir)) {
            try (Stream<Path> st = Files.list(sbDir)) {
                Path last = st.filter(p -> p.getFileName().toString().startsWith("run-")).max(Comparator.naturalOrder())
                        .orElse(null);
                if (last != null && Files.exists(last.resolve("result.json"))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(last.resolve("result.json")));
                    m.put("runPath", "specblast/" + last.getFileName());
                    Path pc = last.resolve("production-check.json");
                    if (Files.exists(pc)) {
                        m.put("productionCheck", Json.parse(Files.readString(pc)));
                    }
                    m.put("evidence", Files.exists(last.resolve("evidence/evidence.html")));
                    if (Files.exists(last.resolve("source.txt"))) {
                        m.put("source", Files.readString(last.resolve("source.txt")).strip());
                    }
                    sb = m;
                }
            }
        }
        s.put("specblastRun", sb);
        s.put("specblastAvailable", Files.isDirectory(root.resolve(SPECBLAST_PROJECT)));

        Path log = runsDir.resolve("audit-log.jsonl");
        Map<String, Object> audit = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>();
        if (Files.exists(log)) {
            for (String line : Files.readAllLines(log)) {
                if (!line.isBlank()) {
                    entries.add(Json.parse(line));
                }
            }
        }
        audit.put("entries", entries);
        audit.put("problem", new AuditLog(log).verify());
        s.put("audit", audit);
        return s;
    }

    private Map<String, Object> snippet(Map<String, String> q) throws IOException {
        String which = q.getOrDefault("project", "");
        Path base;
        if ("specblast".equals(which)) {
            base = root.resolve(SPECBLAST_PROJECT).normalize();
        } else if (PROJECTS.contains(which)) {
            base = root.resolve("examples/specblast-product").resolve(which).normalize();
        } else {
            throw new IllegalArgumentException("unknown project");
        }
        Path file = base.resolve(q.getOrDefault("file", "")).normalize();
        if (!file.startsWith(base) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("file not found");
        }
        int line = Integer.parseInt(q.getOrDefault("line", "1"));
        List<String> lines = Files.readAllLines(file);
        int from = Math.max(1, line - 3);
        int to = Math.min(lines.size(), line + 3);
        List<Object> out = new ArrayList<>();
        for (int k = from; k <= to; k++) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("n", k);
            l.put("text", lines.get(k - 1));
            l.put("hit", k == line);
            out.add(l);
        }
        return Map.of("lines", out);
    }

    /** Edits one entry in a copy of the audit log and shows that verification catches it. The real log is untouched. */
    private Map<String, Object> tamperTest(Session ss) throws IOException {
        Path log = ss.root.resolve("runs/audit-log.jsonl");
        Map<String, Object> r = new LinkedHashMap<>();
        if (!Files.exists(log)) {
            r.put("message", "No audit log yet. Run something first.");
            return r;
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(log));
        lines.removeIf(String::isBlank);
        if (lines.size() < 2) {
            r.put("message", "Need at least two entries to show the chain breaking.");
            return r;
        }
        int target = lines.size() / 2;
        for (int k = 0; k < lines.size(); k++) {
            if (lines.get(k).contains("\"RUN_COMPLETED\"") && lines.get(k).contains("\"BLOCKED\"")) {
                target = k;
                break;
            }
        }
        String original = lines.get(target);
        String edited = original.contains("\"BLOCKED\"") ? original.replaceFirst("\"BLOCKED\"", "\"APPROVED\"")
                : original.contains("\"REJECTED_BY_HUMAN\"") ? original.replaceFirst("\"REJECTED_BY_HUMAN\"", "\"APPROVED\"")
                : original.replaceFirst("\"at\":\"", "\"at\":\"1999");
        lines.set(target, edited);
        Path copy = Files.createTempFile("audit-copy", ".jsonl");
        try {
            Files.write(copy, lines);
            r.put("editedEntry", target + 1);
            r.put("change", original.contains("\"BLOCKED\"") ? "verdict BLOCKED changed to APPROVED"
                    : original.contains("\"REJECTED_BY_HUMAN\"") ? "verdict REJECTED_BY_HUMAN changed to APPROVED"
                    : "timestamp changed");
            r.put("result", new AuditLog(copy).verify());
        } finally {
            Files.deleteIfExists(copy);
        }
        return r;
    }

    // ---- http helpers ----------------------------------------------------------------------------

    // ---- notifications: the inbox shown in the page, and email settings for the local console ----

    /** The notifications of this session, newest first, read back from runs/outbox/ exactly as they were sent. */
    private static List<Object> inbox(Session ss) throws IOException {
        Path dir = ss.root.resolve("runs/outbox");
        List<Object> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> st = Files.list(dir)) {
            files = st.filter(f -> f.getFileName().toString().endsWith(".eml"))
                    .sorted(Comparator.reverseOrder()).limit(30).toList();
        }
        for (Path f : files) {
            String eml = Files.readString(f, StandardCharsets.UTF_8);
            int split = eml.indexOf("\r\n\r\n");
            String head = split < 0 ? eml : eml.substring(0, split);
            String body = split < 0 ? "" : eml.substring(split + 4);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getFileName().toString());
            for (String line : head.split("\r\n")) {
                int c = line.indexOf(':');
                if (c > 0) {
                    String k = line.substring(0, c).trim().toLowerCase(), v = line.substring(c + 1).trim();
                    if (k.equals("subject")) {
                        m.put("subject", decodeHeader(v));
                    } else if (k.equals("to") || k.equals("date")) {
                        m.put(k, v);
                    }
                }
            }
            java.util.regex.Matcher bm = java.util.regex.Pattern.compile("boundary=\"([^\"]+)\"").matcher(head);
            List<String> attached = new ArrayList<>();
            if (bm.find()) {
                // multipart: the first part is the text, the others are attachments
                String text = "";
                for (String part : body.split("--" + java.util.regex.Pattern.quote(bm.group(1)))) {
                    int ps = part.indexOf("\r\n\r\n");
                    if (ps < 0) {
                        continue;
                    }
                    String ph = part.substring(0, ps);
                    java.util.regex.Matcher fn = java.util.regex.Pattern.compile("filename=\"([^\"]+)\"").matcher(ph);
                    if (fn.find()) {
                        attached.add(fn.group(1));
                    } else if (ph.contains("text/plain") && text.isEmpty()) {
                        text = part.substring(ps + 4);
                    }
                }
                body = text;
            }
            try {
                m.put("body", new String(java.util.Base64.getMimeDecoder().decode(body.trim()), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
            } catch (IllegalArgumentException e) {
                m.put("body", body);
            }
            m.put("attachments", attached);
            out.add(m);
        }
        return out;
    }

    private static String decodeHeader(String v) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("=\\?UTF-8\\?B\\?([A-Za-z0-9+/=]+)\\?=").matcher(v);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            mt.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    new String(java.util.Base64.getDecoder().decode(mt.group(1)), StandardCharsets.UTF_8)));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    /** What the page shows: the address mail goes to and whether sending is set up. The password is never sent back. */
    private Map<String, Object> mailSettings(Session ss) throws IOException {
        java.util.Properties file = new java.util.Properties();
        Path f = root.resolve("notify.properties");
        if (Files.exists(f)) {
            try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                file.load(r);
            }
        }
        boolean page = !ss.email.isEmpty();
        String to = page ? ss.email : file.getProperty("notify.to", "").trim();
        String user = page && !ss.mailUser.isEmpty() ? ss.mailUser : file.getProperty("smtp.user", "").trim();
        boolean pw = page && !ss.mailUser.isEmpty() ? !ss.mailPassword.isEmpty() : !file.getProperty("smtp.password", "").isBlank();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("to", to);
        m.put("user", user);
        m.put("canSend", !to.isEmpty() && !user.isEmpty() && pw);
        m.put("source", page ? "page" : Files.exists(f) ? "file" : "none");
        return m;
    }

    private Map<String, Object> saveMailSettings(Session ss, Map<String, String> q) throws IOException {
        String to = q.getOrDefault("to", "").trim();
        String user = q.getOrDefault("user", "").trim();
        // Google shows app passwords in groups of four; copied text may carry ordinary or non-breaking spaces
        String password = q.getOrDefault("password", "").replaceAll("[\\s\\u00A0]", "");
        if (to.isEmpty() || !DemoMailer.valid(to)) {
            return Map.of("error", "to");
        }
        if (!user.isEmpty() && !DemoMailer.valid(user)) {
            return Map.of("error", "user");
        }
        if (!user.isEmpty() && password.isEmpty()) {
            // keep the password already in use, but only for the same sender: a different Gmail needs its own
            java.util.Properties file = new java.util.Properties();
            Path f = root.resolve("notify.properties");
            if (Files.exists(f)) {
                try (var r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    file.load(r);
                }
            }
            if (user.equalsIgnoreCase(ss.mailUser) && !ss.mailPassword.isEmpty()) {
                password = ss.mailPassword;
            } else if (user.equalsIgnoreCase(file.getProperty("smtp.user", "").trim())) {
                password = file.getProperty("smtp.password", "").replaceAll("\\s", "");
            }
            if (password.isEmpty()) {
                return Map.of("error", "password");
            }
        }
        ss.email = to;
        ss.mailUser = user;
        ss.mailPassword = user.isEmpty() ? "" : password;
        if ("true".equals(q.get("remember")) && !user.isEmpty()) {
            // written only on this computer; notify.properties is in .gitignore
            Files.writeString(root.resolve("notify.properties"), "# Saved from the console. In .gitignore: never committed.\n"
                    + "notify.to=" + to + "\nsmtp.user=" + user + "\nsmtp.password=" + ss.mailPassword + "\n", StandardCharsets.UTF_8);
        }
        return mailSettings(ss);
    }

    /** The visitor's address as Cloud Run reports it (first X-Forwarded-For entry). Kept in memory only. */
    private static String client(HttpExchange ex) {
        String f = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (f != null && !f.isBlank()) {
            return f.split(",")[0].trim();
        }
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> m = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return m;
        }
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    private static void json(HttpExchange ex, Object body) throws IOException {
        json(ex, 200, body);
    }

    private static void json(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.writeCompact(body));
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void openBrowser(String url) {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
            } else if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", url).start();
            }
        } catch (IOException ignored) {
            // The URL is printed; opening it by hand works too.
        }
    }
}
