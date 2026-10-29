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
public final class CicdStatus {

    public static final String REPO = "roseweigee/ai-code-verification-gate";
    static final String WORKFLOW = "release.yml";
    private static final String API = "https://api.github.com/repos/" + REPO;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Map<String, Object> cached;
    private long cachedAt;

    synchronized Map<String, Object> get(boolean refresh) {
        long now = System.currentTimeMillis();
        // even "refresh" is limited: at most one GitHub read per 15 s (60/hour without a token)
        if (cached != null && now - cachedAt < (refresh ? 5_000 : 60_000)) {
            return cached;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repo", REPO);
        out.put("fetchedAt", java.time.LocalTime.now().withNano(0).toString());
        out.put("actionsUrl", "https://github.com/" + REPO + "/actions/workflows/" + WORKFLOW);
        try {
            Map<String, Object> runs = obj(Json.parse(fetch(API + "/actions/workflows/" + WORKFLOW + "/runs?per_page=5")));
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
                if ("workflow_dispatch".equals(r.get("event")) && r.get("display_title") != null) {
                    msg = String.valueOf(r.get("display_title")); // a cloud-demo approval names its run here
                }
                row.put("message", msg.contains("\n") ? msg.substring(0, msg.indexOf('\n')) : msg);
                list.add(row);
            }
            // jobs for the latest 2 runs only, to stay within the public rate limit
            for (int i = 0; i < Math.min(2, list.size()); i++) {
                Map<String, Object> jobs = obj(Json.parse(fetch(API + "/actions/runs/" + list.get(i).get("id") + "/jobs")));
                List<Map<String, Object>> js = new ArrayList<>();
                for (Object o : arr(jobs.get("jobs"))) {
                    Map<String, Object> j = obj(o);
                    js.add(Map.of("name", String.valueOf(j.get("name")), "status", String.valueOf(j.get("status")),
                            "conclusion", String.valueOf(j.get("conclusion"))));
                }
                list.get(i).put("jobs", js);
            }
            if (!list.isEmpty()) {
                try {
                    list.get(0).putAll(details(list.get(0).get("id"), out));
                } catch (Exception e) {
                    out.put("drillError", "release: " + e.getMessage());
                }
            }
            out.put("runs", list);
            List<Map<String, Object>> drills = new ArrayList<>();
            try {
            for (Object o : arr(obj(Json.parse(fetch(API + "/actions/workflows/canary.yml/runs?per_page=3"))).get("workflow_runs"))) {
                Map<String, Object> r = obj(o);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("status", r.get("status"));
                row.put("conclusion", r.get("conclusion"));
                row.put("createdAt", r.get("created_at"));
                row.put("url", r.get("html_url"));
                row.put("id", r.get("id"));
                drills.add(row);
            }
            if (!drills.isEmpty()) {
                drills.get(0).putAll(details(drills.get(0).get("id"), out));
            }
            } catch (Exception e) {
                out.put("drillError", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            out.put("canary", drills);
            out.put("canaryUrl", "https://github.com/" + REPO + "/actions/workflows/canary.yml");
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
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (cached != null && cached.get("error") == null) {
                // keep showing the last good data; just say it is not fresh
                Map<String, Object> stale = new LinkedHashMap<>(cached);
                stale.put("warning", msg);
                cachedAt = now;
                return stale;
            }
            out.put("error", msg);
        }
        cached = out;
        cachedAt = now;
        return out;
    }

    /**
     * Steps of every job of one run (those named "N · ..."), plus what the run reported as annotations:
     * traffic before / during / after and the canary analysis. Cached once the run is complete.
     */
    private Map<String, Object> details(Object id, Map<String, Object> out) throws Exception {
        String key = String.valueOf(id);
        if (drillDone.containsKey(key)) {
            return drillDone.get(key);
        }
        Map<String, Object> d = new LinkedHashMap<>();
        java.util.TreeMap<Integer, String> parts = new java.util.TreeMap<>();   // canary-result-1, -2, ...
        List<Map<String, Object>> steps = new ArrayList<>();
        boolean done = true;
        for (Object jo : arr(obj(Json.parse(fetch(API + "/actions/runs/" + id + "/jobs"))).get("jobs"))) {
            Map<String, Object> job = obj(jo);
            for (Object o : arr(job.get("steps"))) {
                Map<String, Object> st = obj(o);
                String name = String.valueOf(st.get("name"));
                if (name.matches("^[0-9] · .*")) {
                    steps.add(Map.of("name", name, "status", String.valueOf(st.get("status")),
                            "conclusion", String.valueOf(st.get("conclusion"))));
                }
            }
            if (!"completed".equals(job.get("status"))) {
                done = false;
                continue;
            }
            List<Object> anns;
            try {
                anns = arr(Json.parse(fetch(API + "/check-runs/" + job.get("id") + "/annotations")));
            } catch (Exception e) {
                anns = List.of();
                done = false;   // do not cache a run whose report could not be read
                out.put("drillError", "report: " + e.getMessage());
            }
            for (Object o : anns) {
                Map<String, Object> an = obj(o);
                String title = String.valueOf(an.get("title"));
                String msg = String.valueOf(an.get("message"));
                if (title.startsWith("traffic-") || "canary-result".equals(title)) {
                    try {
                        String text = msg.startsWith("b64:") ? new String(java.util.Base64.getDecoder()
                                .decode(msg.substring(4).trim()), java.nio.charset.StandardCharsets.UTF_8) : msg;
                        d.put(title, Json.parse(text.trim()));
                    } catch (RuntimeException ignored) {
                        // not JSON (or masked by GitHub): skip
                    }
                } else if (title.matches("canary-result-\\d+") && msg.startsWith("b64:")) {
                    parts.put(Integer.parseInt(title.substring(14)), msg.substring(4).trim());
                } else if ("canary-input".equals(title)) {
                    d.put("input", msg);
                }
            }
        }
        if (!parts.isEmpty()) {
            try {
                d.put("canary-result", Json.parse(new String(java.util.Base64.getDecoder().decode(String.join("", parts.values())),
                        java.nio.charset.StandardCharsets.UTF_8).trim()));
            } catch (RuntimeException ignored) {
                // a part is missing: the next refresh tries again
            }
        }
        d.put("steps", steps);
        if (done) {
            drillDone.put(key, d);
        }
        return d;
    }

    /** Starts the rollback drill. Needs a GitHub token with permission to run workflows (gh auth login). */
    synchronized Map<String, Object> dispatch(boolean bad) {
        Map<String, Object> out = new LinkedHashMap<>();
        String t = token();
        if (t == null || t.isBlank()) {
            out.put("error", "no-token");
            return out;
        }
        String version = bad ? "bad: attempt 1 (expiry-month bug)" : "good: the approved release";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/actions/workflows/canary.yml/dispatches"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + t).header("User-Agent", "ai-code-verification-gate")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"ref\":\"main\",\"inputs\":{\"version\":\"" + version + "\"}}"))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 204) {
                out.put("started", version);
                cached = null;
            } else {
                out.put("error", "GitHub " + r.statusCode() + ": " + r.body());
            }
        } catch (Exception e) {
            out.put("error", String.valueOf(e.getMessage()));
        }
        return out;
    }

    /**
     * Cloud demo: a visitor approved the code that is already approved in release/iso8583/ (same SHA-256), so
     * the real CD pipeline runs again for it. GitHub Actions re-checks the approval record in the repository.
     */
    /** True when a release run is queued or running on GitHub right now. */
    boolean releaseRunning() {
        for (String st : new String[] {"in_progress", "queued"}) {
            try {
                Map<String, Object> r = obj(Json.parse(fetch(API + "/actions/workflows/" + WORKFLOW + "/runs?per_page=1&status=" + st)));
                Object n = r.get("total_count");
                if (n instanceof Number num && num.longValue() > 0) {
                    return true;
                }
            } catch (Exception e) {
                // cannot tell: do not block the release because of a read error
            }
        }
        return false;
    }

    synchronized Map<String, Object> dispatchRelease(String by, String run) {
        Map<String, Object> out = new LinkedHashMap<>();
        String t = System.getenv("GH_DISPATCH_TOKEN");
        if (t == null || t.isBlank()) {
            out.put("release", "off");
            return out;
        }
        String body = "{\"ref\":\"main\",\"inputs\":{\"by\":" + Json.writeCompact(by) + ",\"run\":" + Json.writeCompact(run) + "}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(API + "/actions/workflows/" + WORKFLOW + "/dispatches"))
                    .timeout(Duration.ofSeconds(15)).header("Accept", "application/vnd.github+json")
                    .header("Authorization", "Bearer " + t).header("User-Agent", "ai-code-verification-gate")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 204) {
                out.put("release", "started");
                cached = null;
            } else {
                out.put("release", "error");
                out.put("releaseError", "GitHub " + r.statusCode());
            }
        } catch (Exception e) {
            out.put("release", "error");
            out.put("releaseError", String.valueOf(e.getMessage()));
        }
        return out;
    }

    private String fetch(String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "ai-code-verification-gate");
        String token = token();
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401 && token != null && !token.isBlank()) {
            // a stale token: fall back to public, unauthenticated reads
            this.token = null;
            this.tokenLooked = true;
            return fetch(url);
        }
        if (r.statusCode() != 200) {
            throw new IllegalStateException("GitHub API " + r.statusCode() + (r.statusCode() == 403 || r.statusCode() == 429 ? " (hourly limit reached; run `gh auth login` or set GITHUB_TOKEN, then restart the console)" : ""));
        }
        return r.body();
    }

    private final Map<String, Map<String, Object>> drillDone = new java.util.concurrent.ConcurrentHashMap<>();
    private String token;
    private boolean tokenLooked;

    /** GITHUB_TOKEN, or the token of the GitHub CLI if it is installed and logged in. Read-only use. */
    private String token() {
        if (tokenLooked) {
            return token;
        }
        tokenLooked = true;
        token = System.getenv("GITHUB_TOKEN");
        if (token == null || token.isBlank()) {
            token = System.getenv("GH_DISPATCH_TOKEN"); // cloud demo: may start release.yml, nothing else
        }
        if (token == null || token.isBlank()) {
            try {
                Process p = new ProcessBuilder("gh", "auth", "token").redirectErrorStream(false).start();
                String t = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (p.waitFor() == 0 && !t.isEmpty() && !t.contains(" ")) {
                    token = t;
                }
            } catch (Exception ignored) {
                // no gh CLI: public, unauthenticated reads
            }
        }
        return token;
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
