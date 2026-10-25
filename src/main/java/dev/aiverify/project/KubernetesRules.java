package dev.aiverify.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * P3 - Policy for the Kubernetes manifests SpecBlast generates.
 * The environment comes from the folder name (k8s/dev, k8s/sit, k8s/uat, k8s/prod):
 * dev is allowed shortcuts that shared environments are not.
 */
final class KubernetesRules {

    private static final Pattern SENSITIVE_ENV = Pattern.compile("(?i).*(PASSWORD|SECRET|TOKEN|API_?KEY|CREDENTIAL).*");
    private static final Pattern CREDENTIAL_KEY = Pattern.compile("(?i).*(PASSWORD|PASSWD|SECRET|TOKEN|API_?KEY|CREDENTIAL|USERNAME|USER).*");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?i).*(change|replace|your|xxx|todo|<|example|placeholder).*");

    static List<Finding> check(Map<Path, String> yamlFiles, Path root) {
        List<Finding> out = new ArrayList<>();
        int deployments = 0;
        int[] errors = new int[10];
        for (Map.Entry<Path, String> e : yamlFiles.entrySet()) {
            String file = root.relativize(e.getKey()).toString();
            String env = environment(file);
            List<Object> docs;
            try {
                docs = MiniYaml.parseAll(e.getValue());
            } catch (RuntimeException ex) {
                out.add(new Finding("K-00", "WARN", file, 0, "File could not be read as YAML", ex.getMessage(), null));
                continue;
            }
            for (Object doc : docs) {
                String kind = MiniYaml.str(doc, "kind");
                if ("Deployment".equals(kind)) {
                    deployments++;
                    checkDeployment(doc, file, env, out, errors);
                } else if ("Secret".equals(kind)) {
                    checkSecret(doc, file, out, errors);
                }
            }
        }
        if (deployments == 0) {
            out.add(new Finding("K-00", "WARN", "k8s/", 0, "No Deployment manifests found", null, null));
            return out;
        }
        String[][] passes = {
                {"K-01", "Every container has CPU and memory requests and limits"},
                {"K-02", "Every container has liveness and readiness probes"},
                {"K-03", "Shared environments use immutable image tags"},
                {"K-04", "Containers run as non-root"},
                {"K-05", "Sensitive environment variables come from Secrets"},
                {"K-06", "Production runs at least 2 replicas"},
                {"K-07", "Committed Secret files hold placeholders only"}};
        for (int k = 0; k < passes.length; k++) {
            if (errors[k + 1] == 0) {
                out.add(Finding.pass(passes[k][0], null, passes[k][1]));
            }
        }
        return out;
    }

    private static void checkDeployment(Object doc, String file, String env, List<Finding> out, int[] errors) {
        boolean shared = !env.equals("dev");
        Object podSpec = MiniYaml.get(doc, "spec", "template", "spec");
        boolean podNonRoot = "true".equals(MiniYaml.str(podSpec, "securityContext", "runAsNonRoot"));

        if (env.equals("prod")) {
            String r = MiniYaml.str(doc, "spec", "replicas");
            int replicas = r == null ? 1 : Integer.parseInt(r.strip());
            if (replicas < 2) {
                errors[6]++;
                out.add(new Finding("K-06", "ERROR", file, 0, "Production runs a single replica",
                        "replicas is " + replicas + "; one node or pod failure takes the service down.",
                        "Set replicas to at least 2 (or let an HPA with minReplicas >= 2 manage it)."));
            }
        }
        for (Object c : MiniYaml.list(podSpec, "containers")) {
            String name = MiniYaml.str(c, "name");
            String where = "container " + name;
            if (MiniYaml.get(c, "resources", "requests", "cpu") == null || MiniYaml.get(c, "resources", "requests", "memory") == null
                    || MiniYaml.get(c, "resources", "limits", "cpu") == null || MiniYaml.get(c, "resources", "limits", "memory") == null) {
                errors[1]++;
                out.add(new Finding("K-01", "ERROR", file, 0, "Missing resource requests or limits",
                        where + " does not set CPU and memory requests and limits, so one pod can starve its node.",
                        "Set resources.requests and resources.limits for cpu and memory."));
            }
            if (MiniYaml.get(c, "livenessProbe") == null || MiniYaml.get(c, "readinessProbe") == null) {
                errors[2]++;
                out.add(new Finding("K-02", "ERROR", file, 0, "Missing health probes",
                        where + " needs both a liveness and a readiness probe.", "Add probes on /actuator/health."));
            }
            String image = MiniYaml.str(c, "image");
            if (image != null) {
                String tagPart = image.contains("@") ? "digest" : image.substring(image.lastIndexOf('/') + 1);
                boolean mutable = !tagPart.equals("digest") && (!tagPart.contains(":") || tagPart.endsWith(":latest"));
                if (mutable) {
                    if (shared) {
                        errors[3]++;
                    }
                    out.add(new Finding("K-03", shared ? "ERROR" : "WARN", file, 0,
                            "Mutable image tag in " + env,
                            where + " uses " + image + ". The same tag can point to different builds, so you cannot "
                                    + "tell or roll back exactly what is running.",
                            "Deploy the image tagged with the commit SHA (Cloud Build already builds :$COMMIT_SHA)."));
                }
            }
            boolean containerNonRoot = "true".equals(MiniYaml.str(c, "securityContext", "runAsNonRoot"));
            boolean privileged = "true".equals(MiniYaml.str(c, "securityContext", "privileged"));
            if (privileged) {
                errors[4]++;
                out.add(new Finding("K-04", "ERROR", file, 0, "Privileged container", where + " runs privileged.",
                        "Remove privileged: true."));
            } else if (!podNonRoot && !containerNonRoot) {
                errors[4]++;
                out.add(new Finding("K-04", shared ? "WARN" : "WARN", file, 0, "Non-root is not enforced by Kubernetes",
                        where + " has no securityContext.runAsNonRoot. The Dockerfile switches to a non-root user, "
                                + "but the cluster does not enforce it if the image changes.",
                        "Add securityContext: runAsNonRoot: true and allowPrivilegeEscalation: false."));
            }
            for (Object ev : MiniYaml.list(c, "env")) {
                String evName = MiniYaml.str(ev, "name");
                if (evName != null && SENSITIVE_ENV.matcher(evName).matches() && MiniYaml.get(ev, "value") != null) {
                    errors[5]++;
                    out.add(new Finding("K-05", "ERROR", file, 0, "Secret written as a plain environment value",
                            evName + " in " + where + " is set with value: instead of a Secret.",
                            "Use valueFrom.secretKeyRef."));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkSecret(Object doc, String file, List<Finding> out, int[] errors) {
        int real = 0;
        int total = 0;
        for (String section : new String[] {"stringData", "data"}) {
            Object values = MiniYaml.get(doc, section);
            if (values instanceof Map<?, ?> m) {
                for (Map.Entry<String, Object> kv : ((Map<String, Object>) m).entrySet()) {
                    if (!CREDENTIAL_KEY.matcher(kv.getKey()).matches()) {
                        continue; // e.g. DB_NAME is configuration, not a credential
                    }
                    total++;
                    Object v = kv.getValue();
                    String s = v == null ? "" : v.toString();
                    if (!s.isBlank() && !PLACEHOLDER.matcher(s).matches()) {
                        real++;
                    }
                }
            }
        }
        if (real > 0) {
            errors[7]++;
            out.add(new Finding("K-07", "ERROR", file, 0, "Real secret values committed to the repository",
                    real + " of " + total + " credential values do not look like placeholders.",
                    "Remove them from Git, rotate them, and use Secret Manager or External Secrets."));
        } else if (total > 0) {
            out.add(new Finding("K-07", "WARN", file, 0, "Secret manifest kept in the repository",
                    "All " + total + " credential values are placeholders, which is safe. Someone may still fill them in and commit.",
                    "Create the Secret from Secret Manager in the pipeline instead of keeping the file in Git."));
        }
    }

    /** file -> image, for Deployments outside dev (dev images are built locally by skaffold). */
    static Map<String, String> sharedImages(Map<Path, String> yamlFiles, Path root) {
        Map<String, String> out = new java.util.TreeMap<>();
        for (Map.Entry<Path, String> e : yamlFiles.entrySet()) {
            String file = root.relativize(e.getKey()).toString();
            if (environment(file).equals("dev")) {
                continue;
            }
            try {
                for (Object doc : MiniYaml.parseAll(e.getValue())) {
                    if ("Deployment".equals(MiniYaml.str(doc, "kind"))) {
                        for (Object c : MiniYaml.list(doc, "spec", "template", "spec", "containers")) {
                            String image = MiniYaml.str(c, "image");
                            if (image != null) {
                                out.put(file, image);
                            }
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // reported by check()
            }
        }
        return out;
    }

    private static String environment(String file) {
        for (String env : new String[] {"prod", "uat", "sit", "dev"}) {
            if (file.contains("/" + env + "/") || file.contains("-" + env + ".") || file.contains("/" + env + "-")) {
                return env;
            }
        }
        return "shared";
    }

    private KubernetesRules() {
    }
}
