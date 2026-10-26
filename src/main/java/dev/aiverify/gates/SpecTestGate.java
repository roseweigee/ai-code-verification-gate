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
 * plus the baseline robustness rule NF-05 that applies to every candidate, whatever the spec says:
 * no input may make check() throw; a null request or transaction date is a format error (30).
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
        List<Map<String, Object>> baseline = baselineProbes(candidate);
        long baseFail = baseline.stream().filter(m -> !"PASS".equals(m.get("status"))).count();

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
        for (Map<String, Object> b : baseline) {
            if (!"PASS".equals(b.get("status"))) {
                failures.add(b);
            }
        }
        details.put("failures", failures);
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("total", baseline.size());
        base.put("passed", baseline.size() - baseFail);
        base.put("rule", "NF-05: no input may make check() throw; null request or date returns 30");
        details.put("baselineSuite", base);
        details.put("baselineOnly", aiFail + goldenFail == 0 && baseFail > 0);
        details.put("cases", allCases(ai, golden));

        String counts = String.format("AI tests %d/%d passed, human golden tests %d/%d passed, null-input checks %d/%d passed.",
                ai.size() - aiFail, ai.size(), golden.size() - goldenFail, golden.size(),
                baseline.size() - baseFail, baseline.size());
        long ms = System.currentTimeMillis() - t0;
        if (aiFail + goldenFail + baseFail > 0) {
            return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(), counts, details, ms);
        }
        return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(), counts, details, ms);
    }

    /**
     * NF-05 probes: inputs the spec may not list but no payment code may crash on.
     * A thrown exception is reported with the line in the candidate where it happened.
     */
    static List<Map<String, Object>> baselineProbes(AuthorizationPrecheck candidate) {
        dev.aiverify.contract.AuthRequest valid =
                new dev.aiverify.contract.AuthRequest("4111111111111111", "2812", 1000, 5000);
        java.time.LocalDate date = java.time.LocalDate.of(2026, 10, 27);
        Object[][] probes = {
            {"R-01", "Transaction date is null", valid, null},
            {"R-02", "Request is null", null, date},
        };
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "baseline-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            for (Object[] p : probes) {
                dev.aiverify.contract.AuthRequest req = (dev.aiverify.contract.AuthRequest) p[2];
                java.time.LocalDate d = (java.time.LocalDate) p[3];
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p[0]);
                m.put("criterion", "NF-05");
                m.put("description", p[1]);
                m.put("origin", "baseline");
                m.put("pan", req == null ? null : TestCase.maskPan(req.pan()));
                m.put("expiry", req == null ? null : req.expiry());
                m.put("amountMinor", req == null ? null : req.amountMinor());
                m.put("availableMinor", req == null ? null : req.availableMinor());
                m.put("transactionDate", d == null ? null : d.toString());
                m.put("expected", "30");
                java.util.concurrent.Future<String> f = ex.submit(() -> candidate.check(req, d));
                try {
                    String actual = f.get(1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    m.put("actual", actual);
                    m.put("status", "30".equals(actual) ? "PASS" : "FAIL");
                    m.put("detail", "30".equals(actual) ? null : "expected 30 but got " + actual);
                } catch (java.util.concurrent.TimeoutException e) {
                    f.cancel(true);
                    m.put("status", "TIMEOUT");
                    m.put("detail", "no answer within 1000 ms");
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    int line = 0;
                    for (StackTraceElement el : c.getStackTrace()) {
                        if (el.getClassName().startsWith("dev.aiverify.candidate")) {
                            line = el.getLineNumber();
                            break;
                        }
                    }
                    m.put("actual", c.getClass().getSimpleName());
                    m.put("status", "ERROR");
                    m.put("line", line);
                    m.put("detail", c.getClass().getSimpleName() + (line > 0 ? " at line " + line : ""));
                }
                out.add(m);
            }
        } finally {
            ex.shutdownNow();
        }
        return out;
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
