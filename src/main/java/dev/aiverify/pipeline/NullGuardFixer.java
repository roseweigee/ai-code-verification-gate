package dev.aiverify.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic auto-fix for rule NF-05 (no input may make check() throw).
 *
 * <p>It adds one guard at the top of every {@code public String check(...)} method: if a parameter of a type that
 * crashed when null (from the NF-05 probes) is null, return "30" (format error, as AC-01 does for a null PAN or expiry). It is a fixed, reviewable rewrite,
 * not an AI suggestion: the same input always gives the same change, the change is shown as a diff, every gate
 * runs again on the fixed code, and a named human still approves it.
 */
public final class NullGuardFixer {

    private static final Set<String> PRIMITIVES = Set.of("byte", "short", "int", "long", "float", "double",
            "boolean", "char");
    private static final Pattern CHECK = Pattern.compile(
            "(?m)^([ \\t]*)public\\s+String\\s+check\\s*\\(([^)]*)\\)\\s*(?:throws[^{]*)?\\{[ \\t]*\\n");
    public static final String MARK = "// NF-05 auto-fix by the gate: a null input is a format error (30), never a crash";

    private NullGuardFixer() {
    }

    /** The fixed source, or null when there is nothing this rule can fix. */
    public static String fix(String source) {
        return fix(source, null);
    }

    /** @param types parameter types to guard (simple names, e.g. LocalDate); null guards every object parameter */
    public static String fix(String source, Set<String> types) {
        if (source.contains(MARK)) {
            return null;
        }
        Matcher m = CHECK.matcher(source);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean changed = false;
        while (m.find()) {
            List<String> objects = new ArrayList<>();
            for (String param : m.group(2).split(",")) {
                String[] parts = param.trim().replaceAll("\\bfinal\\s+", "").split("\\s+");
                String type = parts.length >= 2 ? parts[parts.length - 2] : "";
                if (parts.length >= 2 && !PRIMITIVES.contains(type)
                        && (types == null || types.contains(type.substring(type.lastIndexOf('.') + 1)))) {
                    objects.add(parts[parts.length - 1]);
                }
            }
            out.append(source, last, m.end());
            if (!objects.isEmpty()) {
                String indent = m.group(1) + "    ";
                StringBuilder cond = new StringBuilder();
                for (String o : objects) {
                    cond.append(cond.length() == 0 ? "" : " || ").append(o).append(" == null");
                }
                out.append(indent).append(MARK).append('\n')
                        .append(indent).append("if (").append(cond).append(") {\n")
                        .append(indent).append("    return \"30\";\n")
                        .append(indent).append("}\n");
                changed = true;
            }
            last = m.end();
        }
        out.append(source.substring(last));
        return changed ? out.toString() : null;
    }

    /** Lines added by the fix, with their line numbers in the fixed source, for the report. */
    public static List<Map<String, Object>> addedLines(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        List<Map<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (int j = 0; j < b.length; j++) {
            if (i < a.length && a[i].equals(b[j])) {
                i++;
            } else {
                out.add(Map.of("n", j + 1, "text", b[j]));
            }
        }
        return out;
    }
}
