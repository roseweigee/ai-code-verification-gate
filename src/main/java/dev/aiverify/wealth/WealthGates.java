package dev.aiverify.wealth;

import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.SubscriptionPrecheck;
import dev.aiverify.contract.SubscriptionRequest;
import dev.aiverify.gates.PolicyGate;
import dev.aiverify.mutation.Mutator;
import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Gates G1-G4 for the wealth scenario. They make the same decisions as the card-payment gates:
 * the same policy rules, the same in-memory compiler, the same mutation engine and the same 80% threshold.
 * Only the contract type and the test data differ. G5 (AI review) is reused unchanged.
 */
public final class WealthGates {

    public static final String CANDIDATE_CLASS = "dev.aiverify.candidate.SubscriptionPrecheckImpl";
    public static final String FORMAT_ERROR = "FMT";

    private WealthGates() {
    }

    /** What the wealth gates share during one run. */
    public static final class State {
        public List<WealthCase> goldenTests = new ArrayList<>();
        public List<WealthCase> aiTests = new ArrayList<>();
        public Class<?> compiledClass;

        public SubscriptionPrecheck newCandidate() {
            try {
                return (SubscriptionPrecheck) compiledClass.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot create the candidate: " + e.getMessage(), e);
            }
        }
    }

    /**
     * G1. Runs the shared policy gate, then applies the stricter NF-01 of this spec: client data must never be
     * logged, so any console or log output is a violation (the shared gate only notes output without a PAN).
     */
    public static final class Policy implements Gate {
        private final PolicyGate shared = new PolicyGate();

        public String id() {
            return "G1";
        }

        public String name() {
            return "Policy guard";
        }

        public String decidedBy() {
            return "deterministic";
        }

        @SuppressWarnings("unchecked")
        public GateResult run(RunContext ctx) {
            GateResult r = shared.run(ctx);
            Map<String, Object> details = new LinkedHashMap<>(r.details());
            List<Map<String, Object>> violations = new ArrayList<>((List<Map<String, Object>>) details.get("violations"));
            for (Map<String, Object> n : (List<Map<String, Object>>) details.get("notes")) {
                Map<String, Object> v = new LinkedHashMap<>(n);
                v.put("rule", "NF-01");
                v.put("title", "Console or log output: client data must not be logged");
                violations.add(v);
            }
            details.put("violations", violations);
            details.put("notes", List.of());
            details.put("rulesChecked", List.of(
                    "NF-01 no console or log output at all (client data)",
                    "NF-02 no file, network, process, reflection or System.exit",
                    "NF-03 imports only from java.time, java.util, dev.aiverify.contract",
                    "NF-04 no hard-coded secrets",
                    "Shared: no comments or strings that try to steer the AI reviewer"));
            if (!violations.isEmpty()) {
                return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                        violations.size() + " policy violation(s).", details, r.durationMs());
            }
            return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(),
                    "No policy violations (5 rule groups).", details, r.durationMs());
        }
    }

    /** G2. Compile in memory and check the code implements the human-owned SubscriptionPrecheck. */
    public static final class Compile implements Gate {
        private final State state;

        public Compile(State state) {
            this.state = state;
        }

        public String id() {
            return "G2";
        }

        public String name() {
            return "Compile & contract";
        }

        public String decidedBy() {
            return "deterministic";
        }

        public GateResult run(RunContext ctx) {
            long t0 = System.currentTimeMillis();
            InMemoryCompiler.Result r = new InMemoryCompiler().compile(CANDIDATE_CLASS, ctx.candidateSource);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("compilerErrors", r.errors());
            if (!r.ok()) {
                return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                        "Does not compile (" + r.errors().size() + " error(s)).", details, System.currentTimeMillis() - t0);
            }
            if (!SubscriptionPrecheck.class.isAssignableFrom(r.loadedClass())) {
                details.put("compilerErrors", List.of("Class does not implement " + SubscriptionPrecheck.class.getName()));
                return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                        "Compiles, but does not implement the contract.", details, System.currentTimeMillis() - t0);
            }
            state.compiledClass = r.loadedClass();
            try {
                state.newCandidate();
            } catch (RuntimeException e) {
                details.put("compilerErrors", List.of(e.getMessage()));
                return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                        "Compiles, but cannot be created with a no-argument constructor.", details,
                        System.currentTimeMillis() - t0);
            }
            return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(),
                    "Compiles with Java 17 and implements SubscriptionPrecheck.", details, System.currentTimeMillis() - t0);
        }
    }

    /** G3. AI tests (from the spec only) + hidden human golden tests + NF-05 null-input probes. */
    public static final class SpecTests implements Gate {
        private final State state;

        public SpecTests(State state) {
            this.state = state;
        }

        public String id() {
            return "G3";
        }

        public String name() {
            return "Spec tests";
        }

        public String decidedBy() {
            return "deterministic";
        }

        public GateResult run(RunContext ctx) {
            long t0 = System.currentTimeMillis();
            SubscriptionPrecheck candidate = state.newCandidate();
            WealthRunner runner = new WealthRunner();
            List<WealthRunner.Outcome> ai = runner.runAll(candidate, state.aiTests);
            List<WealthRunner.Outcome> golden = runner.runAll(candidate, state.goldenTests);
            List<Map<String, Object>> baseline = baselineProbes(candidate);
            long baseFail = baseline.stream().filter(m -> !"PASS".equals(m.get("status"))).count();
            long aiFail = ai.stream().filter(o -> !o.passed()).count();
            long goldenFail = golden.stream().filter(o -> !o.passed()).count();

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("aiSuite", suite(ai));
            details.put("goldenSuite", suite(golden));
            details.put("aiOnlyVerdict", aiFail == 0 ? "would have PASSED" : "would have FAILED");
            List<Map<String, Object>> failures = new ArrayList<>();
            List<Map<String, Object>> cases = new ArrayList<>();
            List<WealthRunner.Outcome> all = new ArrayList<>(ai);
            all.addAll(golden);
            for (WealthRunner.Outcome o : all) {
                Map<String, Object> m = o.testCase().toMap();
                m.put("status", o.status().name());
                m.put("actual", o.actual());
                cases.add(m);
                if (!o.passed()) {
                    Map<String, Object> f = new LinkedHashMap<>(m);
                    f.put("detail", o.detail());
                    if (o.line() > 0) {
                        f.put("line", o.line());
                    }
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
            base.put("rule", "NF-05: no input may make check() throw; null request or trade date returns FMT");
            details.put("baselineSuite", base);
            details.put("baselineOnly", aiFail + goldenFail == 0 && baseFail > 0);
            details.put("cases", cases);

            String counts = String.format(
                    "AI tests %d/%d passed, human golden tests %d/%d passed, null-input checks %d/%d passed.",
                    ai.size() - aiFail, ai.size(), golden.size() - goldenFail, golden.size(),
                    baseline.size() - baseFail, baseline.size());
            GateResult.Status s = aiFail + goldenFail + baseFail > 0 ? GateResult.Status.FAILED : GateResult.Status.PASSED;
            return new GateResult(id(), name(), s, decidedBy(), counts, details, System.currentTimeMillis() - t0);
        }

        static List<Map<String, Object>> baselineProbes(SubscriptionPrecheck candidate) {
            SubscriptionRequest valid = new SubscriptionRequest("C3", "RR2", LocalDate.of(2026, 3, 15),
                    1_000_000, 300_000, 0, 50_000_000);
            LocalDate date = LocalDate.of(2026, 10, 30);
            Object[][] probes = {
                {"R-01", "Trade date is null", valid, null},
                {"R-02", "Order is null", null, date},
            };
            List<Map<String, Object>> out = new ArrayList<>();
            ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "wealth-baseline-probe");
                t.setDaemon(true);
                return t;
            });
            try {
                for (Object[] p : probes) {
                    SubscriptionRequest req = (SubscriptionRequest) p[2];
                    LocalDate d = (LocalDate) p[3];
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p[0]);
                    m.put("criterion", "NF-05");
                    m.put("description", p[1]);
                    m.put("origin", "baseline");
                    m.put("tradeDate", d == null ? null : d.toString());
                    m.put("expected", FORMAT_ERROR);
                    Future<String> f = ex.submit(() -> candidate.check(req, d));
                    try {
                        String actual = f.get(1000, TimeUnit.MILLISECONDS);
                        m.put("actual", actual);
                        m.put("status", FORMAT_ERROR.equals(actual) ? "PASS" : "FAIL");
                        m.put("detail", FORMAT_ERROR.equals(actual) ? null : "expected FMT but got " + actual);
                    } catch (TimeoutException e) {
                        f.cancel(true);
                        m.put("status", "TIMEOUT");
                        m.put("detail", "no answer within 1000 ms");
                    } catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        int line = WealthRunner.candidateLine(c);
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

        private static Map<String, Object> suite(List<WealthRunner.Outcome> outcomes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", outcomes.size());
            m.put("passed", outcomes.stream().filter(WealthRunner.Outcome::passed).count());
            m.put("criteriaCovered", new ArrayList<>(new TreeSet<>(
                    outcomes.stream().map(o -> o.testCase().criterion()).toList())));
            return m;
        }

        /** Text given to the diagnostician AI. */
        @SuppressWarnings("unchecked")
        public static String describeFailures(GateResult r) {
            StringBuilder b = new StringBuilder();
            for (Map<String, Object> f : (List<Map<String, Object>>) r.details().get("failures")) {
                b.append("- ").append(f.get("id")).append(" (").append(f.get("criterion")).append(", ")
                        .append(f.get("origin")).append("): ").append(f.get("description")).append('\n')
                        .append("  input: client=").append(f.get("clientRiskLevel"))
                        .append(", fund=").append(f.get("fundRiskLevel"))
                        .append(", kycAssessedOn=").append(f.get("kycAssessedOn"))
                        .append(", amountMinor=").append(f.get("amountMinor"))
                        .append(", minimumMinor=").append(f.get("minimumMinor"))
                        .append(", holdingMinor=").append(f.get("holdingMinor"))
                        .append(", portfolioMinor=").append(f.get("portfolioMinor"))
                        .append(", tradeDate=").append(f.get("tradeDate")).append('\n')
                        .append("  ").append(f.get("detail")).append('\n');
            }
            return b.toString();
        }
    }

    /** G4. Mutation testing with the shared mutation engine and the same 80% threshold. */
    public static final class Mutation implements Gate {
        public static final double THRESHOLD = 0.80;
        private final State state;

        public Mutation(State state) {
            this.state = state;
        }

        public String id() {
            return "G4";
        }

        public String name() {
            return "Mutation testing";
        }

        public String decidedBy() {
            return "deterministic";
        }

        public GateResult run(RunContext ctx) {
            long t0 = System.currentTimeMillis();
            List<Mutator.Mutant> mutants = new Mutator().mutate(ctx.candidateSource);
            InMemoryCompiler compiler = new InMemoryCompiler();
            WealthRunner runner = new WealthRunner();
            int stillborn = 0;
            int killedAll = 0;
            int killedAi = 0;
            int killedGolden = 0;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Mutator.Mutant m : mutants) {
                InMemoryCompiler.Result r = compiler.compile(CANDIDATE_CLASS, m.source());
                if (!r.ok()) {
                    stillborn++;
                    continue;
                }
                SubscriptionPrecheck mutant;
                try {
                    mutant = (SubscriptionPrecheck) r.loadedClass().getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException | RuntimeException e) {
                    killedAll++;
                    killedAi++;
                    killedGolden++;
                    continue;
                }
                boolean byAi = runner.anyFails(mutant, state.aiTests);
                boolean byGolden = runner.anyFails(mutant, state.goldenTests);
                if (byAi || byGolden) {
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
                row.put("survived", !(byAi || byGolden));
                rows.add(row);
            }
            int valid = mutants.size() - stillborn;
            double all = valid == 0 ? 0 : (double) killedAll / valid;
            double aiOnly = valid == 0 ? 0 : (double) killedAi / valid;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("threshold", THRESHOLD);
            details.put("mutantsGenerated", mutants.size());
            details.put("stillborn", stillborn);
            details.put("validMutants", valid);
            details.put("killedCombined", killedAll);
            details.put("killedByAiTests", killedAi);
            details.put("killedByGoldenTests", killedGolden);
            details.put("scoreCombined", Math.round(all * 1000) / 1000.0);
            details.put("scoreAiOnly", Math.round(aiOnly * 1000) / 1000.0);
            details.put("survivors", rows.stream().filter(x -> (Boolean) x.get("survived")).toList());
            details.put("missedByAiOnly", rows.stream()
                    .filter(x -> !(Boolean) x.get("killedByAiTests") && (Boolean) x.get("killedByGoldenTests")).toList());
            String summary = String.format(
                    "Combined tests detect %d of %d mutants (%.1f%%; threshold %.0f%%). AI tests alone: %.1f%%.",
                    killedAll, valid, all * 100, THRESHOLD * 100, aiOnly * 100);
            GateResult.Status s = all >= THRESHOLD ? GateResult.Status.PASSED : GateResult.Status.FAILED;
            return new GateResult(id(), name(), s, decidedBy(), summary, details, System.currentTimeMillis() - t0);
        }
    }
}
