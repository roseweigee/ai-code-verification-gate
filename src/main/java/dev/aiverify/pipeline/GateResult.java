package dev.aiverify.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outcome of one gate.
 *
 * @param decidedBy who is allowed to decide this gate: "deterministic", "AI (advisory)" or "human".
 *                  AI-decided gates can raise findings but can never pass or fail the run on their own.
 */
public record GateResult(String id, String name, Status status, String decidedBy, String summary,
                         Map<String, Object> details, long durationMs) {

    public enum Status { PASSED, FAILED, WARNING, SKIPPED, PENDING }

    public static GateResult skipped(String id, String name, String decidedBy, String reason) {
        return new GateResult(id, name, Status.SKIPPED, decidedBy, reason, new LinkedHashMap<>(), 0);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("status", status.name());
        m.put("decidedBy", decidedBy);
        m.put("summary", summary);
        m.put("durationMs", durationMs);
        m.put("details", details);
        return m;
    }
}
