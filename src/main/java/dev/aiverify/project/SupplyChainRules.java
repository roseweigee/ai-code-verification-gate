package dev.aiverify.project;

import dev.aiverify.json.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P6 - Supply chain. Risks that are specific to AI-written projects or that pass every code check:
 * a dependency the model invented (attackers register such names), unpinned versions, known
 * vulnerabilities in libraries or the container image, and secrets left in Git history.
 */
final class SupplyChainRules {

    private static final Pattern DEPENDENCY = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern GROUP = Pattern.compile("<groupId>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(?<![-\\w])(password|passwd|secret|api[_-]?key|token)\\s*[=:]\\s*['\"]?(?!\\$\\{)[A-Za-z0-9/+_\\-]{8,}|sk-ant-[A-Za-z0-9_-]{8,}|AKIA[0-9A-Z]{16}");

    @SuppressWarnings("unchecked")
    static List<Finding> check(Path gateRoot, Path root, String pom, String cloudbuild) {
        List<Finding> out = new ArrayList<>();
        List<String> approved = List.of();
        try {
            Map<String, Object> cfg = Json.parseObject(Files.readString(gateRoot.resolve("config/approved-dependencies.json")));
            approved = (List<String>) (List<?>) cfg.get("approved");
        } catch (IOException e) {
            out.add(new Finding("D-00", "WARN", "config/approved-dependencies.json", 0, "No approved dependency list",
                    "The dependency allow-list could not be read.", null));
        }

        if (pom != null) {
            int unknown = 0;
            int unpinned = 0;
            int deps = 0;
            String noComments = pom.replaceAll("(?s)<!--.*?-->", "");
            Matcher dm = DEPENDENCY.matcher(noComments);
            while (dm.find()) {
                String block = dm.group(1);
                Matcher g = GROUP.matcher(block);
                Matcher a = ARTIFACT.matcher(block);
                if (!g.find() || !a.find()) {
                    continue;
                }
                deps++;
                String coord = g.group(1) + ":" + a.group(1);
                if (!approved.isEmpty() && approved.stream().noneMatch(p -> matches(p, coord))) {
                    unknown++;
                    out.add(new Finding("D-01", "ERROR", "pom.xml", 0, "Dependency not on the approved list: " + coord,
                            "AI models sometimes invent package names, and attackers register those names with "
                                    + "malicious code. An unlisted dependency may also simply be unvetted.",
                            "Check that the library is real and needed; ask the architect to add it to config/approved-dependencies.json."));
                }
                Matcher v = VERSION.matcher(block);
                if (v.find()) {
                    String ver = v.group(1);
                    if (ver.contains("SNAPSHOT") || ver.startsWith("[") || ver.startsWith("(")
                            || ver.equals("LATEST") || ver.equals("RELEASE")) {
                        unpinned++;
                        out.add(new Finding("D-02", "ERROR", "pom.xml", 0, "Unpinned dependency version: " + coord + " " + ver,
                                "The build can pull different code over time.", "Pin an exact released version."));
                    }
                }
            }
            if (unknown == 0 && deps > 0) {
                out.add(Finding.pass("D-01", "pom.xml", "All " + deps + " dependencies are on the approved list"));
            }
            if (unpinned == 0 && deps > 0) {
                out.add(Finding.pass("D-02", "pom.xml", "No SNAPSHOT, range or LATEST dependency versions"));
            }
        }

        if (cloudbuild != null) {
            boolean depScan = cloudbuild.contains("dependency-check") || cloudbuild.contains("osv-scanner")
                    || cloudbuild.contains("trivy fs") || cloudbuild.contains("snyk");
            boolean imageScan = cloudbuild.contains("trivy") && cloudbuild.contains("image")
                    || cloudbuild.contains("images scan") || cloudbuild.contains("grype");
            out.add(depScan ? Finding.pass("D-03", "cloudbuild.yaml", "Dependencies are scanned for known vulnerabilities")
                    : new Finding("D-03", "WARN", "cloudbuild.yaml", 0, "No dependency vulnerability scan",
                            "Known CVEs in libraries pass every code check.",
                            "Add OWASP Dependency-Check (fail on CVSS 7 or higher) before the image build."));
            out.add(imageScan ? Finding.pass("D-04", "cloudbuild.yaml", "The container image is scanned before deployment")
                    : new Finding("D-04", "WARN", "cloudbuild.yaml", 0, "No container image scan",
                            "The base image can carry vulnerabilities the Java code never shows.",
                            "Scan the pushed image (for example with Trivy) and stop on HIGH or CRITICAL findings."));
        }

        out.addAll(gitHistory(root));
        return out;
    }

    /**
     * D-05: secrets that were committed once stay in Git history even after the file is fixed.
     * A key preceded by '-' is a CLI flag or a resource name (e.g. gcloud --secret=NAME), not a value.
     */
    private static List<Finding> gitHistory(Path root) {
        List<Finding> out = new ArrayList<>();
        if (!Files.exists(root.resolve(".git"))) {
            return out; // not a repository: nothing to scan
        }
        try {
            Process p = new ProcessBuilder("git", "-C", root.toString(), "log", "--all", "-p", "--no-color",
                    "--format=commit %h").redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(60, TimeUnit.SECONDS);
            String commit = null;
            int hits = 0;
            for (String line : log.split("\n")) {
                if (line.startsWith("commit ")) {
                    commit = line.substring(7).strip();
                } else if (line.startsWith("+") && !line.startsWith("+++") && SECRET.matcher(line).find()
                        && !line.matches("(?i).*(change|replace|your|xxx|placeholder|example).*")) {
                    hits++;
                    if (hits <= 5) {
                        out.add(new Finding("D-05", "ERROR", "git history", 0, "Secret added in commit " + commit,
                                "A credential-like value was committed. Removing the file later does not remove it from history.",
                                "Rotate the credential, then remove it from history (for example with git filter-repo)."));
                    }
                }
            }
            if (hits == 0) {
                out.add(Finding.pass("D-05", "git history", "No secrets found in Git history"));
            }
        } catch (IOException | InterruptedException e) {
            out.add(new Finding("D-05", "WARN", "git history", 0, "Git history not scanned", e.getMessage(), null));
        }
        return out;
    }

    private static boolean matches(String pattern, String coord) {
        return pattern.endsWith(":*") ? coord.startsWith(pattern.substring(0, pattern.length() - 1)) : pattern.equals(coord);
    }

    private SupplyChainRules() {
    }
}
