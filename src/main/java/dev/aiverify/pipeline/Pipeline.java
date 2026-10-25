package dev.aiverify.pipeline;

import dev.aiverify.ai.Prompts;
import dev.aiverify.gates.AiReviewGate;
import dev.aiverify.gates.CompileGate;
import dev.aiverify.gates.MutationGate;
import dev.aiverify.gates.PolicyGate;
import dev.aiverify.gates.SpecTestGate;
import dev.aiverify.json.Json;
import dev.aiverify.testing.TestCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The verification pipeline:
 *
 *   AI author writes code  ─┐
 *   AI test writer (spec)  ─┤→ G1 policy → G2 compile → G3 spec tests → G4 mutation → G5 AI review → G6 human
 *   Human golden tests     ─┘
 *
 * Deterministic gates decide. AI gates advise. A human approves.
 */
public final class Pipeline {

    public static final String VERDICT_BLOCKED = "BLOCKED";
    public static final String VERDICT_READY = "READY_FOR_HUMAN_APPROVAL";
    public static final String VERDICT_APPROVED = "APPROVED";
    public static final String VERDICT_REJECTED = "REJECTED_BY_HUMAN";

    private final List<Gate> gates = List.of(
            new PolicyGate(), new CompileGate(), new SpecTestGate(), new MutationGate(), new AiReviewGate());

    public Map<String, Object> run(RunContext ctx, String feedbackForAuthor) {
        OffsetDateTime started = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        log("Run " + ctx.runId + "  (" + ctx.ai.describe() + ", golden tests "
                + ("latest".equals(ctx.goldenVersion) ? "latest" : "v" + ctx.goldenVersion) + ")");
        loadInputs(ctx);

        // ---- AI generation: three roles, three separate contexts
        log("  author        writes the implementation (sees spec + contract, never the tests)");
        Prompts.Prompt ap = Prompts.author(ctx.spec, ctx.contractSource, feedbackForAuthor);
        ctx.candidateSource = Prompts.extractJava(ctx.askAi("author", ap.system(), ap.user()));
        ctx.write("candidate/AuthorizationPrecheckImpl.java", ctx.candidateSource);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("file", "AuthorizationPrecheckImpl.java");
        provenance.put("origin", "AI-generated");
        provenance.put("generatedBy", ctx.ai.describe());
        provenance.put("runId", ctx.runId);
        provenance.put("sha256", Hashing.sha256(ctx.candidateSource));
        provenance.put("promptAndAnswer", "ai/author.prompt.txt, ai/author.answer.txt");
        provenance.put("status", "Not approved. May be used only after gates G1-G5 and a named human approval (G6).");
        ctx.write("candidate/PROVENANCE.json", Json.write(provenance));

        log("  test-writer   writes tests from the spec only (never sees the code)");
        Prompts.Prompt tp = Prompts.testWriter(ctx.spec);
        ctx.aiTests = TestCase.fromJson(Json.parseEmbedded(ctx.askAi("test-writer", tp.system(), tp.user())), "ai");
        List<Map<String, Object>> aiTestMaps = new ArrayList<>();
        ctx.aiTests.forEach(t -> aiTestMaps.add(t.toMap()));
        ctx.write("ai-tests.json", Json.write(Map.of("cases", aiTestMaps)));

        // ---- Gates
        List<GateResult> results = new ArrayList<>();
        boolean blocked = false;
        GateResult failedTests = null;
        for (Gate g : gates) {
            if (blocked) {
                results.add(GateResult.skipped(g.id(), g.name(), g.decidedBy(), "Skipped: an earlier gate failed."));
                continue;
            }
            GateResult r = g.run(ctx);
            results.add(r);
            log(String.format("  %s %-22s %-8s %s", r.id(), r.name(), r.status(), r.summary()));
            if (r.status() == GateResult.Status.FAILED) {
                blocked = true;
                if (g instanceof SpecTestGate) {
                    failedTests = r;
                }
            }
        }

        // ---- On test failure, a separate AI diagnoses the root cause for the human
        if (failedTests != null) {
            log("  diagnostician explains the failure (feedback to the author stays at spec level)");
            Prompts.Prompt dp = Prompts.diagnostician(ctx.spec, ctx.candidateSource,
                    SpecTestGate.describeFailures(failedTests));
            String answer = ctx.askAi("diagnostician", dp.system(), dp.user());
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> d = (Map<String, Object>) Json.parseEmbedded(answer);
                ctx.diagnosis = d;
                Object fb = d.get("feedbackForAuthor");
                if (fb != null) {
                    ctx.write("feedback-for-author.txt", fb.toString());
                }
            } catch (RuntimeException e) {
                ctx.diagnosis = Map.of("rootCause", "Diagnosis could not be read: " + e.getMessage());
            }
        }

        String verdict = blocked ? VERDICT_BLOCKED : VERDICT_READY;
        results.add(new GateResult("G6", "Human approval",
                blocked ? GateResult.Status.SKIPPED : GateResult.Status.PENDING, "human",
                blocked ? "Not requested: the run was blocked." : "Waiting for a named human to approve.",
                new LinkedHashMap<>(), 0));
        log("  verdict: " + verdict);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", ctx.runId);
        result.put("attempt", ctx.attempt);
        result.put("startedAt", started.toString());
        result.put("mode", ctx.ai.describe());
        result.put("goldenVersion", ctx.goldenVersion);
        result.put("feedbackReceived", feedbackForAuthor);
        result.put("verdict", verdict);
        result.put("candidateSource", ctx.candidateSource);
        result.put("candidateSha256", Hashing.sha256(ctx.candidateSource));
        result.put("inputs", inputHashes(ctx));
        result.put("aiExchanges", ctx.aiExchanges);
        result.put("responsibleAi", responsibleAi(ctx));
        List<Map<String, Object>> gateMaps = new ArrayList<>();
        results.forEach(r -> gateMaps.add(r.toMap()));
        result.put("gates", gateMaps);
        if (ctx.diagnosis != null) {
            result.put("diagnosis", ctx.diagnosis);
        }
        ctx.write("result.json", Json.write(result));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "RUN_COMPLETED");
        event.put("at", started.toString());
        event.put("runId", ctx.runId);
        event.put("mode", ctx.ai.describe());
        event.put("verdict", verdict);
        event.put("candidateSha256", Hashing.sha256(ctx.candidateSource));
        event.put("specSha256", Hashing.sha256(ctx.spec));
        event.put("gates", gateSummary(results));
        new AuditLog(ctx.projectRoot.resolve("runs/audit-log.jsonl")).append(event);
        return result;
    }

    private static void loadInputs(RunContext ctx) {
        try {
            ctx.spec = Files.readString(ctx.projectRoot.resolve("spec/authorization-precheck.md"));
            ctx.contractSource = Files.readString(ctx.projectRoot.resolve(
                    "src/main/java/dev/aiverify/contract/AuthorizationPrecheck.java"))
                    + "\n" + Files.readString(ctx.projectRoot.resolve(
                    "src/main/java/dev/aiverify/contract/AuthRequest.java"));
            ctx.goldenTests = TestCase.fromJson(Json.parse(Files.readString(
                    ctx.projectRoot.resolve("tests/golden/acceptance-cases.json"))), "golden")
                    .stream().filter(t -> t.includedIn(ctx.goldenVersion)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read pipeline inputs: " + e.getMessage(), e);
        }
    }

    /** Evidence for the Responsible AI principles, from what this run actually did. Principle 4 is G6 itself. */
    private static Map<String, Object> responsibleAi(RunContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        dev.aiverify.ai.AiPolicy policy = new dev.aiverify.ai.AiPolicy(ctx.projectRoot);
        String mode = ctx.ai.describe();
        m.put("approvedTools", mode.startsWith("live")
                ? "Model " + mode.substring(6) + " is on the approved list " + policy.approvedModels()
                        + " (" + policy.provider() + ")."
                : "Offline replay: no model was called. Live mode only calls models on the approved list "
                        + policy.approvedModels() + ".");
        m.put("noSensitiveData", ctx.aiExchanges.size() + " of " + ctx.aiExchanges.size()
                + " prompts were scanned before sending: no card numbers, secrets, emails or national IDs. "
                + "Card numbers are masked in every report and in the diagnostician's input.");
        m.put("disclosureAndReview", "The code is marked AI-generated (candidate/PROVENANCE.json). Every prompt and "
                + "answer is saved and hashed. Correctness is checked by deterministic gates G1-G4; "
                + "an independent AI review (G5) is shown to the human.");
        return m;
    }

    private static Map<String, Object> inputHashes(RunContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spec", Hashing.sha256(ctx.spec));
        m.put("contract", Hashing.sha256(ctx.contractSource));
        List<Map<String, Object>> golden = new ArrayList<>();
        ctx.goldenTests.forEach(t -> golden.add(t.toMap()));
        m.put("goldenTests", Hashing.sha256(Json.writeCompact(golden)));
        m.put("goldenVersion", ctx.goldenVersion);
        m.put("goldenCount", ctx.goldenTests.size());
        return m;
    }

    private static Map<String, Object> gateSummary(List<GateResult> results) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (GateResult r : results) {
            m.put(r.id(), r.status().name());
        }
        return m;
    }

    private static void log(String s) {
        System.out.println(s);
    }

    public static Path latestRun(Path runsDir) throws IOException {
        try (var s = Files.list(runsDir)) {
            return s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith("run-"))
                    .max(Path::compareTo).orElseThrow(() -> new IllegalStateException("No runs found"));
        }
    }
}
