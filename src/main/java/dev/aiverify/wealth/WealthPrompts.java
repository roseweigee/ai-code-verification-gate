package dev.aiverify.wealth;

import dev.aiverify.ai.Prompts;

/**
 * Author and test-writer instructions for the wealth scenario. The separation of what each role may SEE is the
 * same as for card payments: the author sees the spec and the contract, never a test; the test writer sees the
 * spec only. The reviewer and diagnostician prompts are shared ({@link Prompts}).
 */
public final class WealthPrompts {

    private WealthPrompts() {
    }

    public static Prompts.Prompt author(String spec, String contract, String feedback) {
        String system = """
                You are a senior Java engineer at an asset management firm.
                Implement the class dev.aiverify.candidate.SubscriptionPrecheckImpl so that it implements
                dev.aiverify.contract.SubscriptionPrecheck exactly as the specification says.
                Rules:
                - Java 17, no external libraries. Only import java.time, java.util and dev.aiverify.contract.
                - Never print or log anything: the order contains client data.
                - Return exactly one ```java code block containing the whole file, then nothing else.
                """;
        StringBuilder user = new StringBuilder();
        user.append("## Specification\n\n").append(spec).append("\n\n## Contract\n\n```java\n")
                .append(contract).append("\n```\n");
        if (feedback != null && !feedback.isBlank()) {
            user.append("\n## Feedback on your previous attempt (from the verification pipeline)\n\n")
                    .append(feedback).append('\n');
        }
        return new Prompts.Prompt(system, user.toString());
    }

    public static Prompts.Prompt testWriter(String spec) {
        String system = """
                You are a QA engineer for wealth management systems. You have NOT seen any implementation.
                From the specification only, write acceptance test cases as data.
                Answer with one JSON object and nothing else:
                {"cases": [{"id": "AI-01", "criterion": "AC-03", "description": "...",
                  "clientRiskLevel": "C3", "fundRiskLevel": "RR2", "kycAssessedOn": "YYYY-MM-DD",
                  "amountMinor": 1000000, "minimumMinor": 300000, "holdingMinor": 0, "portfolioMinor": 50000000,
                  "tradeDate": "YYYY-MM-DD", "expected": "OK"}]}
                Cover every acceptance criterion. Use invented example values only, never real client data.
                """;
        return new Prompts.Prompt(system, "## Specification\n\n" + spec);
    }
}
