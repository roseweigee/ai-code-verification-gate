package dev.aiverify.testing;

import dev.aiverify.contract.AuthorizationPrecheck;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs data-driven test cases against a candidate, with a per-case timeout. Deterministic. */
public final class TestRunner {

    public enum Status { PASS, FAIL, ERROR, TIMEOUT }

    public record Outcome(TestCase testCase, Status status, String actual, String detail) {
        public boolean passed() {
            return status == Status.PASS;
        }
    }

    private static final long TIMEOUT_MS = 1000;

    private ExecutorService executor = newExecutor();

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "candidate-runner");
            t.setDaemon(true);
            return t;
        });
    }

    public List<Outcome> runAll(AuthorizationPrecheck candidate, List<TestCase> cases) {
        List<Outcome> out = new ArrayList<>();
        for (TestCase tc : cases) {
            out.add(run(candidate, tc));
        }
        return out;
    }

    /** Stops at the first failing case. Used by mutation testing, where one failure is enough to kill a mutant. */
    public boolean anyFails(AuthorizationPrecheck candidate, List<TestCase> cases) {
        for (TestCase tc : cases) {
            if (!run(candidate, tc).passed()) {
                return true;
            }
        }
        return false;
    }

    public Outcome run(AuthorizationPrecheck candidate, TestCase tc) {
        Future<String> f = executor.submit(() -> candidate.check(tc.request(), tc.transactionDate()));
        try {
            String actual = f.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (tc.expected().equals(actual)) {
                return new Outcome(tc, Status.PASS, actual, null);
            }
            return new Outcome(tc, Status.FAIL, actual, "expected " + tc.expected() + " but got " + actual);
        } catch (TimeoutException e) {
            f.cancel(true);
            executor.shutdownNow();
            executor = newExecutor(); // the stuck thread is a daemon and is abandoned
            return new Outcome(tc, Status.TIMEOUT, null, "no answer within " + TIMEOUT_MS + " ms");
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return new Outcome(tc, Status.ERROR, null, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }
}
