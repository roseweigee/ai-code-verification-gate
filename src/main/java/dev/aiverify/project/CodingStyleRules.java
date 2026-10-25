package dev.aiverify.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P2 - SpecBlast's own CodingStyle.md, checked as a contract.
 *
 * SpecBlast gives CodingStyle.md to the model as instructions. Here the same file becomes
 * deterministic rules, so "the AI was told" turns into "the AI was checked".
 */
final class CodingStyleRules {

    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE_DECL = Pattern.compile(
            "(?m)^\\s*public\\s+(?:abstract\\s+|final\\s+)?(class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern ALLOWED_API = Pattern.compile("`ApiResponse\\.(\\w+)\\(([^)]*)\\)`");
    private static final Pattern BIZ_CTOR = Pattern.compile("`new BusinessException\\(([^)]*)\\)`");

    static List<Finding> check(String style, Map<Path, String> javaFiles, Path root) {
        List<Finding> out = new ArrayList<>();
        if (style == null) {
            out.add(new Finding("C-00", "WARN", "CodingStyle.md", 0, "No CodingStyle.md found",
                    "The style contract could not be checked.", "Generate CodingStyle.md with the project."));
            return out;
        }

        String base = sectionFirstLine(style, "## Base Package");
        Set<String> allowedPackages = new LinkedHashSet<>();
        Matcher pk = Pattern.compile("(?m)^-\\s+(" + Pattern.quote(base == null ? "com" : base) + "[\\w.]*)\\s").matcher(style);
        while (pk.find()) {
            allowedPackages.add(pk.group(1));
        }
        Map<String, Integer> apiArity = new LinkedHashMap<>();
        Matcher am = ALLOWED_API.matcher(style);
        while (am.find()) {
            apiArity.merge(am.group(1) + "/" + arity(am.group(2)), 1, Integer::sum);
        }
        Matcher bm = BIZ_CTOR.matcher(style);
        Integer bizArity = bm.find() ? arity(bm.group(1)) : null;
        boolean noTryInController = style.contains("Controller 不需要 try-catch");
        boolean readOnlyQueries = style.contains("@Transactional(readOnly = true)");

        int packageViolations = 0;
        int namingViolations = 0;
        int apiViolations = 0;
        int ctorViolations = 0;
        int tryViolations = 0;
        int txViolations = 0;
        for (Map.Entry<Path, String> e : javaFiles.entrySet()) {
            String file = root.relativize(e.getKey()).toString();
            if (file.contains("src/test/")) {
                continue;
            }
            String src = e.getValue();
            Matcher pd = PACKAGE_DECL.matcher(src);
            String pkg = pd.find() ? pd.group(1) : "";
            Matcher td = TYPE_DECL.matcher(src);
            String type = td.find() ? td.group(2) : null;

            if (base != null && !pkg.equals(base) && !allowedPackages.contains(pkg)) {
                packageViolations++;
                out.add(new Finding("C-01", "ERROR", file, 1, "Package not in the agreed structure",
                        "Package " + pkg + " is not listed in CodingStyle.md.", "Move the class to one of " + allowedPackages + "."));
            }
            String naming = namingProblem(pkg, type, src);
            if (naming != null) {
                namingViolations++;
                out.add(new Finding("C-02", "ERROR", file, 1, "Class name does not follow the naming rule", naming,
                        "Rename the class to match its layer."));
            }

            String[] lines = src.split("\n", -1);
            for (int k = 0; k < lines.length; k++) {
                String l = lines[k];
                Matcher call = Pattern.compile("ApiResponse\\.(\\w+)\\(").matcher(l);
                while (!apiArity.isEmpty() && call.find()) {
                    int n = arity(argsFrom(l, call.end()));
                    if (!apiArity.containsKey(call.group(1) + "/" + n)) {
                        apiViolations++;
                        out.add(new Finding("C-03", "ERROR", file, k + 1, "ApiResponse method that does not exist",
                                "ApiResponse." + call.group(1) + " with " + n + " argument(s) is not in the allowed list.",
                                "Use only " + apiArity.keySet() + " (name/arguments)."));
                    }
                }
                Matcher nb = Pattern.compile("new BusinessException\\(").matcher(l);
                while (bizArity != null && nb.find()) {
                    int n = arity(argsFrom(l, nb.end()));
                    if (n != bizArity) {
                        ctorViolations++;
                        out.add(new Finding("C-04", "ERROR", file, k + 1, "BusinessException built with the wrong arguments",
                                "Called with " + n + " argument(s); CodingStyle.md allows " + bizArity + ".",
                                "Use new BusinessException(String message)."));
                    }
                }
                if (noTryInController && pkg.endsWith(".controller") && l.contains("try {")) {
                    tryViolations++;
                    out.add(new Finding("C-05", "ERROR", file, k + 1, "try-catch inside a controller",
                            "CodingStyle.md says the global handler deals with exceptions.", "Remove the try-catch."));
                }
            }
            if (readOnlyQueries && pkg.endsWith(".service.impl")) {
                Matcher q = Pattern.compile("(?m)^(?:\\s*@\\w+(?:\\([^)]*\\))?\\s*\\n)*\\s*public\\s+[\\w<>, ?]+\\s+((find|get|query|list|search)\\w*)\\s*\\(").matcher(src);
                while (q.find()) {
                    String head = q.group(0);
                    if (!head.contains("@Transactional(readOnly = true)")) {
                        txViolations++;
                        out.add(new Finding("C-06", "ERROR", file, lineOf(src, q.start(1)), "Query method is not read-only",
                                q.group(1) + " has no @Transactional(readOnly = true).", "Add @Transactional(readOnly = true)."));
                    }
                }
            }
        }
        if (packageViolations == 0) {
            out.add(Finding.pass("C-01", null, "Every class is in an agreed package"));
        }
        if (namingViolations == 0) {
            out.add(Finding.pass("C-02", null, "Every class follows the naming rule of its layer"));
        }
        if (apiViolations == 0) {
            out.add(Finding.pass("C-03", null, "Only the allowed ApiResponse methods are used"));
        }
        if (ctorViolations == 0) {
            out.add(Finding.pass("C-04", null, "BusinessException is built as specified"));
        }
        if (tryViolations == 0) {
            out.add(Finding.pass("C-05", null, "No try-catch in controllers"));
        }
        if (txViolations == 0) {
            out.add(Finding.pass("C-06", null, "Query methods are read-only transactions"));
        }
        return out;
    }

    private static String namingProblem(String pkg, String type, String src) {
        if (type == null) {
            return null;
        }
        boolean isInterface = src.contains("interface " + type);
        if (pkg.endsWith(".controller") && !type.endsWith("Controller")) {
            return type + " is in the controller package but does not end with Controller.";
        }
        if (pkg.endsWith(".service.impl") && !type.endsWith("ServiceImpl")) {
            return type + " is in service.impl but does not end with ServiceImpl.";
        }
        if (pkg.endsWith(".service") && isInterface && !type.endsWith("Service")) {
            return type + " is a service interface but does not end with Service.";
        }
        if (pkg.endsWith(".repository") && !type.endsWith("Repository")) {
            return type + " is in the repository package but does not end with Repository.";
        }
        if (pkg.endsWith(".dto") && !type.endsWith("Request") && !type.endsWith("Response")) {
            return type + " is a DTO but does not end with Request or Response.";
        }
        return null;
    }

    private static String sectionFirstLine(String md, String heading) {
        int at = md.indexOf(heading);
        if (at < 0) {
            return null;
        }
        for (String l : md.substring(at + heading.length()).split("\n")) {
            if (!l.isBlank()) {
                return l.strip();
            }
        }
        return null;
    }

    /** The argument text of a call whose '(' ends right before {@code from}. */
    private static String argsFrom(String line, int from) {
        int depth = 1;
        for (int k = from; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return line.substring(from, k);
            }
        }
        return line.substring(from); // call continues on the next line; count what we can see
    }

    private static int arity(String args) {
        if (args.isBlank()) {
            return 0;
        }
        int depth = 0;
        int n = 1;
        boolean inStr = false;
        for (char c : args.toCharArray()) {
            if (c == '"') {
                inStr = !inStr;
            } else if (!inStr && (c == '(' || c == '<')) {
                depth++;
            } else if (!inStr && (c == ')' || c == '>')) {
                depth--;
            } else if (!inStr && c == ',' && depth == 0) {
                n++;
            }
        }
        return n;
    }

    private static int lineOf(String s, int pos) {
        int line = 1;
        for (int k = 0; k < pos; k++) {
            if (s.charAt(k) == '\n') {
                line++;
            }
        }
        return line;
    }

    private CodingStyleRules() {
    }
}
