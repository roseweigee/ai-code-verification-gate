package dev.aiverify.ai;

/**
 * Instructions for each AI role. The separation of what each role may SEE is the point:
 *
 *  - author:        spec + contract (+ spec-level feedback). Never sees any test.
 *  - test-writer:   spec only. Never sees the code, so its tests cannot copy the code's mistakes.
 *  - reviewer:      spec + code, in a fresh context. Did not write the code.
 *  - diagnostician: spec + code + failing cases. Its feedback to the author must stay at spec level.
 */
public final class Prompts {

    private Prompts() {
    }

    public record Prompt(String system, String user) {
    }

    public static Prompt author(String spec, String contract, String feedback) {
        String system = """
                You are a senior Java engineer at a payments company.
                Implement the class dev.aiverify.candidate.AuthorizationPrecheckImpl so that it implements
                dev.aiverify.contract.AuthorizationPrecheck exactly as the specification says.
                Rules:
                - Java 21, no external libraries. Only import java.time, java.util and dev.aiverify.contract.
                - Never print or log the PAN.
                - Return exactly one ```java code block containing the whole file, then nothing else.
                """;
        StringBuilder user = new StringBuilder();
        user.append("## Specification\n\n").append(spec).append("\n\n## Contract\n\n```java\n")
                .append(contract).append("\n```\n");
        if (feedback != null && !feedback.isBlank()) {
            user.append("\n## Feedback on your previous attempt (from the verification pipeline)\n\n")
                    .append(feedback).append('\n');
        }
        return new Prompt(system, user.toString());
    }

    public static Prompt testWriter(String spec) {
        String system = """
                You are a QA engineer for card payments. You have NOT seen any implementation.
                From the specification only, write acceptance test cases as data.
                Answer with one JSON object and nothing else:
                {"cases": [{"id": "AI-01", "criterion": "AC-02", "description": "...",
                  "pan": "...", "expiry": "YYMM", "amountMinor": 1000, "availableMinor": 5000,
                  "transactionDate": "YYYY-MM-DD", "expected": "00"}]}
                Cover every acceptance criterion. Use well-known public test card numbers only.
                """;
        return new Prompt(system, "## Specification\n\n" + spec);
    }

    public static Prompt reviewer(String spec, String code) {
        String system = """
                You are an independent senior code reviewer. You did not write this code.
                Compare the code line by line against the specification and the non-functional rules.
                You cannot run the code; do not claim that anything was tested.
                Answer with one JSON object and nothing else:
                {"summary": "...", "findings": [{"severity": "HIGH|MEDIUM|LOW", "criterion": "AC-xx or NF-xx or GENERAL",
                  "line": 0, "title": "...", "detail": "..."}]}
                HIGH = likely violates the spec. MEDIUM = risky or unclear. LOW = style or maintainability.
                Return an empty findings list if you find nothing.
                """;
        String user = "## Specification\n\n" + spec + "\n\n## Code under review (with line numbers)\n\n```java\n"
                + numbered(code) + "\n```\n";
        return new Prompt(system, user);
    }

    public static Prompt diagnostician(String spec, String code, String failures) {
        String system = """
                You are a debugging specialist. Deterministic tests failed for the code below.
                Find the root cause. Answer with one JSON object and nothing else:
                {"rootCause": "...", "criterion": "AC-xx", "line": 0, "suggestedFix": "...",
                 "confidence": "HIGH|MEDIUM|LOW",
                 "feedbackForAuthor": "..."}
                feedbackForAuthor goes back to the code author. It must refer to the specification only.
                Do NOT include any test input values, so the author cannot simply code to the hidden tests.
                """;
        String user = "## Specification\n\n" + spec + "\n\n## Code (with line numbers)\n\n```java\n" + numbered(code)
                + "\n```\n\n## Failing tests\n\n" + failures;
        return new Prompt(system, user);
    }

    /** Pulls the Java file out of the author's answer. */
    public static String extractJava(String answer) {
        int start = answer.indexOf("```java");
        if (start < 0) {
            start = answer.indexOf("```");
            if (start < 0) {
                return answer.strip();
            }
        }
        int codeStart = answer.indexOf('\n', start) + 1;
        int end = answer.indexOf("```", codeStart);
        return (end < 0 ? answer.substring(codeStart) : answer.substring(codeStart, end)).strip() + "\n";
    }

    private static String numbered(String code) {
        String[] lines = code.split("\n", -1);
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            b.append(String.format("%3d | %s%n", k + 1, lines[k]));
        }
        return b.toString();
    }
}
