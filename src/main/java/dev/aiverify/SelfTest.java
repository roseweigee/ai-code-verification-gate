package dev.aiverify;

import dev.aiverify.compile.InMemoryCompiler;
import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.gates.PolicyGate;
import dev.aiverify.json.Json;
import dev.aiverify.mutation.Mutator;
import dev.aiverify.pipeline.AuditLog;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import dev.aiverify.testing.TestCase;
import dev.aiverify.testing.TestRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Tests for the verification engine itself (the gate must be trustworthy before it judges AI code).
 * Plain Java, no test framework, so it runs in locked-down environments without a dependency download.
 */
final class SelfTest {

    private static int passed;
    private static int failed;

    static boolean run() throws Exception {
        json();
        luhn();
        mutator();
        compilerAndRunner();
        policy();
        audit();
        outbound();
        projectRules();
        System.out.printf("%nSelf-test: %d passed, %d failed%n", passed, failed);
        return failed == 0;
    }

    private static void json() {
        Object v = Json.parse("{\"a\":[1,2.5,\"x\\n<y>\",true,null],\"b\":{}}");
        check("JSON round trip", Json.parse(Json.write(v)).equals(v));
        check("JSON escapes '<' for safe HTML embedding", Json.writeCompact("</script>").contains("\\u003c"));
        check("JSON found inside prose",
                ((Map<?, ?>) Json.parseEmbedded("Here you go:\n```json\n{\"k\": 1}\n```")).get("k").equals(1L));
    }

    private static void luhn() {
        check("Luhn accepts 4111111111111111", PolicyGate.luhn("4111111111111111"));
        check("Luhn accepts 378282246310005 (15 digits)", PolicyGate.luhn("378282246310005"));
        check("Luhn rejects 4111111111111112", !PolicyGate.luhn("4111111111111112"));
    }

    private static void mutator() {
        String src = """
                package x;
                import java.util.List;
                class A {
                    // a < b in a comment is not mutated
                    String s = "a < b";
                    boolean f(int a, int b) { return a < b && b != 3; }
                    int g(int a) { return -a; }
                }
                """;
        List<Mutator.Mutant> ms = new Mutator().mutate(src);
        long boundary = ms.stream().filter(m -> m.operator().equals("Conditional boundary")).count();
        long logical = ms.stream().filter(m -> m.operator().equals("Swap logical operator")).count();
        long math = ms.stream().filter(m -> m.operator().equals("Math operator")).count();
        check("Mutator: one boundary mutant for 'a < b' (comment ignored)", boundary == 1);
        check("Mutator: '&&' swapped to '||'", logical == 1);
        check("Mutator: unary minus is not treated as subtraction", math == 0);
        check("Mutator: every mutant changes exactly one line",
                ms.stream().allMatch(m -> !m.originalLine().equals(m.mutatedLine())));
    }

    private static void compilerAndRunner() {
        String ok = """
                package dev.aiverify.candidate;
                import dev.aiverify.contract.*;
                import java.time.LocalDate;
                public class AuthorizationPrecheckImpl implements AuthorizationPrecheck {
                    public String check(AuthRequest r, LocalDate d) { return "00"; }
                }
                """;
        InMemoryCompiler.Result r = new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, ok);
        check("Compiler loads a valid candidate", r.ok());
        check("Compiler reports errors with line numbers",
                !new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, ok.replace("\"00\";", "\"00\""))
                        .errors().isEmpty());

        String loop = ok.replace("return \"00\";", "while (true) { } ");
        AuthorizationPrecheck hang = instance(new InMemoryCompiler().compile(RunContext.CANDIDATE_CLASS, loop));
        TestCase tc = new TestCase("T", "AC-06", "", "golden", "4111111111111111", "3012", 100, 200,
                LocalDate.of(2026, 1, 1), "00");
        check("Runner stops an infinite loop with TIMEOUT",
                new TestRunner().run(hang, tc).status() == TestRunner.Status.TIMEOUT);
        check("Runner passes a correct answer",
                new TestRunner().run(instance(r), tc).status() == TestRunner.Status.PASS);
    }

    private static void policy() {
        String bad = """
                package dev.aiverify.candidate;
                import java.net.URL;
                public class AuthorizationPrecheckImpl {
                    // java.io in a comment is fine
                    void f(String pan) { System.out.println("card " + pan); }
                    String apiKey = "abc123";
                }
                """;
        RunContext ctx = new RunContext(Path.of("."), Path.of("."), "self", 0, null);
        ctx.candidateSource = bad;
        GateResult g = new PolicyGate().run(ctx);
        String text = g.details().toString();
        check("Policy: blocks the network import", text.contains("java.net.URL"));
        check("Policy: blocks printing the PAN", text.contains("PAN written to console"));
        check("Policy: blocks a hard-coded secret", text.contains("Hard-coded secret"));
        check("Policy: ignores java.io inside a comment", !text.contains("File system access"));
        check("Policy: gate FAILED", g.status() == GateResult.Status.FAILED);
    }

    private static void audit() throws Exception {
        Path dir = Files.createTempDirectory("audit");
        Path file = dir.resolve("log.jsonl");
        AuditLog log = new AuditLog(file);
        log.append(Map.of("event", "A"));
        log.append(Map.of("event", "B"));
        check("Audit chain verifies when untouched", log.verify() == null);
        Files.writeString(file, Files.readString(file).replace("\"B\"", "\"C\""));
        check("Audit chain detects an edited entry", log.verify() != null);
    }

    private static void outbound() {
        check("Outbound: blocks a full card number in a prompt",
                !dev.aiverify.ai.AiPolicy.scanOutbound("pan=4111111111111111").isEmpty());
        check("Outbound: allows a masked card number", dev.aiverify.ai.AiPolicy.scanOutbound("pan=411111******1111").isEmpty());
        check("Outbound: blocks a Taiwan national ID", !dev.aiverify.ai.AiPolicy.scanOutbound("id A123456789").isEmpty());
        check("Outbound: blocks an email address", !dev.aiverify.ai.AiPolicy.scanOutbound("mail rose@example.com").isEmpty());
        check("Outbound: allows ordinary amounts and dates",
                dev.aiverify.ai.AiPolicy.scanOutbound("amountMinor=12345, date 2026-10-28, code 51").isEmpty());
        boolean refused;
        try {
            new dev.aiverify.ai.AiPolicy(Path.of(System.getProperty("aiverify.root", "."))).requireApprovedModel("some-public-chatbot");
            refused = false;
        } catch (IllegalStateException e) {
            refused = true;
        }
        check("Approved tools: refuses a model that is not on the list", refused);
    }

    @SuppressWarnings("unchecked")
    private static void projectRules() throws Exception {
        String yaml = """
                apiVersion: apps/v1
                kind: Deployment
                spec:
                  replicas: 1
                  template:
                    spec:
                      containers:
                        - name: app   # comment
                          image: "registry.example/app:latest"
                          env:
                            - name: DB_PASSWORD
                              value: "hunter2"
                ---
                kind: Service
                """;
        List<Object> docs = dev.aiverify.project.MiniYaml.parseAll(yaml);
        Object c = dev.aiverify.project.MiniYaml.list(docs.get(0), "spec", "template", "spec", "containers").get(0);
        check("YAML: two documents, list of maps, quotes and comments",
                docs.size() == 2 && "registry.example/app:latest".equals(dev.aiverify.project.MiniYaml.str(c, "image"))
                        && "app".equals(dev.aiverify.project.MiniYaml.str(c, "name")));
        Path dir = Files.createTempDirectory("proj");
        Files.createDirectories(dir.resolve("k8s/prod"));
        Files.writeString(dir.resolve("k8s/prod/deployment.yaml"), yaml);
        Map<String, Object> r = dev.aiverify.project.ProjectVerifier.verify(dir, dir, "selftest", false);
        String text = r.toString();
        check("Project: prod :latest is an ERROR", text.contains("Mutable image tag in prod"));
        check("Project: password as plain env value is an ERROR", text.contains("Secret written as a plain environment value"));
        check("Project: single prod replica is an ERROR", text.contains("Production runs a single replica"));
        check("Project: missing probes and limits are ERRORs",
                text.contains("Missing health probes") && text.contains("Missing resource requests or limits"));
        check("Project: verdict BLOCKED", "BLOCKED".equals(r.get("verdict")));
    }

    private static AuthorizationPrecheck instance(InMemoryCompiler.Result r) {
        try {
            return (AuthorizationPrecheck) r.loadedClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }

    private SelfTest() {
    }
}
