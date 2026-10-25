package dev.aiverify.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** P1 - Security rules for AI-generated Java code. Each rule names the weakness it prevents. */
final class JavaPolicyRules {

    private static final Pattern LEAK = Pattern.compile("new\\s+\\w*Exception\\s*\\([^;]*\\.getMessage\\(\\)");
    private static final Pattern DANGEROUS = Pattern.compile(
            "Runtime\\.getRuntime\\(\\)\\.exec|\\bProcessBuilder\\b|System\\.exit\\(|setAccessible\\(true\\)");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|secret|apikey|api_key|token)\\s*=\\s*\"[^\"$]{4,}\"|sk-ant-[A-Za-z0-9_-]{8,}");
    private static final Pattern LOG_SENSITIVE = Pattern.compile(
            "(?i)\\blog\\.(info|debug|warn|error|trace)\\(.*\\b(password|pan|cardNumber|cvv|token)\\b");
    private static final Pattern BROAD_CATCH = Pattern.compile("catch\\s*\\(\\s*(Exception|Throwable)\\s+\\w+\\s*\\)");

    static List<Finding> check(Map<Path, String> javaFiles, Path root) {
        List<Finding> out = new ArrayList<>();
        int leaks = 0;
        int dangerous = 0;
        int secrets = 0;
        int logs = 0;
        for (Map.Entry<Path, String> e : javaFiles.entrySet()) {
            String file = root.relativize(e.getKey()).toString();
            String src = e.getValue();
            String[] lines = src.split("\n", -1);
            boolean advice = src.contains("@RestControllerAdvice") || src.contains("@ControllerAdvice");
            for (int k = 0; k < lines.length; k++) {
                String l = lines[k];
                if (l.strip().startsWith("//") || l.strip().startsWith("*")) {
                    continue;
                }
                if (LEAK.matcher(l).find()) {
                    leaks++;
                    out.add(new Finding("J-01", "ERROR", file, k + 1,
                            "Internal error details are sent to API clients",
                            "The exception message from the database or framework is copied into a client-facing "
                                    + "exception, so callers can see internal details (CWE-209).",
                            "Log the cause on the server; return a fixed, user-safe message and an error code."));
                }
                if (DANGEROUS.matcher(l).find()) {
                    dangerous++;
                    out.add(new Finding("J-02", "ERROR", file, k + 1, "Dangerous API in generated code",
                            l.strip(), "Remove it; generated service code never needs process control or reflection."));
                }
                if (SECRET.matcher(l).find()) {
                    secrets++;
                    out.add(new Finding("J-03", "ERROR", file, k + 1, "Hard-coded secret",
                            "A credential-like value is written in the source.", "Read it from a Secret or environment variable."));
                }
                if (LOG_SENSITIVE.matcher(l).find()) {
                    logs++;
                    out.add(new Finding("J-04", "ERROR", file, k + 1, "Sensitive value written to the log",
                            l.strip(), "Never log passwords, tokens or card numbers (PCI DSS)."));
                }
                if (advice && l.contains("@ResponseStatus(HttpStatus.OK)")) {
                    out.add(new Finding("J-05", "WARN", file, k + 1, "Errors are returned with HTTP 200",
                            "An exception handler answers errors with status 200. Gateways, monitoring and "
                                    + "client retries cannot see that the call failed.",
                            "Return 4xx for business errors and 5xx for system errors, keeping the ApiResponse body."));
                }
                Matcher bc = BROAD_CATCH.matcher(l);
                if (bc.find() && file.contains("service")) {
                    out.add(new Finding("J-06", "WARN", file, k + 1, "Catches every exception in the service",
                            "catch (" + bc.group(1) + ") also wraps programming errors, which hides real defects.",
                            "Let unexpected exceptions reach the global handler; catch only the specific ones you handle."));
                }
            }
        }
        if (leaks == 0) {
            out.add(Finding.pass("J-01", null, "No internal error details sent to clients"));
        }
        if (dangerous == 0) {
            out.add(Finding.pass("J-02", null, "No process, exit or reflection APIs"));
        }
        if (secrets == 0) {
            out.add(Finding.pass("J-03", null, "No hard-coded secrets in Java code"));
        }
        if (logs == 0) {
            out.add(Finding.pass("J-04", null, "No sensitive values in log statements"));
        }
        return out;
    }

    private JavaPolicyRules() {
    }
}
