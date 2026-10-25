package dev.aiverify.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** P4 - The delivery pipeline must not deploy AI-generated code that nothing has verified. */
final class PipelineRules {

    static List<Finding> check(String cloudbuildFile, String cloudbuild, String dockerfile, Map<String, String> manifestImages) {
        List<Finding> out = new ArrayList<>();
        if (cloudbuild == null) {
            out.add(new Finding("B-00", "WARN", "cloudbuild.yaml", 0, "No Cloud Build pipeline found",
                    "Pipeline rules were not checked.", null));
            return out;
        }
        List<Object> docs = MiniYaml.parseAll(cloudbuild);
        List<Object> steps = docs.isEmpty() ? List.of() : MiniYaml.list(docs.get(0), "steps");
        int firstVerify = -1;
        int firstDeploy = -1;
        boolean immutableTag = false;
        boolean latestTag = false;
        for (int k = 0; k < steps.size(); k++) {
            String cmd = command(steps.get(k));
            if (firstVerify < 0 && (cmd.matches("(?s).*\\bmvn\\b.*\\b(test|verify)\\b.*")
                    || cmd.matches("(?s).*\\bgradle\\w*\\b.*\\btest\\b.*") || cmd.contains("verify-project"))) {
                firstVerify = k;
            }
            if (firstDeploy < 0 && (cmd.matches("(?s).*kubectl.*\\b(apply|set|rollout)\\b.*")
                    || cmd.contains("gke-deploy") || cmd.matches("(?s).*skaffold.*\\b(run|deploy)\\b.*"))) {
                firstDeploy = k;
            }
            if (cmd.contains("$COMMIT_SHA") || cmd.contains("$SHORT_SHA") || cmd.contains("${COMMIT_SHA}")) {
                immutableTag = true;
            }
            if (cmd.contains(":latest")) {
                latestTag = true;
            }
        }
        if (firstDeploy >= 0 && (firstVerify < 0 || firstVerify > firstDeploy)) {
            out.add(new Finding("B-01", "ERROR", cloudbuildFile, 0, "Deploys without verifying the generated code",
                    "Step " + (firstDeploy + 1) + " deploys to the cluster, but no earlier step runs the tests or the "
                            + "verification gate. AI-generated code reaches the environment unchecked.",
                    "Add a verification step before the docker build: run the tests and the verification gate, "
                            + "and stop the build if either fails."));
        } else if (firstDeploy >= 0) {
            out.add(Finding.pass("B-01", cloudbuildFile, "Tests and verification run before deployment"));
        }
        if (latestTag && !immutableTag) {
            out.add(new Finding("B-02", "ERROR", cloudbuildFile, 0, "Pipeline builds only a mutable :latest image", null,
                    "Tag every image with $COMMIT_SHA."));
        } else if (immutableTag) {
            out.add(Finding.pass("B-02", cloudbuildFile, "Images are tagged with the commit SHA"));
        }
        // B-04: every shared environment must run an image the pipeline actually builds
        java.util.Set<String> pushed = new java.util.TreeSet<>();
        java.util.regex.Matcher pm = java.util.regex.Pattern.compile("([\\w.-]+\\.(?:pkg\\.dev|gcr\\.io)/[\\w./-]+?)(?::[\\w.${}-]+)?(?=\\s|$)")
                .matcher(String.join(" ", steps.stream().map(PipelineRules::command).toList()));
        while (pm.find()) {
            pushed.add(pm.group(1));
        }
        int mismatches = 0;
        if (!pushed.isEmpty()) {
            for (Map.Entry<String, String> e : manifestImages.entrySet()) {
                String repo = e.getValue().contains("@") ? e.getValue().substring(0, e.getValue().indexOf('@'))
                        : e.getValue().replaceAll(":[^/:]+$", "");
                if (!pushed.contains(repo)) {
                    mismatches++;
                    out.add(new Finding("B-04", "ERROR", e.getKey(), 0, "Manifest uses an image the pipeline never builds",
                            "This manifest runs " + repo + ", but Cloud Build pushes only to " + pushed
                                    + ". The deploy would pull a stale image or fail with ImagePullBackOff.",
                            "Point every environment at the repository the pipeline pushes to."));
                }
            }
            if (mismatches == 0 && !manifestImages.isEmpty()) {
                out.add(Finding.pass("B-04", cloudbuildFile, "Every shared environment runs an image the pipeline builds"));
            }
        }
        if (dockerfile != null && dockerfile.contains("-DskipTests") && firstVerify < 0) {
            out.add(new Finding("B-03", "WARN", "Dockerfile", 0, "Tests are skipped in the image build",
                    "The Dockerfile builds with -DskipTests and the pipeline has no separate test step, so the "
                            + "generated tests never run.",
                    "Keep -DskipTests in the Dockerfile only once the pipeline runs mvn verify in its own step."));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String command(Object step) {
        StringBuilder b = new StringBuilder();
        Object name = MiniYaml.get(step, "name");
        Object entry = MiniYaml.get(step, "entrypoint");
        if (name != null) {
            b.append(name).append(' ');
        }
        if (entry != null) {
            b.append(entry).append(' ');
        }
        Object args = MiniYaml.get(step, "args");
        if (args instanceof List<?> l) {
            for (Object a : l) {
                b.append(a).append(' ');
            }
        } else if (args != null) {
            b.append(args);
        }
        Object script = step instanceof Map<?, ?> m ? ((Map<String, Object>) m).get("script") : null;
        if (script != null) {
            b.append(script);
        }
        return b.toString();
    }

    private PipelineRules() {
    }
}
