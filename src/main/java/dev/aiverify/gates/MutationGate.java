package dev.aiverify.gates;

import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.mutation.Mutator;
import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import dev.aiverify.testing.TestCase;
import dev.aiverify.testing.TestRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate 4 - Mutation testing: "who tests the tests?"
 *
 * Passing tests only mean something if the tests would notice a wrong program. We plant
 * one small bug at a time (a mutant) and re-run the suite. A mutant the suite does not
 * notice ("survived") shows a gap in the tests. The score is reported for the combined
 * suite (the gate decision) and for the AI suite alone (to show what AI tests miss).
 */
public final class MutationGate implements Gate {

    public static final double THRESHOLD = 0.80;

    @Override
    public String id() {
        return "G4";
    }

    @Override
    public String name() {
        return "Mutation testing";
    }

    @Override
    public String decidedBy() {
        return "deterministic";
    }

    @Override
    public GateResult run(RunContext ctx) {
        long t0 = System.currentTimeMillis();
        List<Mutator.Mutant> mutants = new Mutator().mutate(ctx.candidateSource);
        InMemoryCompiler compiler = new InMemoryCompiler();
        TestRunner runner = new TestRunner();
        List<TestCase> all = new ArrayList<>(ctx.aiTests);
        all.addAll(ctx.goldenTests);

        int stillborn = 0;
        int killedAll = 0;
        int killedAi = 0;
        int killedGolden = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Mutator.Mutant m : mutants) {
            InMemoryCompiler.Result r = compiler.compile(RunContext.CANDIDATE_CLASS, m.source());
            if (!r.ok()) {
                stillborn++; // the change made the code invalid, e.g. a mutated generic type
                continue;
            }
            AuthorizationPrecheck mutant;
            try {
                mutant = (AuthorizationPrecheck) r.loadedClass().getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | RuntimeException e) {
                killedAll++;
                killedAi++;
                killedGolden++;
                continue; // a mutant that cannot even start is trivially detected
            }
            boolean byAi = runner.anyFails(mutant, ctx.aiTests);
            boolean byGolden = runner.anyFails(mutant, ctx.goldenTests);
            boolean byAll = byAi || byGolden;
            if (byAll) {
                killedAll++;
            }
            if (byAi) {
                killedAi++;
            }
            if (byGolden) {
                killedGolden++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.id());
            row.put("operator", m.operator());
            row.put("line", m.line());
            row.put("change", m.original() + "  ->  " + m.replacement());
            row.put("originalLine", m.originalLine());
            row.put("mutatedLine", m.mutatedLine());
            row.put("killedByAiTests", byAi);
            row.put("killedByGoldenTests", byGolden);
            row.put("survived", !byAll);
            rows.add(row);
        }

        int valid = mutants.size() - stillborn;
        double scoreAll = valid == 0 ? 0 : (double) killedAll / valid;
        double scoreAi = valid == 0 ? 0 : (double) killedAi / valid;
        double scoreGolden = valid == 0 ? 0 : (double) killedGolden / valid;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("threshold", THRESHOLD);
        details.put("mutantsGenerated", mutants.size());
        details.put("stillborn", stillborn);
        details.put("validMutants", valid);
        details.put("killedCombined", killedAll);
        details.put("killedByAiTests", killedAi);
        details.put("killedByGoldenTests", killedGolden);
        details.put("scoreCombined", round(scoreAll));
        details.put("scoreAiOnly", round(scoreAi));
        details.put("scoreGoldenOnly", round(scoreGolden));
        details.put("survivors", rows.stream().filter(x -> (Boolean) x.get("survived")).toList());
        details.put("missedByAiOnly", rows.stream()
                .filter(x -> !(Boolean) x.get("killedByAiTests") && (Boolean) x.get("killedByGoldenTests")).toList());

        String summary = String.format("Combined tests detect %d of %d mutants (%.1f%%; threshold %.0f%%). AI tests alone: %.1f%%.",
                killedAll, valid, scoreAll * 100, THRESHOLD * 100, scoreAi * 100);
        GateResult.Status status = scoreAll >= THRESHOLD ? GateResult.Status.PASSED : GateResult.Status.FAILED;
        return new GateResult(id(), name(), status, decidedBy(), summary, details, System.currentTimeMillis() - t0);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
