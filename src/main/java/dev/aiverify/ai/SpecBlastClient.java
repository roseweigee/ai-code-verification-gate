package dev.aiverify.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The author is SpecBlast: its generated {@code AuthorizationRules} class is the code under test.
 *
 * <p>SpecBlast generated a whole Spring Boot project from {@code examples/specblast-iso8583/iso8583-precheck.txt}.
 * The spec asked for the rules in one plain Java class, so the gate can run its tests on exactly that code.
 * The gate adds a small adapter (written by the gate, clearly marked) that maps the human-owned contract onto
 * SpecBlast's method. SpecBlast's class is used unchanged apart from its package line and imports.
 *
 * <p>Other roles: the AI test suite is the same sample set as the three-attempt demo (written from the spec only).
 * The reviewer runs live when ANTHROPIC_API_KEY is set; otherwise G5 is reported as not run, never as passed.
 */
public final class SpecBlastClient implements LlmClient {

    private final String candidate;
    private final String origin;
    private final LlmClient samples;
    private final LlmClient reviewer;

    public SpecBlastClient(String candidate, String origin, LlmClient samples, LlmClient reviewer) {
        this.candidate = candidate;
        this.origin = origin;
        this.samples = samples;
        this.reviewer = reviewer;
    }

    @Override
    public String complete(String role, String system, String user) {
        return switch (role) {
            case "author" -> "```java\n" + candidate + "```\n";
            case "test-writer" -> samples.complete(role, system, user);
            case "reviewer" -> reviewer != null ? reviewer.complete(role, system, user)
                    : "{\"skipped\": true, \"summary\": \"Not run: no reviewer model in offline mode. "
                    + "Set ANTHROPIC_API_KEY to get an independent review.\", \"findings\": []}";
            case "diagnostician" -> "{\"rootCause\": \"Not diagnosed offline. The failing golden tests above show "
                    + "which rule SpecBlast got wrong.\", \"feedbackForAuthor\": null}";
            default -> throw new IllegalStateException("Unknown role " + role);
        };
    }

    @Override
    public String describe() {
        return "SpecBlast: " + origin + (reviewer != null ? "; reviewer " + reviewer.describe() : "");
    }

    /** Finds AuthorizationRules.java in a generated project. */
    public static Path findRules(Path project) throws IOException {
        try (Stream<Path> s = Files.walk(project.resolve("src/main/java"))) {
            List<Path> hits = s.filter(p -> p.getFileName().toString().equals("AuthorizationRules.java")).toList();
            if (hits.isEmpty()) {
                throw new IllegalStateException("No AuthorizationRules.java under " + project.resolve("src/main/java")
                        + ". The spec asks SpecBlast for this class.");
            }
            return hits.get(0);
        }
    }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+[\\w.]+\\s*;\\s*$");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?[\\w.*]+\\s*;\\s*$");

    /**
     * One compilable source: the gate's adapter (public, implements the contract) followed by SpecBlast's class
     * as a second, package-private top-level class.
     */
    public static String wrap(String rulesSource) {
        List<String> imports = new ArrayList<>();
        Matcher m = IMPORT.matcher(rulesSource);
        while (m.find()) {
            String line = m.group().strip();
            if (!imports.contains(line)) {
                imports.add(line);
            }
        }
        String body = IMPORT.matcher(PACKAGE.matcher(rulesSource).replaceAll("")).replaceAll("").strip();
        body = body.replaceFirst("public\\s+(final\\s+)?class\\s+AuthorizationRules", "$1class AuthorizationRules");
        for (String need : List.of("import dev.aiverify.contract.AuthRequest;",
                "import dev.aiverify.contract.AuthorizationPrecheck;", "import java.time.LocalDate;")) {
            if (!imports.contains(need)) {
                imports.add(need);
            }
        }
        return "package dev.aiverify.candidate;\n\n"
                + String.join("\n", imports) + "\n\n"
                + "/** Adapter written by the gate, not by SpecBlast: maps the human-owned contract onto SpecBlast's class. */\n"
                + "public class AuthorizationPrecheckImpl implements AuthorizationPrecheck {\n"
                + "    private final AuthorizationRules rules = new AuthorizationRules();\n\n"
                + "    @Override\n"
                + "    public String check(AuthRequest r, LocalDate transactionDate) {\n"
                + "        return rules.check(r.pan(), r.expiry(), r.amountMinor(), r.availableMinor(), transactionDate);\n"
                + "    }\n"
                + "}\n\n"
                + "// ---- Generated by SpecBlast. Unchanged except: package line and imports moved, class made package-private.\n"
                + body + "\n";
    }
}
