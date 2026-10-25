package dev.aiverify.gates;

import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import dev.aiverify.testing.TestCase;
import dev.aiverify.testing.TestRunner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Gate 3 - Spec tests. Two suites, both derived from the spec, neither derived from the code:
 *  - golden: written and approved by a human, held out from the AI author
 *  - ai:     written by the test-writer AI, which never saw the code
 */
public final class SpecTestGate implements Gate {

    @Override
    public String id() {
        return "G3";
    }

    @Override
    public String name() {
        return "Spec tests";
    }

    @Override
    public String decidedBy() {
        return "deterministic";
    }

    @Override
    public GateResult run(RunContext ctx) {
        long t0 = System.currentTimeMillis();
        AuthorizationPrecheck candidate = ctx.newCandidate();
        TestRunner runner = new TestRunner();
        List<TestRunner.Outcome> ai = runner.runAll(candidate, ctx.aiTests);
        List<TestRunner.Outcome> golden = runner.runAll(candidate, ctx.goldenTests);

        long aiFail = ai.stream().filter(o -> !o.passed()).count();
        long goldenFail = golden.stream().filter(o -> !o.passed()).count();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("aiSuite", suite(ai));
        details.put("goldenSuite", suite(golden));
        details.put("aiOnlyVerdict", aiFail == 0 ? "would have PASSED" : "would have FAILED");
        List<Map<String, Object>> failures = new ArrayList<>();
        for (TestRunner.Outcome o : concat(ai, golden)) {
            if (!o.passed()) {
                Map<String, Object> f = o.testCase().toMap();
                f.put("status", o.status().name());
                f.put("actual", o.actual());
                f.put("detail", o.detail());
                failures.add(f);
            }
        }
        details.put("failures", failures);
        details.put("cases", allCases(ai, golden));

        String counts = String.format("AI tests %d/%d passed, human golden tests %d/%d passed.",
                ai.size() - aiFail, ai.size(), golden.size() - goldenFail, golden.size());
        long ms = System.currentTimeMillis() - t0;
        if (aiFail + goldenFail > 0) {
            return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(), counts, details, ms);
        }
        return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(), counts, details, ms);
    }

    /** Text block given to the diagnostician AI. */
    @SuppressWarnings("unchecked")
    public static String describeFailures(GateResult r) {
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> f : (List<Map<String, Object>>) r.details().get("failures")) {
            b.append("- ").append(f.get("id")).append(" (").append(f.get("criterion")).append(", ")
                    .append(f.get("origin")).append("): ").append(f.get("description")).append('\n')
                    .append("  input: expiry=").append(f.get("expiry")).append(", amountMinor=").append(f.get("amountMinor"))
                    .append(", availableMinor=").append(f.get("availableMinor"))
                    .append(", transactionDate=").append(f.get("transactionDate")).append(", pan=").append(f.get("pan"))
                    .append('\n').append("  ").append(f.get("detail")).append('\n');
        }
        return b.toString();
    }

    private static Map<String, Object> suite(List<TestRunner.Outcome> outcomes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", outcomes.size());
        m.put("passed", outcomes.stream().filter(TestRunner.Outcome::passed).count());
        m.put("criteriaCovered", new ArrayList<>(new TreeSet<>(
                outcomes.stream().map(o -> o.testCase().criterion()).toList())));
        return m;
    }

    private static List<Map<String, Object>> allCases(List<TestRunner.Outcome> ai, List<TestRunner.Outcome> golden) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestRunner.Outcome o : concat(ai, golden)) {
            Map<String, Object> m = o.testCase().toMap();
            m.put("status", o.status().name());
            m.put("actual", o.actual());
            out.add(m);
        }
        return out;
    }

    private static List<TestRunner.Outcome> concat(List<TestRunner.Outcome> a, List<TestRunner.Outcome> b) {
        List<TestRunner.Outcome> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
