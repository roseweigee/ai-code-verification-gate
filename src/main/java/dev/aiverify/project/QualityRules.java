package dev.aiverify.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P5 - Tests and code quality.
 *
 * Generated Mockito tests are useful, but they were written by the same AI as the code and can
 * share its blind spots. So the gate checks that they exist and test failure paths, that coverage
 * is enforced by the build (JaCoCo), and that a SonarQube quality gate runs before deployment.
 */
final class QualityRules {

    private static final Pattern TYPE = Pattern.compile("(?m)^\\s*public\\s+(?:final\\s+)?class\\s+(\\w+)");
    private static final Pattern FAILURE_PATH = Pattern.compile(
            "thenThrow|assertThrows|assertThatThrownBy|isInternalServerError|is4xxClientError|is5xxServerError|isBadRequest");

    static List<Finding> check(Map<Path, String> javaFiles, Path root, String pom, String cloudbuild) {
        List<Finding> out = new ArrayList<>();
        List<String> testFiles = new ArrayList<>();
        StringBuilder allTests = new StringBuilder();
        for (Map.Entry<Path, String> e : javaFiles.entrySet()) {
            String rel = root.relativize(e.getKey()).toString();
            if (rel.contains("src/test/")) {
                testFiles.add(e.getKey().getFileName().toString().replace(".java", ""));
                allTests.append(e.getValue()).append('\n');
            }
        }
        int tests = count(allTests.toString(), "@Test");
        boolean mockito = allTests.indexOf("@Mock") >= 0 || allTests.indexOf("Mockito") >= 0;

        // T-01: every controller and service implementation has a test class
        int missing = 0;
        for (Map.Entry<Path, String> e : javaFiles.entrySet()) {
            String rel = root.relativize(e.getKey()).toString();
            if (rel.contains("src/test/")) {
                continue;
            }
            Matcher m = TYPE.matcher(e.getValue());
            if (!m.find()) {
                continue;
            }
            String type = m.group(1);
            if ((type.endsWith("Controller") || type.endsWith("ServiceImpl")) && !testFiles.contains(type + "Test")) {
                missing++;
                out.add(new Finding("T-01", "ERROR", rel, 0, "No unit test for " + type,
                        "Controllers and service implementations must ship with a test class.",
                        "Add " + type + "Test (Mockito for services, MockMvc for controllers)."));
            }
        }
        if (missing == 0) {
            out.add(Finding.pass("T-01", null, "Every controller and service has a test class ("
                    + tests + " tests" + (mockito ? ", Mockito" : "") + ")"));
        }

        // T-02: at least one test exercises a failure path
        if (tests > 0 && !FAILURE_PATH.matcher(allTests).find()) {
            out.add(new Finding("T-02", "WARN", "src/test", 0, "Only happy-path tests",
                    "No test makes a dependency fail or checks an error response.",
                    "Add a test where the repository throws, and assert the HTTP status and the message."));
        } else if (tests > 0) {
            out.add(Finding.pass("T-02", null, "Tests cover at least one failure path"));
        }

        // Q-01: coverage is enforced by the build, not just reported
        boolean jacoco = pom != null && pom.contains("jacoco-maven-plugin");
        boolean jacocoCheck = jacoco && pom.contains("<goal>check</goal>") && pom.contains("<minimum>");
        if (!jacocoCheck) {
            out.add(new Finding("Q-01", "WARN", "pom.xml", 0,
                    jacoco ? "Coverage is measured but not enforced" : "No coverage threshold",
                    "Without a JaCoCo check goal, mvn verify passes even if the tests cover almost nothing.",
                    "Add jacoco-maven-plugin with prepare-agent, report and a check goal with a minimum line coverage."));
        } else {
            out.add(Finding.pass("Q-01", "pom.xml", "JaCoCo enforces a minimum coverage in mvn verify"));
        }

        // Q-02: a SonarQube quality gate runs in the pipeline and waits for its result
        boolean sonar = cloudbuild != null && (cloudbuild.contains("sonar:sonar") || cloudbuild.contains("sonar-scanner"));
        boolean waits = sonar && cloudbuild.contains("sonar.qualitygate.wait=true");
        if (!sonar) {
            out.add(new Finding("Q-02", "WARN", "cloudbuild.yaml", 0, "No SonarQube quality gate",
                    "No static analysis for bugs, vulnerabilities and code smells runs before deployment.",
                    "Run mvn sonar:sonar with -Dsonar.qualitygate.wait=true before the image build."));
        } else if (!waits) {
            out.add(new Finding("Q-02", "WARN", "cloudbuild.yaml", 0, "SonarQube runs but does not block",
                    "Without sonar.qualitygate.wait=true the build continues even when the quality gate fails.",
                    "Add -Dsonar.qualitygate.wait=true."));
        } else {
            out.add(Finding.pass("Q-02", "cloudbuild.yaml", "SonarQube quality gate runs and blocks on failure"));
        }
        return out;
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private QualityRules() {
    }
}
