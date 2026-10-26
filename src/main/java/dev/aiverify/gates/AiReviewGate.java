package dev.aiverify.gates;

import dev.aiverify.ai.Prompts;
import dev.aiverify.json.Json;
import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate 5 - Independent AI review, in a fresh context that never saw the author's conversation.
 *
 * The reviewer is ADVISORY: it can raise findings, but it cannot pass or fail the run.
 * HIGH findings must be explicitly acknowledged by the human approver.
 */
public final class AiReviewGate implements Gate {

    @Override
    public String id() {
        return "G5";
    }

    @Override
    public String name() {
        return "Independent AI review";
    }

    @Override
    public String decidedBy() {
        return "AI (advisory)";
    }

    @Override
    @SuppressWarnings("unchecked")
    public GateResult run(RunContext ctx) {
        long t0 = System.currentTimeMillis();
        Prompts.Prompt p = Prompts.reviewer(ctx.spec, ctx.candidateSource);
        String answer = ctx.askAi("reviewer", p.system(), p.user());

        Map<String, Object> details = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        try {
            Map<String, Object> parsed = (Map<String, Object>) Json.parseEmbedded(answer);
            details.put("reviewSummary", parsed.get("summary"));
            if (Boolean.TRUE.equals(parsed.get("skipped"))) {
                details.put("findings", findings);
                details.put("highFindings", 0L);
                return new GateResult(id(), name(), GateResult.Status.SKIPPED, decidedBy(),
                        String.valueOf(parsed.get("summary")), details, System.currentTimeMillis() - t0);
            }
            Object f = parsed.get("findings");
            if (f instanceof List<?> l) {
                for (Object o : l) {
                    findings.add((Map<String, Object>) o);
                }
            }
        } catch (RuntimeException e) {
            // An unreadable review is itself a finding: a human must look.
            findings.add(Map.of("severity", "MEDIUM", "criterion", "GENERAL", "line", 0,
                    "title", "Reviewer answer could not be read", "detail", e.getMessage()));
        }
        long high = findings.stream().filter(x -> "HIGH".equals(x.get("severity"))).count();
        details.put("findings", findings);
        details.put("highFindings", high);
        details.put("policy", "Advisory only. HIGH findings must be acknowledged by the human approver.");

        long ms = System.currentTimeMillis() - t0;
        if (findings.isEmpty()) {
            return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(),
                    "No findings.", details, ms);
        }
        String summary = findings.size() + " finding(s): " + high + " HIGH. Shown to the human approver.";
        return new GateResult(id(), name(), GateResult.Status.WARNING, decidedBy(), summary, details, ms);
    }
}
