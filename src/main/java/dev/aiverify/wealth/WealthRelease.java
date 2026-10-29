package dev.aiverify.wealth;

import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.SubscriptionPrecheck;
import dev.aiverify.json.Json;
import dev.aiverify.pipeline.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The release of the wealth scenario: only code a named person APPROVED goes to production.
 *
 * <pre>
 *   wealth release --run RUN_ID     copy an APPROVED run's code and approval record into release/wealth/
 *   wealth release-check            approval record + SHA-256 + every golden test, before any deploy
 * </pre>
 *
 * The production service ({@code wealth-serve-release}) runs the same check when it starts and refuses to serve
 * code whose SHA-256 differs from the one the person approved.
 */
public final class WealthRelease {

    public static final String DIR = "release/wealth";
    public static final String CODE = "SubscriptionPrecheckImpl.java";
    public static final String APPROVAL = "approval.json";

    private WealthRelease() {
    }

    /** Copies the code and the decision of an APPROVED run into release/wealth/. */
    @SuppressWarnings("unchecked")
    static void release(Path root, Path runs, String runId) throws IOException {
        Path runDir = runs.resolve(runId);
        Map<String, Object> result = Json.parseObject(Files.readString(runDir.resolve("result.json")));
        if (!WealthPipeline.VERDICT_APPROVED.equals(result.get("verdict"))) {
            throw new IllegalStateException("Run " + runId + " is " + result.get("verdict")
                    + ". Only code a named person approved can be released.");
        }
        String code = Files.readString(runDir.resolve(WealthPipeline.CANDIDATE_FILE));
        if (!Hashing.sha256(code).equals(result.get("candidateSha256"))) {
            throw new IllegalStateException("The code changed after it was approved. It must not be released.");
        }
        Map<String, Object> g6 = null;
        List<Map<String, Object>> gates = new ArrayList<>();
        for (Map<String, Object> g : (List<Map<String, Object>>) result.get("gates")) {
            gates.add(Map.of("id", g.get("id"), "status", g.get("status")));
            if ("G6".equals(g.get("id"))) {
                g6 = (Map<String, Object>) g.get("details");
            }
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("scenario", "wealth");
        a.put("runId", runId);
        a.put("attempt", result.get("attempt"));
        a.put("candidateSha256", result.get("candidateSha256"));
        a.put("decision", "APPROVED");
        a.put("approvedBy", g6.get("decidedBy"));
        a.put("approvedAt", g6.get("decidedAt"));
        a.put("note", g6.get("note"));
        a.put("acceptedHighFindings", g6.get("acceptedHighFindings"));
        a.put("goldenVersion", result.get("goldenVersion"));
        a.put("gates", gates);
        a.put("releasedAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        Path dir = root.resolve(DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(CODE), code);
        Files.writeString(dir.resolve(APPROVAL), Json.write(a));
        System.out.println("Released " + runId + " (approved by " + a.get("approvedBy") + ") to " + DIR + "/");
    }

    /** Throws unless the release is approved, unchanged, and answers every golden test correctly. */
    public static Map<String, Object> check(Path root, Path dir) throws IOException {
        Path approvalFile = dir.resolve(APPROVAL);
        if (!Files.exists(approvalFile)) {
            throw new IllegalStateException("No approval record in " + DIR + ". Nothing a person approved, nothing to deploy.");
        }
        Map<String, Object> a = Json.parseObject(Files.readString(approvalFile));
        if (!"APPROVED".equals(a.get("decision")) || a.get("approvedBy") == null
                || String.valueOf(a.get("approvedBy")).isBlank()) {
            throw new IllegalStateException("The release has no named human approval. It must not be deployed.");
        }
        String code = Files.readString(dir.resolve(CODE));
        String sha = Hashing.sha256(code);
        if (!sha.equals(a.get("candidateSha256"))) {
            throw new IllegalStateException("SHA-256 of the code (" + sha + ") is not the one the person approved ("
                    + a.get("candidateSha256") + "). It must not be deployed.");
        }
        SubscriptionPrecheck impl = load(code);
        List<WealthCase> cases = WealthCase.fromJson(
                Json.parse(Files.readString(root.resolve("tests/golden-wealth/subscription-cases.json"))), "golden");
        int ok = 0;
        List<String> wrong = new ArrayList<>();
        for (WealthCase c : cases) {
            String got;
            try {
                got = impl.check(c.request(), c.tradeDate());
            } catch (RuntimeException e) {
                got = "CRASH " + e.getClass().getSimpleName();
            }
            if (c.expected().equals(got)) {
                ok++;
            } else {
                wrong.add(c.id() + " expected " + c.expected() + ", got " + got);
            }
        }
        if (!wrong.isEmpty()) {
            throw new IllegalStateException("Golden tests " + ok + "/" + cases.size() + ": " + String.join("; ", wrong));
        }
        Map<String, Object> out = new LinkedHashMap<>(a);
        out.put("goldenPassed", ok);
        out.put("goldenTotal", cases.size());
        return out;
    }

    static void printCheck(Path root) throws IOException {
        Map<String, Object> r = check(root, root.resolve(DIR));
        System.out.println("Release of run " + r.get("runId") + " (attempt " + r.get("attempt") + ")");
        System.out.println("  Approved by " + r.get("approvedBy") + " at " + r.get("approvedAt"));
        System.out.println("  SHA-256 matches the approved code: " + r.get("candidateSha256"));
        System.out.println("  Golden tests: " + r.get("goldenPassed") + "/" + r.get("goldenTotal") + " correct");
        System.out.println("Release check PASSED.");
    }

    static SubscriptionPrecheck load(String code) {
        InMemoryCompiler.Result r = new InMemoryCompiler().compile(WealthGates.CANDIDATE_CLASS, code);
        if (!r.ok()) {
            throw new IllegalStateException("The released code does not compile: " + r.errors());
        }
        try {
            return (SubscriptionPrecheck) r.loadedClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot load the released code: " + e);
        }
    }
}
