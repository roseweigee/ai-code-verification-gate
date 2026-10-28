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
        if (!run.matches("(specblast/|live/)?run-[0-9]{8}-[0-9]{6}-(a[0-9]+|sb)")) {
            throw new IllegalArgumentException("unknown run");
        }
        args.addAll(List.of("--run", run));
        return true;
    }

    private final Path root;
    private final byte[] password;
    private final boolean hosted;
    private final CicdStatus cicd = new CicdStatus();
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
    private static final List<String> MAIL_ACTIONS = List.of("attempt1", "attempt2", "attempt3", "live", "approve", "reject");
    /** Real Claude runs cost money: a few per visitor, and a cap for the whole server per day. */
    static final int LIVE_PER_SESSION_HOUR = 3;
    static final int LIVE_PER_DAY = 30;
    static final int LIVE_MAX_CHAIN = 5;
    private final java.util.ArrayDeque<Long> liveDay = new java.util.ArrayDeque<>();

    /** One visitor's workspace: the shared, read-only repository plus a runs/ folder of its own. */
    static final class Session {
        final String id;
        final Path root;
        final LiveService live;
        final java.time.Instant start = java.time.Instant.now().minusSeconds(5);
        final java.util.ArrayDeque<Long> actions = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Long> liveRuns = new java.util.ArrayDeque<>();
        volatile long used = System.currentTimeMillis();
        volatile String email = "";
        /** Local console only: email settings typed in the page, kept in memory unless the person saves them. */
        volatile String mailUser = "";
        volatile String mailPassword = "";

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
                if (!n.equals("runs") && !n.equals(".git") && !n.equals("notify.properties")) {
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
                case "/api/inbox" -> json(ex, Map.of("mails", inbox(ss)));
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
            case "liveReset" -> {
                deleteTree(ss.root.resolve("runs/live"));
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("command", "(cleared runs/live/)");
                r.put("exitCode", 0);
                r.put("output", "The live Claude runs of this workspace were cleared. The audit log keeps them.");
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
                if (password == null && !q.getOrDefault("run", "").startsWith("live/")) {
                    // local console: approval hands the code to the CD pipeline (git push -> GitHub Actions).
                    // A live Claude run is never released from here: it is a demonstration of the gates.
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
            case "live" -> {
                if (!liveKey()) {
                    throw new IllegalArgumentException("live mode is not set up on this server");
                }
                int done = liveChain(ss).size();
                if (done >= LIVE_MAX_CHAIN) {
                    throw new IllegalArgumentException("live chain full: press Start over");
                }
                String why = liveAllow(ss);
                if (why != null) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("error", why);
                    return r;
                }
                args.addAll(List.of("run", "--attempt", String.valueOf(done + 1), "--live", "--golden", "1.1",
                        "--into", "live"));
            }
            case "specblast" -> args.addAll(List.of("verify-specblast", SPECBLAST_PROJECT, "--code-only"));
            case "audit" -> args.add("audit");
            default -> throw new IllegalArgumentException("unknown action");
        }
        String mailTo = null;
        String mailNote = null;
        if (hosted && MAIL_ACTIONS.contains(name)) {
            if (!mailOn) {
                mailNote = "off";
            } else if (ss.email.isEmpty()) {
                mailNote = "no-address";
            } else {
                String why = mailLimit.allow(ss.email, client, 1, false);
                if (why == null) {
                    mailTo = ss.email;
                } else {
                    mailNote = why;
                }
            }
        }
        Map<String, Object> r = runCli(ss, args, mailTo, "live".equals(name));
        if (mailNote != null) {
            r.put("mail", mailNote);
        }
        return r;
    }

    private Map<String, Object> runCli(Session ss, List<String> args, String mailTo) throws IOException, InterruptedException {
        return runCli(ss, args, mailTo, false);
    }

    private boolean liveKey() {
        String k = System.getenv("ANTHROPIC_API_KEY");
        return k != null && !k.isBlank();
    }

    /** The live Claude runs of this workspace, oldest first (runs/live/run-*). */
    private static List<Path> liveChain(Session ss) throws IOException {
        Path d = ss.root.resolve("runs/live");
        if (!Files.isDirectory(d)) {
            return List.of();
        }
        try (Stream<Path> st = Files.list(d)) {
            return st.filter(p -> p.getFileName().toString().startsWith("run-")).sorted().toList();
        }
    }

    /** null = allowed (and counted); otherwise the reason. Only the hosted demo is limited. */
    private String liveAllow(Session ss) {
        if (!hosted) {
            return null;
        }
        long now = System.currentTimeMillis();
        synchronized (liveDay) {
            while (!liveDay.isEmpty() && now - liveDay.peekFirst() > 86_400_000L) {
                liveDay.pollFirst();
            }
            synchronized (ss.liveRuns) {
                while (!ss.liveRuns.isEmpty() && now - ss.liveRuns.peekFirst() > 3_600_000L) {
                    ss.liveRuns.pollFirst();
                }
                if (ss.liveRuns.size() >= LIVE_PER_SESSION_HOUR) {
                    return "live-hour";
                }
                if (liveDay.size() >= LIVE_PER_DAY) {
                    return "live-day";
                }
                ss.liveRuns.add(now);
                liveDay.add(now);
            }
        }
        return null;
    }

    private int liveLeft(Session ss) {
        if (!hosted) {
            return LIVE_PER_SESSION_HOUR;
        }
        long now = System.currentTimeMillis();
        synchronized (liveDay) {
            long day = liveDay.stream().filter(t -> now - t <= 86_400_000L).count();
            long mine;
            synchronized (ss.liveRuns) {
                mine = ss.liveRuns.stream().filter(t -> now - t <= 3_600_000L).count();
            }
            return (int) Math.max(0, Math.min(LIVE_PER_SESSION_HOUR - mine, LIVE_PER_DAY - day));
        }
    }

    private Map<String, Object> runCli(Session ss, List<String> args, String mailTo, boolean withAiKey)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        StringBuilder cp = new StringBuilder();
        for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            cp.append(cp.length() == 0 ? "" : java.io.File.pathSeparator).append(Path.of(e).toAbsolutePath());
        }
        cmd.add(cp.toString());
        cmd.add("-Daiverify.root=" + ss.root);
        cmd.add("dev.aiverify.Main");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(ss.root.toFile()).redirectErrorStream(true);
        if (hosted) {
            // the demo runs the seeded-defect attempts: no AI key, no GitHub token, and mail only to this visitor
            Map<String, String> env = pb.environment();
            if (!withAiKey) {
                env.remove("ANTHROPIC_API_KEY"); // only the live action gets the key
            }
            env.remove("GITHUB_TOKEN");
            env.remove("DEMO_PASSWORD");
            env.put("NOTIFY_TO", mailTo == null ? "" : mailTo);
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
        for (String a : args) {
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
        if (!run.matches("(live/)?run-[0-9]{8}-[0-9]{6}-a[0-9]+")) {
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

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
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
            // the hosted demo shows the real CD history (releases Rose approved locally), not only this session's
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
                        runs.add(m);
                    }
                }
            }
        }
        s.put("runs", runs);
        List<Object> live = new ArrayList<>();
        for (Path d : liveChain(ss)) {
            Path r = d.resolve("result.json");
            if (Files.exists(r)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(r));
                m.put("runPath", "live/" + d.getFileName());
                Path pc = d.resolve("production-check.json");
                if (Files.exists(pc)) {
                    m.put("productionCheck", Json.parse(Files.readString(pc)));
                }
                live.add(m);
            }
        }
        s.put("liveRuns", live);
        s.put("liveAvailable", liveKey());
        s.put("liveLeft", liveLeft(ss));
        s.put("liveMax", LIVE_MAX_CHAIN);

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
            try {
                m.put("body", new String(java.util.Base64.getMimeDecoder().decode(body.trim()), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n"));
            } catch (IllegalArgumentException e) {
                m.put("body", body);
            }
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
