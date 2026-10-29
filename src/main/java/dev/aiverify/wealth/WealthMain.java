package dev.aiverify.wealth;

import dev.aiverify.ai.ClaudeClient;
import dev.aiverify.ai.LlmClient;
import dev.aiverify.ai.ReplayClient;
import dev.aiverify.json.Json;
import dev.aiverify.pipeline.AuditLog;
import dev.aiverify.pipeline.Hashing;
import dev.aiverify.pipeline.RunContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code ./run.sh wealth <command>}: the wealth-management scenario (fund subscription suitability pre-check).
 *
 * <pre>
 *   wealth demo [--full]                          attempts 1-3 with the scripted human decisions
 *   wealth run --attempt N [--golden V] [--live]  one run
 *   wealth approve|reject --by NAME [--note ..] [--ack-high]
 *   wealth report                                 runs-wealth/report.html
 *   wealth audit                                  verify the hash chain
 * </pre>
 */
public final class WealthMain {

    static final String SCRIPTED_HUMAN = "Scripted demo reviewer";

    private WealthMain() {
    }

    public static void dispatch(Path root, String sub, Map<String, String> opts) throws IOException {
        Path runs = root.resolve("runs-wealth");
        switch (sub) {
            case "demo" -> demo(root, runs, opts.containsKey("full"));
            case "run" -> run(root, runs, Integer.parseInt(opts.getOrDefault("attempt", "1")),
                    opts.containsKey("live"), opts.getOrDefault("golden", "latest"));
            case "approve" -> decide(root, runs, opts, true);
            case "reject" -> decide(root, runs, opts, false);
            case "report" -> System.out.println("Report: " + root.relativize(WealthReport.write(root, runs)));
            case "audit" -> audit(runs);
            case "release" -> {
                if (!opts.containsKey("run")) {
                    throw new IllegalArgumentException("Usage: wealth release --run RUN_ID");
                }
                WealthRelease.release(root, runs, opts.get("run"));
            }
            case "release-check" -> WealthRelease.printCheck(root);
            default -> System.out.println("""
                    Wealth scenario: fund subscription suitability pre-check
                      ./run.sh wealth demo [--full]
                      ./run.sh wealth run --attempt N [--golden 1.0|1.1|latest] [--live]
                      ./run.sh wealth approve --by "Name" [--note "..."] [--ack-high]
                      ./run.sh wealth reject  --by "Name" --note "..."
                      ./run.sh wealth report | audit
                      ./run.sh wealth release --run RUN_ID | release-check""");
        }
    }

    private static void demo(Path root, Path runs, boolean full) throws IOException {
        if (Files.exists(runs)) {
            try (Stream<Path> s = Files.walk(runs)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        run(root, runs, 1, false, "1.0");
        System.out.println();
        run(root, runs, 2, false, "1.0");
        System.out.println();
        if (!full) {
            System.out.println("Attempt 2 is waiting for a human. Read the AI review in runs-wealth/report.html.");
            return;
        }
        Map<String, String> reject = new LinkedHashMap<>();
        reject.put("by", SCRIPTED_HUMAN);
        reject.put("note", "The AI reviewer's HIGH finding is real: Integer.parseInt accepts risk levels such as C03 "
                + "or RR+2, so malformed KYC data passes AC-01. AC-01 allows only the exact codes C1-C5 and RR1-RR5. "
                + "Regression tests G-32 and G-33 added to the golden set (v1.1).");
        decide(root, runs, reject, false);
        System.out.println();
        run(root, runs, 3, false, "1.1");
        System.out.println();
        Map<String, String> approve = new LinkedHashMap<>();
        approve.put("by", SCRIPTED_HUMAN);
        approve.put("note", "Accepted. All gates passed, including the NF-05 null guard the gate added. "
                + "The LOW overflow note is outside any real portfolio size.");
        decide(root, runs, approve, true);
    }

    static void run(Path root, Path runs, int attempt, boolean live, String goldenVersion) throws IOException {
        LlmClient ai;
        if (live) {
            String model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-5-5");
            new dev.aiverify.ai.AiPolicy(root).requireApprovedModel(model);
            ai = new ClaudeClient(System.getenv("ANTHROPIC_API_KEY"), model);
        } else {
            ai = new ReplayClient(root.resolve("replay-wealth"), attempt);
        }
        String runId = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-a" + attempt;
        Path runDir = runs.resolve(runId);
        Files.createDirectories(runDir);
        String feedback = null;
        if (attempt > 1) {
            Path prev = previousRun(runDir);
            if (prev != null && Files.exists(prev.resolve("feedback-for-author.txt"))) {
                feedback = Files.readString(prev.resolve("feedback-for-author.txt"));
            }
        }
        RunContext ctx = new RunContext(root, runDir, runId, attempt, ai);
        ctx.goldenVersion = goldenVersion;
        new WealthPipeline().run(ctx, feedback);
        System.out.println("Report: " + root.relativize(WealthReport.write(root, runs)));
    }

    private static Path previousRun(Path current) throws IOException {
        try (Stream<Path> s = Files.list(current.getParent())) {
            return s.filter(Files::isDirectory).filter(p -> !p.equals(current))
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .max(Comparator.naturalOrder()).orElse(null);
        }
    }

    /** G6: a named human approves or rejects. Same rules as the card-payment scenario. */
    @SuppressWarnings("unchecked")
    static void decide(Path root, Path runs, Map<String, String> opts, boolean approve) throws IOException {
        String by = opts.get("by");
        if (by == null || by.isBlank()) {
            throw new IllegalArgumentException("A named human must decide: --by \"Your Name\"");
        }
        String note = opts.getOrDefault("note", "");
        if (!approve && note.isBlank()) {
            throw new IllegalArgumentException("A rejection needs a reason: --note \"...\".");
        }
        Path runDir;
        if (opts.containsKey("run")) {
            runDir = runs.resolve(opts.get("run"));
        } else {
            try (Stream<Path> s = Files.list(runs)) {
                runDir = s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith("run-"))
                        .max(Path::compareTo).orElseThrow(() -> new IllegalStateException("No runs found"));
            }
        }
        Path resultFile = runDir.resolve("result.json");
        Map<String, Object> result = Json.parseObject(Files.readString(resultFile));
        if (!WealthPipeline.VERDICT_READY.equals(result.get("verdict"))) {
            throw new IllegalStateException("Run " + result.get("runId") + " is not waiting for a human: verdict is "
                    + result.get("verdict") + ".");
        }
        String currentCode = Files.readString(runDir.resolve(WealthPipeline.CANDIDATE_FILE));
        if (!Hashing.sha256(currentCode).equals(result.get("candidateSha256"))) {
            throw new IllegalStateException("The candidate code changed after verification. Run the pipeline again.");
        }
        List<Map<String, Object>> gates = (List<Map<String, Object>>) result.get("gates");
        long high = 0;
        for (Map<String, Object> g : gates) {
            if ("G5".equals(g.get("id"))) {
                Object h = ((Map<String, Object>) g.get("details")).get("highFindings");
                high = h instanceof Number n ? n.longValue() : 0;
            }
        }
        if (approve && high > 0 && !opts.containsKey("ack-high")) {
            throw new IllegalStateException("The AI reviewer raised " + high + " HIGH finding(s). To approve anyway, "
                    + "add --ack-high (your name is recorded as accepting the risk). Otherwise use reject.");
        }
        String at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String verb = approve ? "Approved" : "Rejected";
        for (Map<String, Object> g : gates) {
            if ("G6".equals(g.get("id"))) {
                g.put("status", approve ? "PASSED" : "FAILED");
                g.put("summary", verb + " by " + by + (note.isBlank() ? "." : ": " + note));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("decision", approve ? "APPROVED" : "REJECTED");
                d.put("decidedBy", by);
                d.put("decidedAt", at);
                d.put("note", note);
                d.put("acceptedHighFindings", approve ? high : 0);
                g.put("details", d);
            }
        }
        result.put("verdict", approve ? WealthPipeline.VERDICT_APPROVED : WealthPipeline.VERDICT_REJECTED);
        Files.writeString(resultFile, Json.write(result));
        if (!approve) {
            Files.writeString(runDir.resolve("feedback-for-author.txt"), note);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", approve ? "HUMAN_APPROVED" : "HUMAN_REJECTED");
        event.put("at", at);
        event.put("scenario", "wealth");
        event.put("runId", result.get("runId"));
        event.put("decidedBy", by);
        event.put("note", note);
        event.put("acceptedHighFindings", approve ? high : 0);
        event.put("candidateSha256", result.get("candidateSha256"));
        new AuditLog(runs.resolve("audit-log.jsonl")).append(event);
        System.out.println(verb + " " + result.get("runId") + " by " + by + ". Recorded in runs-wealth/audit-log.jsonl.");
        System.out.println("Report: " + root.relativize(WealthReport.write(root, runs)));
    }

    private static void audit(Path runs) {
        AuditLog log = new AuditLog(runs.resolve("audit-log.jsonl"));
        String problem = log.verify();
        if (problem != null) {
            throw new IllegalStateException("Audit log TAMPERED. " + problem);
        }
        System.out.println("Audit log intact: " + log.size() + " entries, hash chain verified.");
    }
}
