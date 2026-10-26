package dev.aiverify.notify;

import java.util.List;
import java.util.Map;

/** The emails the gate sends: a run finished, or a named human decided. */
public final class GateMail {

    private GateMail() {
    }

    /** After a run: BLOCKED tells the developer what failed; READY asks the approver to decide. */
    @SuppressWarnings("unchecked")
    public static Notifier.Message runFinished(Map<String, Object> result) {
        String runId = String.valueOf(result.get("runId"));
        Object attempt = result.get("attempt");
        boolean blocked = "BLOCKED".equals(result.get("verdict"));
        String subject = blocked
                ? "[AI Gate] BLOCKED: " + runId + " (attempt " + attempt + ")"
                : "[AI Gate] Waiting for your approval: " + runId + " (attempt " + attempt + ")";
        StringBuilder b = new StringBuilder();
        b.append(blocked
                ? "閘門擋下了這次執行，程式不會部署。\nThe gate blocked this run. Nothing is deployed.\n\n"
                : "所有自動關卡都通過了，等待具名的人核准或退回。\nAll automated gates passed. A named person must approve or reject it.\n\n");
        b.append("Run:      ").append(runId).append('\n');
        b.append("Attempt:  ").append(attempt).append('\n');
        b.append("Verdict:  ").append(result.get("verdict")).append("\n\n");
        b.append("Gates:\n");
        Object gates = result.get("gates");
        if (gates instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> g = (Map<String, Object>) o;
                b.append(String.format("  %-3s %-28s %-8s %s%n", g.get("id"), g.get("name"), g.get("status"),
                        oneLine(g.get("summary"))));
            }
        }
        b.append('\n');
        if (blocked) {
            b.append("Next step: read the failed gate above and the report. The AI author only receives\n")
                    .append("spec-level feedback, never the hidden golden tests. Then run the next attempt:\n")
                    .append("  ./run.sh run --attempt ").append(next(attempt)).append('\n');
        } else {
            b.append("Next step: read the AI review (advice only) in the report, then decide:\n")
                    .append("  ./run.sh approve --by \"Your Name\" [--note \"...\"]\n")
                    .append("  ./run.sh reject  --by \"Your Name\" --note \"reason\"\n");
        }
        b.append("\nReport: runs/report.html\n");
        return new Notifier.Message(subject, b.toString());
    }

    /** After a human decision. A rejection tells the developer why; its reason is the AI's next feedback. */
    public static Notifier.Message decision(String runId, boolean approve, String by, String note, long acceptedHigh) {
        String subject = (approve ? "[AI Gate] APPROVED by " : "[AI Gate] REJECTED by ") + oneLine(by) + ": " + runId;
        StringBuilder b = new StringBuilder();
        if (approve) {
            b.append("已核准，可以繼續部署。\nApproved. Deployment can continue.\n\n");
        } else {
            b.append("已退回，程式不會部署。\nRejected. Nothing is deployed.\n\n");
        }
        b.append("Run:         ").append(runId).append('\n');
        b.append("Decided by:  ").append(by).append('\n');
        b.append(approve ? "Note:        " : "Reason:      ").append(note.isBlank() ? "(none)" : note).append('\n');
        if (approve && acceptedHigh > 0) {
            b.append("Accepted ").append(acceptedHigh).append(" HIGH AI finding(s); the approver's name is recorded.\n");
        }
        b.append('\n');
        if (!approve) {
            b.append("This reason goes to the AI author as spec-level feedback for the next attempt.\n")
                    .append("If the AI keeps failing, hand the task to a developer: the spec is usually the problem.\n");
        }
        b.append("The decision is recorded in runs/audit-log.jsonl (hash-chained, append-only).\n");
        return new Notifier.Message(subject, b.toString());
    }

    private static String next(Object attempt) {
        return attempt instanceof Number n ? String.valueOf(n.intValue() + 1) : "N";
    }

    private static String oneLine(Object o) {
        return o == null ? "" : o.toString().replace('\r', ' ').replace('\n', ' ');
    }
}
