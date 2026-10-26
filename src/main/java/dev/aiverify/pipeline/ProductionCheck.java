package dev.aiverify.pipeline;

import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.AuthRequest;
import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.json.Json;
import dev.aiverify.testing.TestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * After the gate: send real-looking transactions to the code a human APPROVED and show what it actually does.
 *
 * <p>This is what "deployed" means in the demo. It proves two things the gates alone cannot show on screen:
 * a human who overrides the gate ships a real defect, and code that passed the gate and was approved really
 * behaves as the spec says. Only an APPROVED run can be checked, and only if its code still has the hash the
 * approver saw.
 */
public final class ProductionCheck {

    private static final long TIMEOUT_MS = 1000;

    private ProductionCheck() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> run(Path root, Path runDir) throws IOException {
        Map<String, Object> result = (Map<String, Object>) Json.parse(Files.readString(runDir.resolve("result.json")));
        String runId = String.valueOf(result.get("runId"));
        if (!Pipeline.VERDICT_APPROVED.equals(result.get("verdict"))) {
            throw new IllegalStateException("Run " + runId + " is " + result.get("verdict")
                    + ". Only code a named human approved can go to production.");
        }
        String source = Files.readString(runDir.resolve("candidate/AuthorizationPrecheckImpl.java"));
        if (!Hashing.sha256(source).equals(result.get("candidateSha256"))) {
            throw new IllegalStateException("The code changed after it was approved. It must not be deployed.");
        }
        InMemoryCompiler.Result compiled = new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, source);
        if (!compiled.ok()) {
            throw new IllegalStateException("The approved code does not compile: " + compiled.errors());
        }
        AuthorizationPrecheck code;
        try {
            code = (AuthorizationPrecheck) compiled.loadedClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("The approved code cannot be started: " + e.getMessage());
        }

        Map<String, Object> file = (Map<String, Object>) Json.parse(
                Files.readString(root.resolve("tests/production/transactions.json")));
        List<Map<String, Object>> rows = new ArrayList<>();
        int scored = 0;
        int correct = 0;
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "production-check");
            t.setDaemon(true);
            return t;
        });
        try {
            for (Object o : (List<Object>) file.get("transactions")) {
                Map<String, Object> tx = (Map<String, Object>) o;
                AuthRequest req = new AuthRequest((String) tx.get("pan"), (String) tx.get("expiry"),
                        ((Number) tx.get("amountMinor")).longValue(), ((Number) tx.get("availableMinor")).longValue());
                Object d = tx.get("transactionDate");
                LocalDate date = d == null ? null : LocalDate.parse(d.toString());
                String expected = (String) tx.get("expected");

                String actual = null;
                String error = null;
                Future<String> f = ex.submit(() -> code.check(req, date));
                try {
                    actual = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    error = "no answer within " + TIMEOUT_MS + " ms";
                } catch (Exception e) {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    error = c.getClass().getSimpleName();
                } catch (Throwable e) {
                    error = e.getClass().getSimpleName();
                }

                String outcome;
                if (expected == null) {
                    outcome = "NOT_IN_SPEC";
                } else {
                    scored++;
                    if (expected.equals(actual)) {
                        correct++;
                        outcome = "CORRECT";
                    } else {
                        outcome = "WRONG";
                    }
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", tx.get("id"));
                row.put("rule", tx.get("rule"));
                row.put("title", tx.get("title"));
                row.put("titleZh", tx.get("titleZh"));
                row.put("pan", TestCase.maskPan(req.pan()));
                row.put("expiry", req.expiry());
                row.put("amountMinor", req.amountMinor());
                row.put("availableMinor", req.availableMinor());
                row.put("transactionDate", d);
                row.put("expected", expected);
                row.put("actual", actual);
                row.put("error", error);
                row.put("outcome", outcome);
                if (tx.get("note") != null) {
                    row.put("note", tx.get("note"));
                }
                rows.add(row);
            }
        } finally {
            ex.shutdownNow();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("checkedAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        out.put("candidateSha256", result.get("candidateSha256"));
        out.put("scored", scored);
        out.put("correct", correct);
        out.put("verdict", correct == scored ? "ALL_CORRECT" : "DEFECT_IN_PRODUCTION");
        out.put("transactions", rows);
        Files.writeString(runDir.resolve("production-check.json"), Json.write(out));
        return out;
    }

    /** Plain-text table for the command line. */
    @SuppressWarnings("unchecked")
    public static String format(Map<String, Object> r) {
        StringBuilder b = new StringBuilder();
        b.append("Production check of approved run ").append(r.get("runId")).append('\n');
        b.append("Code hash ").append(String.valueOf(r.get("candidateSha256")), 0, 12)
                .append("... matches what the approver saw.\n\n");
        for (Object o : (List<Object>) r.get("transactions")) {
            Map<String, Object> t = (Map<String, Object>) o;
            String got = t.get("error") != null ? "crash: " + t.get("error") : String.valueOf(t.get("actual"));
            String mark = switch (String.valueOf(t.get("outcome"))) {
                case "CORRECT" -> "OK   ";
                case "WRONG" -> "WRONG";
                default -> "n/a  ";
            };
            b.append(String.format("  %s  %-4s %-36s expected %-4s got %s%n", mark, t.get("id"), t.get("title"),
                    t.get("expected") == null ? "-" : t.get("expected"), got));
        }
        b.append('\n');
        int scored = ((Number) r.get("scored")).intValue();
        int correct = ((Number) r.get("correct")).intValue();
        if (correct == scored) {
            b.append("All ").append(scored).append(" transactions covered by the spec behave as the spec says.\n");
        } else {
            b.append("WRONG: ").append(scored - correct).append(" of ").append(scored)
                    .append(" transactions. This defect is now in production.\n");
        }
        b.append("Cases marked n/a are not covered by the spec; the approver's note says how they are handled.\n");
        return b.toString();
    }
}
