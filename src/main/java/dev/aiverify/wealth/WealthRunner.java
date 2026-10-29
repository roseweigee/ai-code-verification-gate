package dev.aiverify.wealth;

import dev.aiverify.contract.SubscriptionPrecheck;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs wealth test cases against a candidate, with a per-case timeout. Deterministic: expected.equals(actual). */
public final class WealthRunner {

    public enum Status { PASS, FAIL, ERROR, TIMEOUT }

    public record Outcome(WealthCase testCase, Status status, String actual, String detail, int line) {
        public boolean passed() {
            return status == Status.PASS;
        }
    }

    private static final long TIMEOUT_MS = 1000;

    private ExecutorService executor = newExecutor();

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wealth-candidate-runner");
            t.setDaemon(true);
            return t;
        });
    }

    public List<Outcome> runAll(SubscriptionPrecheck candidate, List<WealthCase> cases) {
        List<Outcome> out = new ArrayList<>();
        for (WealthCase tc : cases) {
            out.add(run(candidate, tc));
        }
        return out;
    }

    public boolean anyFails(SubscriptionPrecheck candidate, List<WealthCase> cases) {
        for (WealthCase tc : cases) {
            if (!run(candidate, tc).passed()) {
                return true;
            }
        }
        return false;
    }

    public Outcome run(SubscriptionPrecheck candidate, WealthCase tc) {
        Future<String> f = executor.submit(() -> candidate.check(tc.request(), tc.tradeDate()));
        try {
            String actual = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (tc.expected().equals(actual)) {
                return new Outcome(tc, Status.PASS, actual, null, 0);
            }
            return new Outcome(tc, Status.FAIL, actual, "expected " + tc.expected() + " but got " + actual, 0);
        } catch (TimeoutException e) {
            f.cancel(true);
            executor.shutdownNow();
            executor = newExecutor();
            return new Outcome(tc, Status.TIMEOUT, null, "no answer within " + TIMEOUT_MS + " ms", 0);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            int line = candidateLine(cause);
            return new Outcome(tc, Status.ERROR, cause.getClass().getSimpleName(),
                    cause.getClass().getSimpleName() + (line > 0 ? " at line " + line : ""), line);
        }
    }

    static int candidateLine(Throwable t) {
        for (StackTraceElement el : t.getStackTrace()) {
            if (el.getClassName().startsWith("dev.aiverify.candidate")) {
                return el.getLineNumber();
            }
        }
        return 0;
    }
}
