package dev.aiverify.wealth;

import dev.aiverify.ai.Prompts;
import dev.aiverify.gates.AiReviewGate;
import dev.aiverify.json.Json;
import dev.aiverify.pipeline.AuditLog;
import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.Hashing;
import dev.aiverify.pipeline.NullGuardFixer;
import dev.aiverify.pipeline.RunContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The same verification flow as {@link dev.aiverify.pipeline.Pipeline}, for the wealth-management scenario
 * (fund subscription suitability pre-check):
 *
 *   AI author ─┐
 *   AI tests  ─┤→ G1 policy → G2 compile → G3 spec tests → G4 mutation → G5 AI review → G6 human
 *   Golden    ─┘
 *
 * Only the spec, the contract and the tests are different. Runs and the audit log live in runs-wealth/.
 */
public final class WealthPipeline {

    public static final String VERDICT_BLOCKED = "BLOCKED";
    public static final String VERDICT_READY = "READY_FOR_HUMAN_APPROVAL";
    public static final String VERDICT_APPROVED = "APPROVED";
    public static final String VERDICT_REJECTED = "REJECTED_BY_HUMAN";
    public static final String CANDIDATE_FILE = "candidate/SubscriptionPrecheckImpl.java";
    static final String MARK = "// NF-05 auto-fix by the gate: a null input is a format error (FMT), never a crash";

    private final WealthGates.State state = new WealthGates.State();
    private final List<Gate> gates = List.of(new WealthGates.Policy(), new WealthGates.Compile(state),
            new WealthGates.SpecTests(state), new WealthGates.Mutation(state), new AiReviewGate());

    public Map<String, Object> run(RunContext ctx, String feedbackForAuthor) {
        OffsetDateTime started = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        log("Wealth run " + ctx.runId + "  (" + ctx.ai.describe() + ", golden tests "
                + ("latest".equals(ctx.goldenVersion) ? "latest" : "v" + ctx.goldenVersion) + ")");
        loadInputs(ctx);

        log("  author        writes the implementation (sees spec + contract, never the tests)");
        Prompts.Prompt ap = WealthPrompts.author(ctx.spec, ctx.contractSource, feedbackForAuthor);
        ctx.candidateSource = Prompts.extractJava(ctx.askAi("author", ap.system(), ap.user()));
        ctx.write(CANDIDATE_FILE, ctx.candidateSource);
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("file", "SubscriptionPrecheckImpl.java");
        provenance.put("origin", "AI-generated");
        provenance.put("generatedBy", ctx.ai.describe());
        provenance.put("runId", ctx.runId);
        provenance.put("sha256", Hashing.sha256(ctx.candidateSource));
        provenance.put("status", "Not approved. May be used only after gates G1-G5 and a named human approval (G6).");
        ctx.write("candidate/PROVENANCE.json", Json.write(provenance));

        log("  test-writer   writes tests from the spec only (never sees the code)");
        Prompts.Prompt tp = WealthPrompts.testWriter(ctx.spec);
        state.aiTests = WealthCase.fromJson(Json.parseEmbedded(ctx.askAi("test-writer", tp.system(), tp.user())), "ai");
        List<Map<String, Object>> aiTestMaps = new ArrayList<>();
        state.aiTests.forEach(t -> aiTestMaps.add(t.toMap()));
        ctx.write("ai-tests.json", Json.write(Map.of("cases", aiTestMaps)));

        Pass pass = runGates(ctx);

        // NF-05: when the only failures are the null-input probes, a fixed rule adds a null guard; all gates re-run
        if (pass.failedTests != null && Boolean.TRUE.equals(pass.failedTests.details().get("baselineOnly"))) {
            String before = ctx.candidateSource;
            Set<String> types = new HashSet<>();
            for (Object o : (List<?>) pass.failedTests.details().get("failures")) {
                Object id = ((Map<?, ?>) o).get("id");
                if ("R-01".equals(id)) {
                    types.add("LocalDate");
                } else if ("R-02".equals(id)) {
                    types.add("SubscriptionRequest");
                }
            }
            String fixed = NullGuardFixer.fix(before, types, WealthGates.FORMAT_ERROR, MARK);
            if (fixed != null) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rule", "NF-05");
                fix.put("found", pass.failedTests.details().get("failures"));
                fix.put("added", NullGuardFixer.addedLines(before, fixed));
                fix.put("beforeSha256", Hashing.sha256(before));
                fix.put("afterSha256", Hashing.sha256(fixed));
                fix.put("firstPass", pass.summaries());
                ctx.autoFix = fix;
                ctx.write("candidate/before-auto-fix.java", before);
                ctx.candidateSource = fixed;
                ctx.write(CANDIDATE_FILE, fixed);
                provenance.put("autoFix", "NF-05 null guard added by the gate (deterministic rule, not AI)");
                provenance.put("sha256", Hashing.sha256(fixed));
                ctx.write("candidate/PROVENANCE.json", Json.write(provenance));
                log("  auto-fix      NF-05: a null input crashed the code; the gate added a null guard. All gates run again:");
                pass = runGates(ctx);
            }
        }

        if (pass.failedTests != null) {
            log("  diagnostician explains the failure (feedback to the author stays at spec level)");
            Prompts.Prompt dp = Prompts.diagnostician(ctx.spec, ctx.candidateSource,
                    WealthGates.SpecTests.describeFailures(pass.failedTests));
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

        List<GateResult> results = pass.results;
        String verdict = pass.blocked ? VERDICT_BLOCKED : VERDICT_READY;
        results.add(new GateResult("G6", "Human approval",
                pass.blocked ? GateResult.Status.SKIPPED : GateResult.Status.PENDING, "human",
                pass.blocked ? "Not requested: the run was blocked." : "Waiting for a named human to approve.",
                new LinkedHashMap<>(), 0));
        log("  verdict: " + verdict);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scenario", "wealth: fund subscription suitability pre-check");
        result.put("runId", ctx.runId);
        result.put("attempt", ctx.attempt);
        result.put("startedAt", started.toString());
        result.put("mode", ctx.ai.describe());
        result.put("goldenVersion", ctx.goldenVersion);
        result.put("feedbackReceived", feedbackForAuthor);
        result.put("verdict", verdict);
        result.put("candidateSource", ctx.candidateSource);
        result.put("candidateSha256", Hashing.sha256(ctx.candidateSource));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("spec", Hashing.sha256(ctx.spec));
        inputs.put("contract", Hashing.sha256(ctx.contractSource));
        inputs.put("goldenVersion", ctx.goldenVersion);
        inputs.put("goldenCount", state.goldenTests.size());
        result.put("inputs", inputs);
        result.put("aiExchanges", ctx.aiExchanges);
        List<Map<String, Object>> gateMaps = new ArrayList<>();
        results.forEach(r -> gateMaps.add(r.toMap()));
        result.put("gates", gateMaps);
        if (ctx.diagnosis != null) {
            result.put("diagnosis", ctx.diagnosis);
        }
        if (ctx.autoFix != null) {
            result.put("autoFix", ctx.autoFix);
        }
        ctx.write("result.json", Json.write(result));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event", "RUN_COMPLETED");
        event.put("at", started.toString());
        event.put("scenario", "wealth");
        event.put("runId", ctx.runId);
        event.put("mode", ctx.ai.describe());
        event.put("verdict", verdict);
        event.put("candidateSha256", Hashing.sha256(ctx.candidateSource));
        event.put("specSha256", Hashing.sha256(ctx.spec));
        Map<String, Object> g = new LinkedHashMap<>();
        results.forEach(r -> g.put(r.id(), r.status().name()));
        event.put("gates", g);
        if (ctx.autoFix != null) {
            event.put("autoFix", "NF-05 null guard");
        }
        new AuditLog(ctx.runDir.getParent().resolve("audit-log.jsonl")).append(event);
        return result;
    }

    private void loadInputs(RunContext ctx) {
        try {
            Path root = ctx.projectRoot;
            ctx.spec = Files.readString(root.resolve("spec/subscription-precheck.md"));
            ctx.contractSource = Files.readString(root.resolve("src/main/java/dev/aiverify/contract/SubscriptionPrecheck.java"))
                    + "\n" + Files.readString(root.resolve("src/main/java/dev/aiverify/contract/SubscriptionRequest.java"));
            state.goldenTests = WealthCase.fromJson(Json.parse(Files.readString(
                    root.resolve("tests/golden-wealth/subscription-cases.json"))), "golden")
                    .stream().filter(t -> t.includedIn(ctx.goldenVersion)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read pipeline inputs: " + e.getMessage(), e);
        }
    }

    private record Pass(List<GateResult> results, boolean blocked, GateResult failedTests) {
        List<Object> summaries() {
            List<Object> out = new ArrayList<>();
            for (GateResult r : results) {
                out.add(Map.of("id", r.id(), "status", r.status().name(), "summary", r.summary()));
            }
            return out;
        }
    }

    private Pass runGates(RunContext ctx) {
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
                if (g instanceof WealthGates.SpecTests) {
                    failedTests = r;
                }
            }
        }
        return new Pass(results, blocked, failedTests);
    }

    private static void log(String s) {
        System.out.println(s);
    }
}
