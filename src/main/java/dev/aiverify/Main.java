package dev.aiverify;

import dev.aiverify.ai.ClaudeClient;
import dev.aiverify.ai.LlmClient;
import dev.aiverify.ai.ReplayClient;
import dev.aiverify.json.Json;
import dev.aiverify.notify.GateMail;
import dev.aiverify.notify.Notifier;
import dev.aiverify.pipeline.AuditLog;
import dev.aiverify.pipeline.Hashing;
import dev.aiverify.pipeline.Pipeline;
import dev.aiverify.pipeline.RunContext;
import dev.aiverify.report.ReportWriter;
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

/** Command line entry point. Run with no arguments for help. */
public final class Main {

    private static final Path ROOT = Path.of(System.getProperty("aiverify.root", ".")).toAbsolutePath().normalize();

    /** Name used when the demo itself plays the human, so a scripted decision is never mistaken for a real one. */
    private static final String SCRIPTED_HUMAN = "Scripted demo reviewer";

    public static void main(String[] args) throws Exception {
        String cmd = args.length == 0 ? "help" : args[0];
        Map<String, String> opts = options(args);
        try {
            dispatch(cmd, opts);
        } catch (IllegalArgumentException | IllegalStateException e) {
            fail("Error: " + e.getMessage());
        }
    }

    private static void dispatch(String cmd, Map<String, String> opts) throws Exception {
        switch (cmd) {
            case "demo" -> demo(opts.containsKey("full"));
            case "run" -> run(Integer.parseInt(opts.getOrDefault("attempt", "1")), opts.containsKey("live"),
                    opts.getOrDefault("golden", "latest"));
            case "approve" -> decide(opts, true);
            case "reject" -> decide(opts, false);
            case "verify-project" -> verifyProject(opts);
            case "production" -> production(opts);
            case "verify-specblast" -> verifySpecBlast(opts);
            case "audit" -> audit();
            case "report" -> System.out.println("Report: " + ROOT.relativize(ReportWriter.write(ROOT)));
            case "notify-test" -> new Notifier(ROOT).send(new Notifier.Message("[AI Gate] Test email",
                    "這是測試信。如果你收到了，閘門的 email 通知就設定好了。\nThis is a test. If you got it, gate email notifications work.\n"));
            case "selftest" -> System.exit(SelfTest.run() ? 0 : 1);
            case "console" -> dev.aiverify.ui.ConsoleServer.start(ROOT, Integer.parseInt(opts.getOrDefault("port", "8080")));
            default -> help();
        }
    }

    private static void help() {
        System.out.println("""
                AI Code Verification Gate

                  demo                  Offline demo: attempt 1 (blocked by tests), attempt 2 (stops at the human gate)
                  demo --full           Also plays the human: rejects attempt 2, runs attempt 3, approves it
                  run --attempt N [--live] [--golden 1.0|1.1|latest]
                                        One run. --live calls Claude (needs ANTHROPIC_API_KEY, optional AI_MODEL)
                  approve --by NAME [--run ID] [--note TEXT] [--ack-high]
                  reject  --by NAME [--run ID] --note TEXT
                                        The human gate for the latest (or given) run. A rejection note
                                        becomes spec-level feedback for the next attempt.
                  verify-project PATH [--label TEXT] [--build]
                                        Verify a whole generated project (e.g. SpecBlast output):
                                        Java policy, CodingStyle.md, Kubernetes, pipeline, Maven build.
                                        Exit code 1 when blocked, so a CI step can stop the deploy.
                  verify-specblast PATH
                                        A SpecBlast project generated from examples/specblast-iso8583/iso8583-precheck.txt:
                                        P1-P7 on the whole project, then G1-G6 on its AuthorizationRules class
                                        (golden tests held out from SpecBlast). Approve with --run specblast/RUN_ID.
                  production [--run ID] After approval: send real-looking transactions to the APPROVED code
                                        (latest approved run by default) and show what it actually does.
                                        Exit code 1 if any transaction covered by the spec is wrong.
                  notify-test           Send a test email using notify.properties (see notify.properties.example)
                  audit                 Verify the hash-chained audit log
                  report                Rebuild runs/report.html
                  selftest              Check the engine itself
                  console [--port N]    Open the web console (http://127.0.0.1:8080) for presenting the demo
                """);
    }

    private static void demo(boolean full) throws IOException {
        Path runs = ROOT.resolve("runs");
        if (Files.exists(runs)) {
            try (Stream<Path> s = Files.walk(runs)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
        run(1, false, "1.0");
        System.out.println();
        run(2, false, "1.0");
        System.out.println();
        if (!full) {
            System.out.println("Attempt 2 is waiting for a human. Read the AI review in runs/report.html, then either:");
            System.out.println("  ./run.sh reject --by \"Your Name\" --note \"...\"   and   ./run.sh run --attempt 3 --golden 1.1");
            System.out.println("  ./run.sh approve --by \"Your Name\" --ack-high");
            return;
        }
        Map<String, String> reject = new LinkedHashMap<>();
        reject.put("by", SCRIPTED_HUMAN);
        reject.put("note", "The AI reviewer's HIGH finding is real: a PAN containing a full-width Unicode digit is "
                + "approved. AC-01 and AC-02 mean ASCII digits 0-9 only. Regression tests G-32 and G-33 added to the "
                + "golden set (v1.1).");
        decide(reject, false);
        System.out.println();
        run(3, false, "1.1");
        System.out.println();
        Map<String, String> approve = new LinkedHashMap<>();
        approve.put("by", SCRIPTED_HUMAN);
        approve.put("note", "Accepted. All gates passed, including the NF-05 null guard the gate added.");
        decide(approve, true);
    }

    private static void run(int attempt, boolean live, String goldenVersion) throws IOException {
        LlmClient ai;
        if (live) {
            String model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-5-5");
            new dev.aiverify.ai.AiPolicy(ROOT).requireApprovedModel(model);
            ai = new ClaudeClient(System.getenv("ANTHROPIC_API_KEY"), model);
        } else {
            ai = new ReplayClient(ROOT.resolve("replay"), attempt);
        }
        String runId = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-a" + attempt;
        Path runDir = ROOT.resolve("runs").resolve(runId);
        Files.createDirectories(runDir);

        String feedback = null;
        if (attempt > 1) {
            Path prev = previousRun(runDir);
            if (prev != null && Files.exists(prev.resolve("feedback-for-author.txt"))) {
                feedback = Files.readString(prev.resolve("feedback-for-author.txt"));
            }
        }
        RunContext ctx = new RunContext(ROOT, runDir, runId, attempt, ai);
        ctx.goldenVersion = goldenVersion;
        Map<String, Object> result = new Pipeline().run(ctx, feedback);
        System.out.println("Report: " + ROOT.relativize(ReportWriter.write(ROOT)));
        new Notifier(ROOT).send(GateMail.runFinished(result));
    }

    private static void verifySpecBlast(Map<String, String> opts) throws IOException {
        String path = opts.get("path");
        if (path == null) {
            fail("Usage: verify-specblast PATH   (the SpecBlast project folder)");
        }
        Path project = Path.of(path).toAbsolutePath().normalize();
        System.out.println("== Step 1: the whole project (P1-P7)");
        Map<String, Object> pr = verifyAndSaveProject(project, "specblast-iso8583", false);
        System.out.println();
        System.out.println("== Step 2: SpecBlast's AuthorizationRules through gates G1-G6");
        Path rulesFile = dev.aiverify.ai.SpecBlastClient.findRules(project);
        System.out.println("  code under test: " + project.relativize(rulesFile));
        String candidate = dev.aiverify.ai.SpecBlastClient.wrap(Files.readString(rulesFile));
        Object commit = pr.get("gitCommit");
        String origin = project.getFileName() + (commit != null ? " @ " + commit : "");
        Path sourceNote = project.resolveSibling("SOURCE.txt");
        if (commit == null && Files.exists(sourceNote)) {
            origin = Files.readAllLines(sourceNote).get(0).strip();
        }
        LlmClient reviewer = null;
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key != null && !key.isBlank()) {
            String model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-5-5");
            new dev.aiverify.ai.AiPolicy(ROOT).requireApprovedModel(model);
            reviewer = new ClaudeClient(key, model);
        }
        LlmClient ai = new dev.aiverify.ai.SpecBlastClient(candidate, origin, new ReplayClient(ROOT.resolve("replay"), 1),
                reviewer);
        String runId = "run-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-sb";
        Path runDir = ROOT.resolve("runs/specblast").resolve(runId);
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("source.txt"), project.relativize(rulesFile) + "\n" + origin + "\n");
        RunContext ctx = new RunContext(ROOT, runDir, runId, 1, ai);
        ctx.goldenVersion = "1.1";
        Map<String, Object> result = new Pipeline().run(ctx, null);
        new Notifier(ROOT).send(GateMail.runFinished(result));
        System.out.println();
        System.out.println("Next: approve or reject with --run specblast/" + runId
                + ", then ./run.sh production --run specblast/" + runId);
    }

    @SuppressWarnings("unchecked")
    private static void production(Map<String, String> opts) throws IOException {
        Path runs = ROOT.resolve("runs");
        Path runDir = null;
        if (opts.containsKey("run")) {
            runDir = runs.resolve(opts.get("run"));
        } else if (Files.isDirectory(runs)) {
            try (Stream<Path> s = Files.list(runs)) {
                for (Path d : s.filter(p -> p.getFileName().toString().startsWith("run-"))
                        .sorted(Comparator.reverseOrder()).toList()) {
                    Path r = d.resolve("result.json");
                    if (Files.exists(r) && Pipeline.VERDICT_APPROVED.equals(
                            ((Map<String, Object>) Json.parse(Files.readString(r))).get("verdict"))) {
                        runDir = d;
                        break;
                    }
                }
            }
        }
        if (runDir == null || !Files.exists(runDir.resolve("result.json"))) {
            fail("No approved run yet. Only code a named human approved can go to production.");
        }
        Map<String, Object> r = dev.aiverify.pipeline.ProductionCheck.run(ROOT, runDir);
        System.out.print(dev.aiverify.pipeline.ProductionCheck.format(r));
        if (!"ALL_CORRECT".equals(r.get("verdict"))) {
            System.exit(1);
        }
    }

    private static Path previousRun(Path current) throws IOException {
        try (Stream<Path> s = Files.list(current.getParent())) {
            return s.filter(Files::isDirectory).filter(p -> !p.equals(current))
                    .filter(p -> p.getFileName().toString().startsWith("run-"))
                    .max(Comparator.naturalOrder()).orElse(null);
        }
    }

    /** G6: a named human approves or rejects. Both decisions go into the audit log. */
    @SuppressWarnings("unchecked")
    private static void decide(Map<String, String> opts, boolean approve) throws IOException {
        String by = opts.get("by");
        if (by == null || by.isBlank()) {
            fail("A named human must decide: --by \"Your Name\"");
        }
        String note = opts.getOrDefault("note", "");
        if (!approve && note.isBlank()) {
            fail("A rejection needs a reason: --note \"...\". It becomes spec-level feedback for the next attempt.");
        }
        Path runDir = opts.containsKey("run") ? ROOT.resolve("runs").resolve(opts.get("run"))
                : Pipeline.latestRun(ROOT.resolve("runs"));
        Path resultFile = runDir.resolve("result.json");
        Map<String, Object> result = Json.parseObject(Files.readString(resultFile));
        if (!Pipeline.VERDICT_READY.equals(result.get("verdict"))) {
            fail("Run " + result.get("runId") + " is not waiting for a human: verdict is " + result.get("verdict") + ".");
        }
        String currentCode = Files.readString(runDir.resolve("candidate/AuthorizationPrecheckImpl.java"));
        if (!Hashing.sha256(currentCode).equals(result.get("candidateSha256"))) {
            fail("The candidate code changed after verification. Run the pipeline again.");
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
            fail("The AI reviewer raised " + high + " HIGH finding(s). Read them in the report. To approve anyway, "
                    + "add --ack-high (your name is recorded as accepting the risk). Otherwise use reject.");
        }

        String at = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String verb = approve ? "Approved" : "Rejected";
        for (Map<String, Object> g : gates) {
            if ("G6".equals(g.get("id"))) {
                g.put("status", approve ? "PASSED" : "FAILED");
                g.put("summary", verb + " by " + by
                        + (approve && high > 0 ? " (accepted " + high + " HIGH AI finding(s))" : "")
                        + (note.isBlank() ? "." : ": " + note));
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("decision", approve ? "APPROVED" : "REJECTED");
                d.put("decidedBy", by);
                d.put("decidedAt", at);
                d.put("note", note);
                d.put("acceptedHighFindings", approve ? high : 0);
                g.put("details", d);
            }
        }
        result.put("verdict", approve ? Pipeline.VERDICT_APPROVED : Pipeline.VERDICT_REJECTED);
        Files.writeString(resultFile, Json.write(result));
        if (!approve) {
            Files.writeString(runDir.resolve("feedback-for-author.txt"), note);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", approve ? "HUMAN_APPROVED" : "HUMAN_REJECTED");
        event.put("at", at);
        event.put("runId", result.get("runId"));
        event.put("decidedBy", by);
        event.put("note", note);
        event.put("acceptedHighFindings", approve ? high : 0);
        event.put("candidateSha256", result.get("candidateSha256"));
        new AuditLog(ROOT.resolve("runs/audit-log.jsonl")).append(event);
        System.out.println(verb + " " + result.get("runId") + " by " + by + ". Recorded in runs/audit-log.jsonl.");
        System.out.println("Report: " + ROOT.relativize(ReportWriter.write(ROOT)));
        new Notifier(ROOT).send(GateMail.decision(String.valueOf(result.get("runId")), approve, by, note,
                approve ? high : 0));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> verifyAndSaveProject(Path path, String label, boolean build) throws IOException {
        Map<String, Object> r = dev.aiverify.project.ProjectVerifier.verify(ROOT, path, label, build);
        String slug = String.valueOf(r.get("label")).replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "").toLowerCase();
        Path dir = ROOT.resolve("runs/projects/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + slug);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("result.json"), Json.write(r));

        System.out.println("Project " + r.get("label") + (r.get("gitCommit") != null ? " @ " + r.get("gitCommit") : "")
                + "  (" + ((Map<String, Object>) r.get("filesChecked")).get("total") + " files)");
        for (Map<String, Object> g : (List<Map<String, Object>>) r.get("gates")) {
            System.out.printf("  %s %-26s %-8s %s error(s), %s warning(s)%n", g.get("id"), g.get("name"), g.get("status"),
                    g.get("errors"), g.get("warnings"));
            for (Map<String, Object> f : (List<Map<String, Object>>) g.get("findings")) {
                if (!"PASS".equals(f.get("severity"))) {
                    String where = f.get("file") == null ? "" : "  [" + f.get("file")
                            + (((Number) f.get("line")).intValue() > 0 ? ":" + f.get("line") : "") + "]";
                    System.out.printf("      %-5s %s %s%s%n", f.get("severity"), f.get("rule"), f.get("title"), where);
                }
            }
        }
        System.out.println("  verdict: " + r.get("verdict"));
        System.out.println("Report: " + ROOT.relativize(dev.aiverify.report.ProjectReportWriter.write(ROOT)));
        return r;
    }

    private static void verifyProject(Map<String, String> opts) throws IOException {
        String path = opts.get("path");
        if (path == null) {
            fail("Usage: verify-project PATH [--label TEXT] [--build]");
        }
        Map<String, Object> r = verifyAndSaveProject(Path.of(path), opts.get("label"), opts.containsKey("build"));
        if ("BLOCKED".equals(r.get("verdict"))) {
            System.exit(1);
        }
    }

    private static void audit() {
        AuditLog log = new AuditLog(ROOT.resolve("runs/audit-log.jsonl"));
        String problem = log.verify();
        if (problem == null) {
            System.out.println("Audit log intact: " + log.size() + " entries, hash chain verified.");
        } else {
            fail("Audit log TAMPERED. " + problem);
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        if (args.length > 1 && !args[1].startsWith("--")) {
            m.put("path", args[1]);
        }
        for (int k = 1; k < args.length; k++) {
            if (args[k].startsWith("--")) {
                String key = args[k].substring(2);
                boolean hasValue = k + 1 < args.length && !args[k + 1].startsWith("--");
                m.put(key, hasValue ? args[++k] : "true");
            }
        }
        return m;
    }

    private static void fail(String msg) {
        System.err.println(msg);
        System.exit(2);
    }
}
