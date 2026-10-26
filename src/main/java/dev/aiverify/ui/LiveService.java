package dev.aiverify.ui;

import dev.aiverify.ai.Prompts;
import dev.aiverify.ai.SpecBlastClient;
import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.AuthRequest;
import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.pipeline.NullGuardFixer;
import dev.aiverify.pipeline.RunContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The card authorization pre-check as a "live service": the same code deployed two ways.
 *
 * <ul>
 *   <li>{@code *-nogate}: deployed straight from the AI, skipping the gate.</li>
 *   <li>{@code *-gate}: deployed after the gate, with the NF-05 fix the gate added.</li>
 * </ul>
 * It shows what a customer would see in production: an answer code, or a crash.
 */
final class LiveService {

    private static final Set<String> NULL_TYPES = Set.of("LocalDate", "AuthRequest");
    private static final Map<String, String> MEANING = Map.of(
            "00", "Approved", "13", "Invalid amount", "14", "Invalid card number",
            "30", "Format error", "51", "Insufficient funds", "54", "Expired card");

    private final Path root;
    private final Map<String, Object> versions = new LinkedHashMap<>();

    private final Path releaseSource;

    LiveService(Path root) {
        this(root, null);
    }

    /** For the released service: version "release" is the approved code in {@code releaseSource}. */
    LiveService(Path root, Path releaseSource) {
        this.root = root;
        this.releaseSource = releaseSource;
    }

    /** Version id → description, for the page. */
    static List<Map<String, String>> catalog() {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(Map.of("id", "claude-nogate", "label", "Claude, attempt 3 · deployed without the gate"));
        out.add(Map.of("id", "claude-gate", "label", "Claude, attempt 3 · deployed after the gate"));
        out.add(Map.of("id", "specblast-nogate", "label", "SpecBlast · deployed without the gate"));
        out.add(Map.of("id", "specblast-gate", "label", "SpecBlast · deployed after the gate"));
        return out;
    }

    private synchronized AuthorizationPrecheck load(String id) throws Exception {
        Object cached = versions.get(id);
        if (cached instanceof AuthorizationPrecheck p) {
            return p;
        }
        String raw;
        if ("release".equals(id) && releaseSource != null) {
            String src = Files.readString(releaseSource);
            InMemoryCompiler.Result rr = new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, src);
            if (!rr.ok()) {
                throw new IllegalStateException("Does not compile: " + rr.errors());
            }
            AuthorizationPrecheck rp = (AuthorizationPrecheck) rr.loadedClass().getDeclaredConstructor().newInstance();
            versions.put(id, rp);
            return rp;
        }
        if (id.startsWith("claude")) {
            raw = Prompts.extractJava(Files.readString(root.resolve("replay/attempt-3/author.txt")));
        } else if (id.startsWith("specblast")) {
            Path project = root.resolve("examples/specblast-iso8583/project");
            raw = SpecBlastClient.wrap(Files.readString(SpecBlastClient.findRules(project)));
        } else {
            throw new IllegalArgumentException("unknown version");
        }
        String source = raw;
        if (id.endsWith("-gate")) {
            String fixed = NullGuardFixer.fix(raw, NULL_TYPES);
            source = fixed == null ? raw : fixed;
        } else if (!id.endsWith("-nogate")) {
            throw new IllegalArgumentException("unknown version");
        }
        InMemoryCompiler.Result r = new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, source);
        if (!r.ok()) {
            throw new IllegalStateException("Does not compile: " + r.errors());
        }
        AuthorizationPrecheck p = (AuthorizationPrecheck) r.loadedClass().getDeclaredConstructor().newInstance();
        versions.put(id, p);
        return p;
    }

    /** One authorization request. Returns the HTTP status and the JSON body. */
    Object[] authorize(Map<String, String> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = q.getOrDefault("version", "");
        out.put("version", id);
        AuthorizationPrecheck code;
        try {
            code = load(id);
        } catch (Exception e) {
            out.put("error", e.getMessage());
            return new Object[] {400, out};
        }
        AuthRequest req = new AuthRequest(blankToNull(q.get("pan")), blankToNull(q.get("expiry")),
                parseLong(q.get("amount")), parseLong(q.get("available")));
        String d = blankToNull(q.get("date"));
        LocalDate date;
        try {
            date = d == null ? null : LocalDate.parse(d);
        } catch (RuntimeException e) {
            out.put("error", "Date must be yyyy-MM-dd");
            return new Object[] {400, out};
        }
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "live-service");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<String> f = ex.submit(() -> code.check(req, date));
            String rc = f.get(1000, TimeUnit.MILLISECONDS);
            out.put("responseCode", rc);
            out.put("meaning", MEANING.getOrDefault(rc, "Unknown code"));
            return new Object[] {200, out};
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            out.put("exception", c.getClass().getName());
            List<String> trace = new ArrayList<>();
            int line = 0;
            for (StackTraceElement el : c.getStackTrace()) {
                if (el.getClassName().startsWith("dev.aiverify.candidate")) {
                    trace.add("at " + el.getClassName() + "." + el.getMethodName() + "(" + el.getFileName() + ":"
                            + el.getLineNumber() + ")");
                    if (line == 0) {
                        line = el.getLineNumber();
                    }
                }
            }
            out.put("line", line);
            out.put("trace", trace);
            return new Object[] {500, out};
        } catch (Exception e) {
            out.put("exception", e.getClass().getSimpleName());
            return new Object[] {500, out};
        } finally {
            ex.shutdownNow();
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long parseLong(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
