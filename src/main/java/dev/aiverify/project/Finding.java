package dev.aiverify.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One rule result for a generated project.
 *
 * @param severity ERROR blocks the deployment, WARN is shown to the approver, PASS records a rule that was met
 */
public record Finding(String rule, String severity, String file, int line, String title, String detail, String fix) {

    public static Finding pass(String rule, String file, String title) {
        return new Finding(rule, "PASS", file, 0, title, null, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("severity", severity);
        m.put("file", file);
        m.put("line", line);
        m.put("title", title);
        m.put("detail", detail);
        m.put("fix", fix);
        return m;
    }
}
