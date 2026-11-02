package dev.aiverify.report;

import dev.aiverify.json.Json;
import dev.aiverify.pipeline.AuditLog;
import dev.aiverify.pipeline.Hashing;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code evidence --run ID}: one release evidence pack for one change, from the spec to production.
 *
 * It answers the auditor's questions in one page, each answer backed by a file in the pack and its SHA-256:
 * which locked spec (who locked it), which code (who wrote it, its hash), which checks it passed (P1-P7, G1-G6),
 * who approved it and when (and that the audit log is intact), what was released, how the canary behaved
 * in production, and what the incident fed back into the next spec version.
 *
 * Options: --project-result FILE (P1-P7), --canary FILE (canary-check output), --incident FILE (SpecForge
 * incident.md), --cd-run ID (a GitHub Actions run of this repo), --out DIR. Missing parts are shown as missing,
 * never invented.
 */
public final class EvidencePack {

    private EvidencePack() {
    }

    private record Item(String what, String file, String sha256) {
    }

    @SuppressWarnings("unchecked")
    public static Path write(Path root, String run, Map<String, String> opts) throws IOException {
        Path runDir = root.resolve("runs").resolve(run);
        Path resultFile = runDir.resolve("result.json");
        if (!Files.exists(resultFile)) {
            throw new IOException("No run " + run + " (" + resultFile + ")");
        }
        Path out = opts.containsKey("out") ? Path.of(opts.get("out")) : runDir.resolve("evidence");
        Path files = out.resolve("files");
        Files.createDirectories(files);
        List<Item> items = new ArrayList<>();

        Map<String, Object> result = Json.parseObject(Files.readString(resultFile));
        String runId = String.valueOf(result.get("runId"));
        copy(resultFile, files, "gate-result.json", "Gate run result (G1-G6)", items);
        Path code = runDir.resolve("candidate/AuthorizationPrecheckImpl.java");
        if (Files.exists(code)) {
            copy(code, files, "AuthorizationPrecheckImpl.java", "The code that was checked and approved", items);
        }
        for (String f : List.of("spec.md", "golden-tests.json")) {
            Path p = runDir.resolve("inputs").resolve(f);
            if (Files.exists(p)) {
                copy(p, files, "spec-" + f, f.endsWith(".md") ? "Spec the gate checked against (SpecForge export)"
                        : "Golden tests (SpecForge export)", items);
            }
        }
        String origin = Files.exists(runDir.resolve("source.txt"))
                ? String.join(" · ", Files.readAllLines(runDir.resolve("source.txt"))) : null;

        // P1-P7 for the whole project
        Map<String, Object> project = null;
        Path pr = opts.containsKey("project-result") ? Path.of(opts.get("project-result")) : latestProject(root);
        if (pr != null && Files.exists(pr)) {
            project = Json.parseObject(Files.readString(pr));
            copy(pr, files, "project-result.json", "Whole-project checks (P1-P7)", items);
        }

        // G6 + audit log
        Map<String, Object> decision = Map.of();
        for (Map<String, Object> g : (List<Map<String, Object>>) result.get("gates")) {
            if ("G6".equals(g.get("id")) && g.get("details") instanceof Map<?, ?> d) {
                decision = (Map<String, Object>) d;
            }
        }
        Path auditFile = root.resolve("runs/audit-log.jsonl");
        List<Map<String, Object>> auditEntries = new ArrayList<>();
        String auditProblem = "no audit log";
        if (Files.exists(auditFile)) {
            for (String line : Files.readAllLines(auditFile)) {
                if (!line.isBlank()) {
                    Map<String, Object> e = Json.parseObject(line);
                    if (runId.equals(String.valueOf(e.get("runId")))) {
                        auditEntries.add(e);
                    }
                }
            }
            auditProblem = new AuditLog(auditFile).verify();
            copy(auditFile, files, "audit-log.jsonl", "Hash-chained audit log", items);
        }

        // release record
        Map<String, Object> release = null;
        Path rel = root.resolve("release/iso8583/approval.json");
        if (Files.exists(rel)) {
            Map<String, Object> a = Json.parseObject(Files.readString(rel));
            if (runId.equals(String.valueOf(a.get("runId")))) {
                release = a;
                copy(rel, files, "release-approval.json", "Release record (what CD deploys)", items);
            }
        }

        // CD run on GitHub
        Map<String, Object> cd = null;
        if (opts.containsKey("cd-run")) {
            cd = githubRun(opts.get("cd-run"));
            Path f = files.resolve("cd-run.json");
            Files.writeString(f, Json.write(cd));
            items.add(new Item("GitHub Actions run (fetched " + OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS) + ")",
                    "files/cd-run.json", Hashing.sha256(Files.readString(f))));
        }

        // canary / AIOps and incident feedback
        Map<String, Object> canary = null;
        if (opts.containsKey("canary")) {
            canary = Json.parseObject(Files.readString(Path.of(opts.get("canary"))));
            copy(Path.of(opts.get("canary")), files, "canary-result.json", "Canary check and AIOps analysis", items);
        }
        String incident = null;
        if (opts.containsKey("incident")) {
            incident = Files.readString(Path.of(opts.get("incident")));
            copy(Path.of(opts.get("incident")), files, "incident.md", "Incident fed back into the spec (SpecForge)", items);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", runId);
        manifest.put("createdAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        List<Object> list = new ArrayList<>();
        for (Item i : items) {
            list.add(Map.of("what", i.what(), "file", i.file(), "sha256", i.sha256()));
        }
        manifest.put("files", list);
        String manifestJson = Json.write(manifest);
        Files.writeString(out.resolve("manifest.json"), manifestJson);

        String html = html(result, origin, project, decision, auditEntries, auditProblem, release, cd, canary, incident,
                items, Hashing.sha256(manifestJson));
        Path page = out.resolve("evidence.html");
        Files.writeString(page, html);
        return page;
    }

    private static Item copy(Path from, Path dir, String name, String what, List<Item> items) throws IOException {
        Path to = dir.resolve(name);
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        Item i = new Item(what, "files/" + name, Hashing.sha256(Files.readString(to)));
        items.add(i);
        return i;
    }

    private static Path latestProject(Path root) throws IOException {
        Path dir = root.resolve("runs/projects");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> Files.exists(p.resolve("result.json"))).max(Comparator.naturalOrder())
                    .map(p -> p.resolve("result.json")).orElse(null);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> githubRun(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        String api = "https://api.github.com/repos/" + dev.aiverify.ui.CicdStatus.REPO + "/actions/runs/" + id;
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            Map<String, Object> r = Json.parseObject(get(http, api));
            for (String k : List.of("id", "name", "display_title", "event", "status", "conclusion", "head_sha",
                    "created_at", "updated_at", "html_url")) {
                out.put(k, r.get(k));
            }
            List<Object> jobs = new ArrayList<>();
            for (Object o : (List<Object>) Json.parseObject(get(http, api + "/jobs")).get("jobs")) {
                Map<String, Object> j = (Map<String, Object>) o;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", j.get("name"));
                row.put("conclusion", j.get("conclusion"));
                row.put("started_at", j.get("started_at"));
                row.put("completed_at", j.get("completed_at"));
                jobs.add(row);
            }
            out.put("jobs", jobs);
        } catch (Exception e) {
            out.put("error", "Could not read run " + id + " from GitHub: " + e.getMessage());
        }
        return out;
    }

    private static String get(HttpClient http, String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json");
        String token = System.getenv("GITHUB_TOKEN");
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token.strip());
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("HTTP " + r.statusCode());
        }
        return r.body();
    }

    // ---------------------------------------------------------------- page

    @SuppressWarnings("unchecked")
    private static String html(Map<String, Object> result, String origin, Map<String, Object> project,
            Map<String, Object> decision, List<Map<String, Object>> audit, String auditProblem,
            Map<String, Object> release, Map<String, Object> cd, Map<String, Object> canary, String incident,
            List<Item> items, String manifestSha) {
        StringBuilder b = new StringBuilder();
        String runId = String.valueOf(result.get("runId"));
        Map<String, Object> spec = result.get("specSource") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        List<Map<String, Object>> gates = (List<Map<String, Object>>) result.get("gates");
        boolean gatesOk = gates.stream().allMatch(g -> "PASSED".equals(g.get("status")) || "SKIPPED".equals(g.get("status")));
        boolean approved = "APPROVED".equals(decision.get("decision"));
        String projectVerdict = project == null ? null : String.valueOf(project.get("verdict"));

        b.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Release evidence ").append(esc(runId)).append("</title><style>").append(CSS)
                .append("</style></head><body><main>");
        b.append("<p class=\"eyebrow\">Release evidence pack · 上線證據包</p><h1>").append(esc(runId)).append("</h1>");
        b.append("<p class=\"lede\">One change, from the locked spec to production. Every claim below points to a file in ")
                .append("this pack and its SHA-256. 一次變更從鎖定的規格到正式環境的完整紀錄，每一項都附檔案與雜湊值。</p>");

        // the chain at a glance
        b.append("<ol class=\"chain\">");
        step(b, "Spec 規格", spec == null ? null : true, spec == null ? "not recorded"
                : "v" + spec.get("version") + " · locked by " + spec.get("approvedBy"));
        step(b, "Code 程式", true, origin != null ? origin : "sha256 " + shortSha(result.get("candidateSha256")));
        boolean accepted = String.valueOf(decision.get("note")).contains("Accepted project findings");
        step(b, "P1–P7 project", projectVerdict == null ? null : !"BLOCKED".equals(projectVerdict),
                projectVerdict == null ? "not run" : projectVerdict + (accepted ? " · findings accepted by the approver" : ""));
        step(b, "G1–G5 gates", gatesOk || approved, String.valueOf(result.get("verdict")));
        step(b, "G6 human 人工核准", decision.isEmpty() ? null : approved,
                decision.isEmpty() ? "waiting" : decision.get("decision") + " · " + decision.get("decidedBy"));
        step(b, "Release 發布", release == null ? null : true, release == null ? "not released" : "released " + release.get("releasedAt"));
        String cdNote = cd == null ? "not attached" : cd.containsKey("error") ? "unreadable"
                : cd.get("conclusion") + " · " + cd.get("event");
        step(b, "CD pipeline", cd == null || cd.containsKey("error") ? null : "success".equals(cd.get("conclusion")), cdNote);
        step(b, "Canary 金絲雀", canary == null ? null : "PROMOTE".equals(canary.get("verdict")),
                canary == null ? "not attached" : String.valueOf(canary.get("verdict")));
        step(b, "Feedback → spec", incident == null ? null : true, incident == null
                ? (canary != null && canary.get("incident") instanceof Map<?, ?> ci && ci.get("changed") instanceof Number c
                        && c.intValue() == 0 ? "nothing to feed back (no answer changed)" : "none")
                : "next spec version drafted");
        b.append("</ol>");

        // 1 spec
        b.append("<h2>1 · Spec · 規格</h2>");
        if (spec == null) {
            b.append("<p class=\"miss\">This run was not checked against a SpecForge spec (no --golden-file).</p>");
        } else {
            kv(b, List.of("Title", spec.get("title"), "Version", "v" + spec.get("version"), "Locked by", spec.get("approvedBy"),
                    "Locked at", spec.get("approvedAt"), "Spec sha256", spec.get("sha256"),
                    "Golden tests", spec.get("goldenFile") + " · sha256 " + spec.get("goldenSha256"),
                    "Tool", spec.get("tool")));
        }

        // 2 code
        b.append("<h2>2 · Code · 程式</h2>");
        kv(b, List.of("Origin", origin == null ? "—" : origin, "Code sha256", result.get("candidateSha256"),
                "Attempt", result.get("attempt"), "Started", result.get("startedAt")));
        if (project != null) {
            b.append("<h3>Whole project · P1–P7</h3><table><tr><th>Gate</th><th>Check</th><th>Status</th><th>Errors</th><th>Warnings</th></tr>");
            for (Map<String, Object> g : (List<Map<String, Object>>) project.get("gates")) {
                b.append("<tr><td>").append(esc(g.get("id"))).append("</td><td>").append(esc(g.get("name"))).append("</td><td>")
                        .append(chip(g.get("status"))).append("</td><td>").append(esc(g.get("errors"))).append("</td><td>")
                        .append(esc(g.get("warnings"))).append("</td></tr>");
            }
            b.append("</table><p class=\"small\">Project verdict: ").append(esc(projectVerdict)).append("</p>");
        }

        // 3 gates
        b.append("<h2>3 · Gates G1–G6 · 關卡</h2><table><tr><th>Gate</th><th>Check</th><th>Status</th><th>Result</th></tr>");
        for (Map<String, Object> g : gates) {
            b.append("<tr><td>").append(esc(g.get("id"))).append("</td><td>").append(esc(g.get("name"))).append("</td><td>")
                    .append(chip(g.get("status"))).append("</td><td>").append(esc(g.get("summary"))).append("</td></tr>");
        }
        b.append("</table>");

        // 4 approval
        b.append("<h2>4 · Human approval · 人工核准</h2>");
        if (decision.isEmpty()) {
            b.append("<p class=\"miss\">No decision yet.</p>");
        } else {
            kv(b, List.of("Decision", decision.get("decision"), "By", decision.get("decidedBy"), "At", decision.get("decidedAt"),
                    "Note", decision.get("note"), "Accepted HIGH AI findings", decision.get("acceptedHighFindings")));
        }
        b.append("<p class=\"small\">Audit log: ").append(auditProblem == null
                ? "<b>hash chain intact</b>" : "<b class=\"bad\">" + esc(auditProblem) + "</b>")
                .append(" · ").append(audit.size()).append(" entr").append(audit.size() == 1 ? "y" : "ies")
                .append(" for this run</p><ul class=\"small\">");
        for (Map<String, Object> e : audit) {
            b.append("<li><code>").append(esc(e.get("at"))).append("</code> ").append(esc(e.get("event")))
                    .append(e.get("decidedBy") != null ? " · " + esc(e.get("decidedBy")) : "").append("</li>");
        }
        b.append("</ul>");

        // 5 release + CD
        b.append("<h2>5 · Release and CD · 發布與部署</h2>");
        if (release == null) {
            b.append("<p class=\"miss\">release/iso8583/approval.json does not hold this run.</p>");
        } else {
            kv(b, List.of("Released at", release.get("releasedAt"), "Code sha256", release.get("candidateSha256"),
                    "Matches the approved code", String.valueOf(release.get("candidateSha256")).equals(result.get("candidateSha256")) ? "yes" : "NO"));
        }
        if (cd != null) {
            if (cd.containsKey("error")) {
                b.append("<p class=\"miss\">").append(esc(cd.get("error"))).append("</p>");
            } else {
                kv(b, List.of("Workflow", cd.get("name") + " · " + cd.get("display_title"), "Result", cd.get("conclusion"),
                        "Trigger", cd.get("event"), "Commit", cd.get("head_sha"), "Ran", cd.get("created_at") + " → " + cd.get("updated_at"),
                        "Link", cd.get("html_url")));
                b.append("<table><tr><th>Job</th><th>Result</th></tr>");
                for (Object o : (List<Object>) cd.get("jobs")) {
                    Map<String, Object> j = (Map<String, Object>) o;
                    b.append("<tr><td>").append(esc(j.get("name"))).append("</td><td>").append(chip(j.get("conclusion"))).append("</td></tr>");
                }
                b.append("</table>");
            }
        }

        // 6 canary
        b.append("<h2>6 · Production canary and AIOps · 金絲雀與 AIOps</h2>");
        if (canary == null) {
            b.append("<p class=\"miss\">No canary result attached.</p>");
        } else {
            b.append("<p>Verdict: ").append(chip(canary.get("verdict"))).append(" · ").append(esc(canary.get("n")))
                    .append(" transactions replayed against both versions.</p>");
            Map<String, Object> inc = canary.get("incident") instanceof Map<?, ?> m2 ? (Map<String, Object>) m2 : Map.of();
            if (inc.get("groups") instanceof List<?> groups && !groups.isEmpty()) {
                b.append("<table><tr><th>Stable → canary</th><th>Transactions</th><th>Traced to</th></tr>");
                for (Object o : groups) {
                    Map<String, Object> g = (Map<String, Object>) o;
                    b.append("<tr><td>").append(esc(g.get("from"))).append(" → ").append(esc(g.get("to"))).append("</td><td>")
                            .append(esc(g.get("count"))).append("</td><td>").append(esc(g.get("rule")))
                            .append(g.get("line") instanceof Number n && n.intValue() > 0 ? " line " + n : "").append("</td></tr>");
                }
                b.append("</table>");
            }
        }

        // 7 incident
        b.append("<h2>7 · Back to the spec · 回饋到規格</h2>");
        if (incident == null) {
            b.append("<p class=\"miss\">Nothing fed back: ").append(canary == null ? "no canary result attached."
                    : "in the canary, no transaction changed its answer, so the spec has nothing new to learn.").append("</p>");
        } else {
            b.append(markdown(incident));
        }

        // manifest
        b.append("<h2>Files in this pack · 檔案清單</h2><table><tr><th>What</th><th>File</th><th>SHA-256</th></tr>");
        for (Item i : items) {
            b.append("<tr><td>").append(esc(i.what())).append("</td><td><a href=\"").append(esc(i.file())).append("\">")
                    .append(esc(i.file())).append("</a></td><td><code>").append(esc(i.sha256())).append("</code></td></tr>");
        }
        b.append("</table><p class=\"small\">manifest.json sha256 <code>").append(manifestSha).append("</code> · generated ")
                .append(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)).append(" by AI Code Verification Gate</p>");
        b.append("</main></body></html>");
        return b.toString();
    }

    /** Just enough Markdown for SpecForge's incident report: headings, bullets, tables, `code`. */
    static String markdown(String md) {
        StringBuilder b = new StringBuilder();
        boolean table = false, list = false;
        for (String line : md.split("\n")) {
            String t = line.strip();
            if (list && !t.startsWith("- ")) {
                b.append("</ul>");
                list = false;
            }
            if (table && !t.startsWith("|")) {
                b.append("</table>");
                table = false;
            }
            if (t.startsWith("|")) {
                if (t.matches("\\|[\\s\\-|]+\\|")) {
                    continue;
                }
                String[] cells = t.substring(1, t.length() - 1).split(" \\| ");
                String tag = table ? "td" : "th";
                b.append(table ? "" : "<table>").append("<tr>");
                for (String c : cells) {
                    b.append("<").append(tag).append(">").append(inline(c.strip())).append("</").append(tag).append(">");
                }
                b.append("</tr>");
                table = true;
            } else if (t.startsWith("- ")) {
                b.append(list ? "" : "<ul>").append("<li>").append(inline(t.substring(2))).append("</li>");
                list = true;
            } else if (t.startsWith("#")) {
                b.append("<h3>").append(inline(t.replaceFirst("^#+\\s*", ""))).append("</h3>");
            } else if (!t.isEmpty()) {
                b.append("<p>").append(inline(t)).append("</p>");
            }
        }
        return b.append(table ? "</table>" : "").append(list ? "</ul>" : "").toString();
    }

    private static String inline(String s) {
        return esc(s).replaceAll("`([^`]+)`", "<code>$1</code>");
    }

    private static void step(StringBuilder b, String name, Boolean ok, String note) {
        String cls = ok == null ? "na" : ok ? "ok" : "bad";
        b.append("<li class=\"").append(cls).append("\"><b>").append(esc(name)).append("</b><span>").append(esc(note))
                .append("</span></li>");
    }

    private static void kv(StringBuilder b, List<Object> pairs) {
        b.append("<dl>");
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            b.append("<dt>").append(esc(pairs.get(i))).append("</dt><dd>").append(esc(pairs.get(i + 1))).append("</dd>");
        }
        b.append("</dl>");
    }

    private static String chip(Object status) {
        String s = String.valueOf(status);
        String cls = switch (s) {
            case "PASSED", "PASS", "success", "PROMOTE", "APPROVED", "OK" -> "ok";
            case "FAILED", "BLOCKED", "failure", "ROLLBACK", "REJECTED" -> "bad";
            default -> "na";
        };
        return "<span class=\"chip " + cls + "\">" + esc(s) + "</span>";
    }

    private static String shortSha(Object s) {
        String v = String.valueOf(s);
        return v.length() > 12 ? v.substring(0, 12) + "…" : v;
    }

    static String esc(Object o) {
        return String.valueOf(o == null ? "—" : o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root{--bg:#fbfaf7;--fg:#1d1d1b;--mut:#6b6a66;--line:#e3e0d8;--card:#fff;--ok:#1f7a4d;--okbg:#e5f3ea;
            --bad:#b3261e;--badbg:#fbe6e4;--na:#6b6a66;--nabg:#efede8;--acc:#2d4bd1}
            @media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#151514;--fg:#ecebe6;--mut:#a3a19a;
            --line:#34332f;--card:#1e1e1c;--ok:#6fd39d;--okbg:#17321f;--bad:#ff8a80;--badbg:#3a1a17;--na:#a3a19a;--nabg:#2a2926;--acc:#8ea2ff}}
            :root[data-theme="dark"]{--bg:#151514;--fg:#ecebe6;--mut:#a3a19a;--line:#34332f;--card:#1e1e1c;--ok:#6fd39d;
            --okbg:#17321f;--bad:#ff8a80;--badbg:#3a1a17;--na:#a3a19a;--nabg:#2a2926;--acc:#8ea2ff}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
            font:15px/1.55 system-ui,-apple-system,"Segoe UI","Noto Sans TC","PingFang TC",sans-serif}
            main{max-width:980px;margin:0 auto;padding:32px 16px 64px}
            .eyebrow{color:var(--acc);font-weight:600;letter-spacing:.04em;text-transform:uppercase;font-size:12px;margin:0}
            h1{font-size:28px;margin:4px 0 8px;word-break:break-all}h2{font-size:19px;margin:36px 0 10px;
            padding-top:16px;border-top:1px solid var(--line)}h3{font-size:15px;margin:18px 0 6px}
            .lede{color:var(--mut);max-width:70ch}.small{color:var(--mut);font-size:13px}.miss{color:var(--mut);font-style:italic}
            .chain{list-style:none;padding:0;margin:20px 0;display:grid;grid-template-columns:repeat(auto-fill,minmax(170px,1fr));gap:8px}
            .chain li{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--na);border-radius:8px;padding:8px 10px}
            .chain li.ok{border-left-color:var(--ok)}.chain li.bad{border-left-color:var(--bad)}
            .chain b{display:block;font-size:13px}.chain span{display:block;font-size:12px;color:var(--mut);word-break:break-word}
            dl{display:grid;grid-template-columns:max-content 1fr;gap:4px 16px;margin:8px 0}dt{color:var(--mut)}
            dd{margin:0;word-break:break-all}table{width:100%;border-collapse:collapse;margin:8px 0;font-size:14px;
            display:block;overflow-x:auto}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
            th{color:var(--mut);font-weight:600}code{font-size:12px;word-break:break-all}
            .chip{display:inline-block;padding:1px 8px;border-radius:99px;font-size:12px;font-weight:600;background:var(--nabg);color:var(--na)}
            .chip.ok{background:var(--okbg);color:var(--ok)}.chip.bad{background:var(--badbg);color:var(--bad)}.bad{color:var(--bad)}
            pre{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:12px;overflow-x:auto;
            font-size:12px;white-space:pre-wrap}a{color:var(--acc)}
            """;
}
