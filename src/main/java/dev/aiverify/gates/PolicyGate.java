package dev.aiverify.gates;

import dev.aiverify.pipeline.Gate;
import dev.aiverify.pipeline.GateResult;
import dev.aiverify.pipeline.RunContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gate 1 - Policy guard (Responsible AI / PCI rules), checked before AI code is even compiled.
 * Pure pattern rules: fast, explainable, and the same answer every time.
 */
public final class PolicyGate implements Gate {

    private record Rule(String id, String title, Pattern pattern, boolean onCodeOnly) {
    }

    private static final List<Rule> FORBIDDEN = List.of(
            new Rule("NF-02", "File system access", Pattern.compile("\\bjava\\.(io|nio)\\b|\\bFiles\\."), true),
            new Rule("NF-02", "Network access", Pattern.compile("\\bjava\\.net\\b|\\bHttpClient\\b|\\bSocket\\b"), true),
            new Rule("NF-02", "Process execution", Pattern.compile("Runtime\\.getRuntime|\\bProcessBuilder\\b"), true),
            new Rule("NF-02", "Reflection", Pattern.compile("java\\.lang\\.reflect|setAccessible|Class\\.forName"), true),
            new Rule("NF-02", "System.exit", Pattern.compile("System\\.exit"), true),
            new Rule("NF-04", "Hard-coded secret",
                    Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*=\\s*\"[^\"]+\"|sk-ant-"), false));

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.*]+)\\s*;");
    private static final List<String> ALLOWED_IMPORTS = List.of("java.time.", "java.util.", "dev.aiverify.contract.");
    private static final Pattern OUTPUT = Pattern.compile("System\\.(out|err)|printStackTrace|\\blog(ger)?\\.\\w+\\(",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAN_LITERAL = Pattern.compile("\"(\\d{13,19})\"");

    @Override
    public String id() {
        return "G1";
    }

    @Override
    public String name() {
        return "Policy guard";
    }

    @Override
    public String decidedBy() {
        return "deterministic";
    }

    @Override
    public GateResult run(RunContext ctx) {
        long t0 = System.currentTimeMillis();
        String src = ctx.candidateSource;
        String code = stripCommentsAndStrings(src);
        List<Map<String, Object>> violations = new ArrayList<>();
        List<Map<String, Object>> notes = new ArrayList<>();

        for (Rule r : FORBIDDEN) {
            Matcher m = r.pattern.matcher(r.onCodeOnly ? code : src);
            while (m.find()) {
                violations.add(finding(r.id, r.title, lineOf(src, m.start()), m.group()));
            }
        }

        Matcher im = IMPORT.matcher(code);
        while (im.find()) {
            String imp = im.group(2);
            if (ALLOWED_IMPORTS.stream().noneMatch(imp::startsWith)) {
                violations.add(finding("NF-03", "Import not on the allow-list", lineOf(src, im.start()), imp));
            }
        }

        String[] lines = code.split("\n", -1);
        for (int k = 0; k < lines.length; k++) {
            if (OUTPUT.matcher(lines[k]).find()) {
                if (lines[k].toLowerCase().contains("pan")) {
                    violations.add(finding("NF-01", "PAN written to console or log", k + 1, lines[k].strip()));
                } else {
                    notes.add(finding("NF-01", "Console or log output (no PAN detected)", k + 1, lines[k].strip()));
                }
            }
        }

        Matcher pm = PAN_LITERAL.matcher(src);
        while (pm.find()) {
            if (luhn(pm.group(1))) {
                violations.add(finding("NF-01", "Hard-coded card number", lineOf(src, pm.start()), "\"****\""));
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rulesChecked", List.of("NF-01 no PAN in logs / no hard-coded card numbers",
                "NF-02 no file, network, process, reflection or System.exit",
                "NF-03 imports only from java.time, java.util, dev.aiverify.contract",
                "NF-04 no hard-coded secrets"));
        details.put("violations", violations);
        details.put("notes", notes);
        long ms = System.currentTimeMillis() - t0;
        if (!violations.isEmpty()) {
            return new GateResult(id(), name(), GateResult.Status.FAILED, decidedBy(),
                    violations.size() + " policy violation(s). The code is not compiled.", details, ms);
        }
        return new GateResult(id(), name(), GateResult.Status.PASSED, decidedBy(),
                "No policy violations (4 rule groups).", details, ms);
    }

    private static Map<String, Object> finding(String rule, String title, int line, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        m.put("title", title);
        m.put("line", line);
        m.put("evidence", evidence);
        return m;
    }

    private static int lineOf(String s, int pos) {
        int line = 1;
        for (int k = 0; k < pos && k < s.length(); k++) {
            if (s.charAt(k) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Blanks out comments and string contents, keeping line breaks so line numbers stay correct. */
    static String stripCommentsAndStrings(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (int k = i; k < end; k++) {
                    b.append(s.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else if (c == '"') {
                b.append('"');
                i++;
                while (i < s.length() && s.charAt(i) != '"' && s.charAt(i) != '\n') {
                    if (s.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                    b.append(' ');
                }
                b.append('"');
                i++;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    public static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int k = digits.length() - 1; k >= 0; k--) {
            int d = digits.charAt(k) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }
}
