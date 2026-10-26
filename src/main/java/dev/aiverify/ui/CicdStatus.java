package dev.aiverify.ui;

import dev.aiverify.json.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the GitHub Actions CI/CD pipeline for the ISO 8583 service: the last runs, each job's result,
 * and the URL of what is live in production. Read-only, public GitHub REST API.
 * Set GITHUB_TOKEN for a higher rate limit. Results are cached for 60 seconds.
 */
final class CicdStatus {

    static final String REPO = "roseweigee/ai-code-verification-gate";
    static final String WORKFLOW = "iso8583-cicd.yml";
    private static final String API = "https://api.github.com/repos/" + REPO;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Map<String, Object> cached;
    private long cachedAt;

    synchronized Map<String, Object> get(boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && cached != null && now - cachedAt < 60_000) {
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", REPO);
        out.put("actionsUrl", "https://github.com/" + REPO + "/actions/workflows/" + WORKFLOW);
        try {
            Map<String, Object> runs = obj(fetch(API + "/actions/workflows/" + WORKFLOW + "/runs?per_page=5"));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Object o : arr(runs.get("workflow_runs"))) {
                Map<String, Object> r = obj(o);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.get("id"));
                row.put("status", r.get("status"));
                row.put("conclusion", r.get("conclusion"));
                row.put("event", r.get("event"));
                row.put("createdAt", r.get("created_at"));
                row.put("url", r.get("html_url"));
                String sha = String.valueOf(r.get("head_sha"));
                row.put("sha", sha.length() > 7 ? sha.substring(0, 7) : sha);
                Map<String, Object> commit = obj(r.get("head_commit"));
                String msg = String.valueOf(commit.getOrDefault("message", ""));
                row.put("message", msg.contains("\n") ? msg.substring(0, msg.indexOf('\n')) : msg);
                list.add(row);
            }
            // jobs for the latest 3 runs only, to stay within the public rate limit
            for (int i = 0; i < Math.min(3, list.size()); i++) {
                Map<String, Object> jobs = obj(fetch(API + "/actions/runs/" + list.get(i).get("id") + "/jobs"));
                List<Map<String, Object>> js = new ArrayList<>();
                for (Object o : arr(jobs.get("jobs"))) {
                    Map<String, Object> j = obj(o);
                    js.add(Map.of("name", String.valueOf(j.get("name")), "status", String.valueOf(j.get("status")),
                            "conclusion", String.valueOf(j.get("conclusion"))));
                }
                list.get(i).put("jobs", js);
            }
            out.put("runs", list);
            List<Object> deps = arr(Json.parse(fetch(API + "/deployments?environment=production&per_page=1")));
            if (!deps.isEmpty()) {
                Map<String, Object> d = obj(deps.get(0));
                List<Object> sts = arr(Json.parse(fetch(String.valueOf(d.get("statuses_url")) + "?per_page=1")));
                Map<String, Object> live = new LinkedHashMap<>();
                String sha = String.valueOf(d.get("sha"));
                live.put("sha", sha.length() > 7 ? sha.substring(0, 7) : sha);
                live.put("createdAt", d.get("created_at"));
                if (!sts.isEmpty()) {
                    Map<String, Object> s = obj(sts.get(0));
                    live.put("state", s.get("state"));
                    live.put("url", s.get("environment_url"));
                }
                out.put("production", live);
            }
        } catch (Exception e) {
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        cached = out;
        cachedAt = now;
        return out;
    }

    private String fetch(String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "ai-code-verification-gate");
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IllegalStateException("GitHub API " + r.statusCode() + (r.statusCode() == 403 ? " (rate limit: set GITHUB_TOKEN)" : ""));
        }
        return r.body();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }
}
