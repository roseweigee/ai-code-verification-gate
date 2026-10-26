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
    private final LiveService live;

    private ConsoleServer(Path root, String password) {
        this.root = root;
        this.password = password == null ? null : password.getBytes(StandardCharsets.UTF_8);
        this.live = new LiveService(root);
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
        HttpServer server = HttpServer.create(new InetSocketAddress(hosted ? "0.0.0.0" : "127.0.0.1", port), 0);
        server.createContext("/", app::handle);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        if (hosted) {
            System.out.println("Console listening on port " + port + " (password required).");
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
            switch (path) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8",
                        Files.readString(root.resolve("report/console.html")));
                case "/api/state" -> json(ex, state());
                case "/api/action" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    json(ex, action(q));
                }
                case "/api/snippet" -> json(ex, snippet(q));
                case "/api/tamper-test" -> json(ex, tamperTest());
                case "/pay" -> send(ex, 200, "text/html; charset=utf-8", Files.readString(root.resolve("report/live.html")));
                case "/api/pay" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        json(ex, 405, Map.of("error", "POST only"));
                        return;
                    }
                    Object[] r = live.authorize(q);
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

    private Map<String, Object> action(Map<String, String> q) throws IOException, InterruptedException {
        String name = q.getOrDefault("name", "");
        String by = q.getOrDefault("by", "").trim();
        String note = q.getOrDefault("note", "").trim();
        List<String> args = new ArrayList<>();
        switch (name) {
            case "reset" -> {
                deleteRuns();
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
            }
            case "project" -> {
                String which = q.getOrDefault("which", "");
                if ("iso".equals(which)) {
                    args.addAll(List.of("verify-project", SPECBLAST_PROJECT, "--label", "specblast-iso8583"));
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
            case "audit" -> args.add("audit");
            default -> throw new IllegalArgumentException("unknown action");
        }
        return runCli(args);
    }

    private Map<String, Object> runCli(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("-Daiverify.root=" + root);
        cmd.add("dev.aiverify.Main");
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
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

    private void deleteRuns() throws IOException {
        Path runs = root.resolve("runs");
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

    private Map<String, Object> state() throws IOException {
        Map<String, Object> s = new LinkedHashMap<>();
        List<Object> runs = new ArrayList<>();
        Path runsDir = root.resolve("runs");
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
    private Map<String, Object> tamperTest() throws IOException {
        Path log = root.resolve("runs/audit-log.jsonl");
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
