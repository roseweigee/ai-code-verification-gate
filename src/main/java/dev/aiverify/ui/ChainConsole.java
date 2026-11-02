package dev.aiverify.ui;

import com.sun.net.httpserver.HttpServer;
import dev.aiverify.json.Json;
import dev.aiverify.pipeline.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The whole chain, step by step, from the browser (page /chain): SpecForge -> SpecBlast -> gate -> approval ->
 * release -> canary + AIOps -> incident back to the spec -> evidence pack.
 *
 * Every step runs the same commands as chain/run-chain.sh, in this visitor's own chain workspace
 * (runs/chain/ in the session): a copy of release/ and config/, so nothing shared is changed and nothing is
 * deployed. The canary runs both versions inside this process on 127.0.0.1.
 */
final class ChainConsole {

    /** Runs the gate CLI (with this session's mail settings) and SpecForge, as the console does for its buttons. */
    interface Cli {
        Map<String, Object> gate(Path root, List<String> args, boolean mail) throws IOException, InterruptedException;

        Map<String, Object> specforge(List<String> args) throws IOException, InterruptedException;

        /** SpecForge with the AI key (extract, refine); refused when the AI budget is used up. */
        Map<String, Object> specforgeAi(List<String> args) throws IOException, InterruptedException;

        /**
         * Sends requirements.md to the SpecBlast service (its own /api/v1/generate/zip, the same call as its web page)
         * and unzips the project into outDir. Returns null when no SpecBlast service runs here.
         */
        Map<String, Object> specblast(Path requirements, Path outDir) throws IOException, InterruptedException;
    }

    /** The answered reference spec: the one SpecBlast's saved project was generated from. */
    static final String REFERENCE_SHA = "524608bfa276a8726fee0face7e18fd983047041cbc87529c935d32063ea029d";

    static final List<String> INPUTS = List.of("ref", "pm-a", "pm-b", "pm-c");
    private static final String RUN_RE = "run-[0-9]{8}-[0-9]{6}-sb";

    private final Path root;
    private final Path specforge;

    ChainConsole(Path root, Path specforge) {
        this.root = root;
        this.specforge = specforge;
    }

    /** Where SpecForge lives: SPECFORGE_HOME, ./specforge (the cloud image), or ../specforge (next to this repo). */
    static Path findSpecForge(Path root) {
        String env = System.getenv("SPECFORGE_HOME");
        for (Path p : List.of(env == null || env.isBlank() ? root.resolve("specforge") : Path.of(env),
                root.resolve("specforge"), root.resolveSibling("specforge"))) {
            if (Files.isDirectory(p.resolve("build/classes/dev/specforge"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    boolean available() {
        return specforge != null;
    }

    Path specforgeHome() {
        return specforge;
    }

    // ------------------------------------------------------------------ workspace

    private static Path ws(Path sessionRoot) {
        return sessionRoot.resolve("runs/chain");
    }

    /** A fresh chain workspace: the gate's files linked read-only, release/ and config/ copied (they get written). */
    void reset(Path sessionRoot) throws IOException {
        Path ws = ws(sessionRoot);
        deleteTree(ws);
        Path gate = Files.createDirectories(ws.resolve("gate"));
        try (Stream<Path> st = Files.list(root)) {
            for (Path e : st.toList()) {
                String n = e.getFileName().toString();
                if (List.of("runs", ".git", "notify.properties", "release", "config", "chain-runs", "specforge", "build").contains(n)) {
                    continue;
                }
                Files.createSymbolicLink(gate.resolve(n), e.toAbsolutePath());
            }
        }
        copyTree(root.resolve("release"), gate.resolve("release"));
        copyTree(root.resolve("config"), gate.resolve("config"));
        Files.deleteIfExists(gate.resolve("config/recipients.json"));
        Files.createDirectories(ws.resolve("spec"));
        saveState(sessionRoot, new LinkedHashMap<>(Map.of("createdAt", now())));
    }

    private Path gate(Path sessionRoot) throws IOException {
        Path g = ws(sessionRoot).resolve("gate");
        if (!Files.isDirectory(g)) {
            reset(sessionRoot);
        }
        // the session's recipients get the chain's emails too
        Path rc = sessionRoot.resolve("config/recipients.json");
        Path to = g.resolve("config/recipients.json");
        if (Files.exists(rc)) {
            Files.copy(rc, to, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(to);
        }
        return g;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadState(Path sessionRoot) throws IOException {
        Path f = ws(sessionRoot).resolve("state.json");
        return Files.exists(f) ? (Map<String, Object>) Json.parse(Files.readString(f)) : new LinkedHashMap<>();
    }

    private void saveState(Path sessionRoot, Map<String, Object> s) throws IOException {
        Files.createDirectories(ws(sessionRoot));
        Path tmp = ws(sessionRoot).resolve("state.json.tmp");
        Files.writeString(tmp, Json.write(s));
        Files.move(tmp, ws(sessionRoot).resolve("state.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------ actions

    /** One button on the page. Returns the commands run and their output; the page then reloads the state. */
    @SuppressWarnings("unchecked")
    Map<String, Object> act(Path sr, String step, Map<String, String> q, Cli cli) throws IOException, InterruptedException {
        if (!available() && List.of("load", "lock", "export", "tamper", "incident", "lock-next").contains(step)) {
            throw new IllegalArgumentException("SpecForge is not installed next to the gate (../specforge).");
        }
        Map<String, Object> st = loadState(sr);
        Path ws = ws(sr);
        List<Object> log = new ArrayList<>();
        switch (step) {
            case "reset" -> {
                reset(sr);
                st = loadState(sr);
                log.add(Map.of("command", "(new chain workspace)", "exitCode", 0, "output", "Everything from the previous chain was cleared."));
            }
            case "load" -> {
                String in = q.getOrDefault("input", "ref");
                if (!INPUTS.contains(in)) {
                    throw new IllegalArgumentException("unknown input");
                }
                if (!"1".equals(q.get("keep"))) {
                    reset(sr);
                }
                st = loadState(sr);
                Path src = "ref".equals(in) ? specforge.resolve("examples/reference.canonical.json")
                        : specforge.resolve("examples/extracted/" + in + ".canonical.json");
                Path spec = ws.resolve("spec");
                Files.copy(src, spec.resolve("input.json"), StandardCopyOption.REPLACE_EXISTING);
                Path source = specforge.resolve("examples/extracted/" + in + ".source.txt");
                if (Files.exists(source)) {
                    Files.copy(source, spec.resolve("source.txt"), StandardCopyOption.REPLACE_EXISTING);
                }
                log.add(cli.specforge(List.of("check", spec.resolve("input.json").toString(), "--out", spec.toString())));
                st.put("input", in);
            }
            case "lock" -> {
                String by = name(q.get("by"));
                Path spec = ws.resolve("spec");
                Files.deleteIfExists(spec.resolve("canonical.locked.json"));
                log.add(cli.specforge(List.of("lock", spec.resolve("canonical.json").toString(), "--by", by, "--version", "1.0")));
            }
            case "tamper" -> {
                Path locked = ws.resolve("spec/canonical.locked.json");
                need(locked, "Lock the spec first.");
                Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(locked));
                List<Object> rules = (List<Object>) m.get("rules");
                Map<String, Object> last = (Map<String, Object>) rules.get(rules.size() - 1);
                String was = String.valueOf(last.get("result"));
                last.put("result", "00".equals(was) ? "05" : "00");
                Path t = ws.resolve("spec/tampered.json");
                Files.writeString(t, Json.write(m));
                Map<String, Object> r = new LinkedHashMap<>(cli.specforge(List.of("export", t.toString(), "--out",
                        ws.resolve("tampered-export").toString())));
                r.put("note", "Changed rule " + last.get("id") + " from " + was + " to " + last.get("result") + " after it was locked.");
                log.add(r);
            }
            case "export" -> {
                Path locked = ws.resolve("spec/canonical.locked.json");
                need(locked, "Lock the spec first.");
                log.add(cli.specforge(List.of("export", locked.toString(), "--contract",
                        root.resolve("chain/contract-iso8583.md").toAbsolutePath().toString(), "--out", ws.resolve("export").toString())));
            }
            case "specblast" -> {
                need(ws.resolve("export/specblast/requirements.md"), "Export the spec first (step 1).");
                Path p = ws.resolve("specblast-project");
                deleteTree(p);
                Map<String, Object> r = "saved".equals(q.get("mode")) ? null
                        : cli.specblast(ws.resolve("export/specblast/requirements.md"), p);
                if (r != null) {
                    log.add(r);
                    if (!Integer.valueOf(0).equals(r.get("exitCode"))) {
                        throw new IllegalStateException("SpecBlast failed: " + r.get("output"));
                    }
                    st.put("specblast", "live");
                } else {
                    Map<String, Object> locked = (Map<String, Object>) Json.parse(Files.readString(ws.resolve("spec/canonical.locked.json")));
                    if (!REFERENCE_SHA.equals(((Map<String, Object>) locked.get("approval")).get("sha256"))) {
                        throw new IllegalStateException("No SpecBlast service runs here, and the saved SpecBlast project was generated "
                                + "from the reference spec only. Start SpecBlast (SPECBLAST_URL) to generate code for this spec.");
                    }
                    copyTree(root.resolve("chain/examples/specblast-output"), p);
                    log.add(Map.of("command", "(SpecBlast project: chain/examples/specblast-output)", "exitCode", 0,
                            "output", "SpecBlast generated this project on 2026-11-02 from this same spec (sha256 524608bf…) and the gate's "
                                    + "contract. Unchanged, including SpecBlast's own mistakes."));
                    st.put("specblast", "saved");
                }
            }
            case "extract" -> {
                Path file = Path.of(q.get("file"));
                if (!"1".equals(q.get("keep"))) {
                    reset(sr);
                }
                st = loadState(sr);
                Path spec = ws.resolve("spec");
                Map<String, Object> r = cli.specforgeAi(List.of("extract", file.toString(), "--out", spec.toString()));
                log.add(r);
                if (!Integer.valueOf(0).equals(r.get("exitCode")) || !Files.exists(spec.resolve("canonical.json"))) {
                    throw new IllegalStateException("SpecForge could not read this document: " + lastLine(r));
                }
                st.put("input", "upload:" + file.getFileName());
            }
            case "refine" -> {
                Path spec = ws.resolve("spec");
                need(spec.resolve("canonical.json"), "Load a spec first.");
                Files.writeString(spec.resolve("answers.json"), q.getOrDefault("answers", "[]"));
                Map<String, Object> r = cli.specforgeAi(List.of("refine", spec.resolve("canonical.json").toString(),
                        spec.resolve("answers.json").toString(), "--out", spec.toString()));
                log.add(r);
                if (!Integer.valueOf(0).equals(r.get("exitCode"))) {
                    throw new IllegalStateException("SpecForge could not apply the answers: " + lastLine(r));
                }
            }
            case "verify" -> {
                need(ws.resolve("specblast-project"), "Get the SpecBlast project first (step 2).");
                Path g = gate(sr);
                log.add(cli.gate(g, List.of("verify-specblast", ws.resolve("specblast-project").toString(),
                        "--spec-file", ws.resolve("export/gate/spec.md").toString(),
                        "--golden-file", ws.resolve("export/gate/golden-tests.json").toString()), true));
                st.put("run", latest(g.resolve("runs/specblast")));
                st.remove("approved");
                st.remove("released");
            }
            case "approve" -> {
                String run = (String) st.get("run");
                if (run == null) {
                    throw new IllegalArgumentException("Run the quality gate first (step 3).");
                }
                Path g = gate(sr);
                String by = name(q.get("by"));
                String note = q.getOrDefault("note", "").replaceAll("[\\r\\n]", " ").strip();
                note = note.length() > 300 ? note.substring(0, 300) : note;
                List<String> errs = projectErrors(g);
                if (!errs.isEmpty() && !"true".equals(q.get("acceptFindings"))) {
                    throw new IllegalArgumentException("The project has blocking findings (" + String.join("; ", errs)
                            + "). Tick 'I accept these findings' to approve anyway; they are named in the approval.");
                }
                String full = (note.isEmpty() ? "Checked against spec v1.0" : note)
                        + (errs.isEmpty() ? "" : ". Accepted project findings: " + String.join("; ", errs));
                List<String> args = new ArrayList<>(List.of("approve", "--run", "specblast/" + run, "--by", by, "--note", full));
                if ("true".equals(q.get("ackHigh"))) {
                    args.add("--ack-high");
                }
                Map<String, Object> r = cli.gate(g, args, true);
                log.add(r);
                if (Integer.valueOf(0).equals(r.get("exitCode"))) {
                    st.put("approved", run);
                }
            }
            case "release" -> {
                String run = (String) st.get("approved");
                if (run == null) {
                    throw new IllegalArgumentException("Approve the run first (step 4).");
                }
                Path g = gate(sr);
                if (!Files.isDirectory(ws.resolve("stable"))) {
                    copyTree(g.resolve("release/iso8583"), ws.resolve("stable")); // what is live now
                }
                log.add(cli.gate(g, List.of("release", "--run", "specblast/" + run, "--no-push"), false));
                log.add(cli.gate(g, List.of("release-check"), false));
                st.put("released", run);
            }
            case "canary" -> {
                if (st.get("released") == null) {
                    throw new IllegalArgumentException("Release first (step 5).");
                }
                String mode = "drill".equals(q.get("mode")) ? "drill" : "release";
                Path g = gate(sr);
                Path canaryDir = g.resolve("release/iso8583");
                if ("drill".equals(mode)) {
                    canaryDir = ws.resolve("drill");
                    Files.createDirectories(canaryDir);
                    String src = dev.aiverify.ai.Prompts.extractJava(Files.readString(root.resolve("replay/attempt-1/author.txt")));
                    Files.writeString(canaryDir.resolve("AuthorizationPrecheckImpl.java"), src);
                    Files.writeString(canaryDir.resolve("approval.json"), Json.write(Map.of("runId", "drill-attempt-1",
                            "decision", "CANARY", "approvedBy", "nobody (incident drill)", "candidateSha256", Hashing.sha256(src))));
                }
                Map<String, Object> r = canary(g, ws.resolve("stable"), canaryDir, cli);
                log.add(r);
                String json = String.valueOf(r.getOrDefault("result", ""));
                if (!json.isEmpty()) {
                    Files.writeString(ws.resolve("canary-" + mode + ".json"), json);
                    st.put("canary-" + mode, ((Map<String, Object>) Json.parse(json)).get("verdict"));
                }
            }
            case "incident" -> {
                String mode = "release".equals(q.get("mode")) ? "release" : "drill";
                Path c = ws.resolve("canary-" + mode + ".json");
                need(c, "Run the " + ("drill".equals(mode) ? "incident drill" : "canary") + " first (step 6).");
                Path out = ws.resolve("incident");
                deleteTree(out);
                log.add(cli.specforge(List.of("incident", ws.resolve("spec/canonical.locked.json").toString(), c.toString(),
                        "--version", "1.1", "--out", out.toString())));
                st.put("incidentFrom", mode);
                st.remove("v11");
            }
            case "lock-next" -> {
                Path draft = ws.resolve("incident/canonical.json");
                need(draft, "Feed the incident back first.");
                String by = name(q.get("by"));
                Map<String, Object> r = cli.specforge(List.of("lock", draft.toString(), "--by", by, "--version", "1.1"));
                log.add(r);
                if (Integer.valueOf(0).equals(r.get("exitCode"))) {
                    log.add(cli.specforge(List.of("export", ws.resolve("incident/canonical.locked.json").toString(), "--contract",
                            root.resolve("chain/contract-iso8583.md").toAbsolutePath().toString(), "--out", ws.resolve("export-v1.1").toString())));
                    st.put("v11", true);
                }
            }
            case "rerun" -> {
                need(ws.resolve("export-v1.1/gate/golden-tests.json"), "Lock and export v1.1 first.");
                Path g = gate(sr);
                String v10 = ws.resolve("export/gate/golden-tests.json").toString(), v11 = ws.resolve("export-v1.1/gate/golden-tests.json").toString();
                log.add(cli.gate(g, List.of("run", "--attempt", "1", "--spec-file", ws.resolve("export/gate/spec.md").toString(), "--golden-file", v10), false));
                log.add(cli.gate(g, List.of("run", "--attempt", "1", "--spec-file", ws.resolve("export-v1.1/gate/spec.md").toString(), "--golden-file", v11), false));
                log.add(cli.gate(g, List.of("verify-specblast", ws.resolve("specblast-project").toString(), "--code-only",
                        "--spec-file", ws.resolve("export-v1.1/gate/spec.md").toString(), "--golden-file", v11), false));
                st.put("rerun", true);
            }
            case "evidence" -> {
                String run = (String) st.get("approved");
                if (run == null) {
                    throw new IllegalArgumentException("Approve a run first (step 4).");
                }
                Path g = gate(sr);
                List<String> args = new ArrayList<>(List.of("evidence", "--run", "specblast/" + run));
                if (Files.exists(ws.resolve("canary-release.json"))) {
                    args.addAll(List.of("--canary", ws.resolve("canary-release.json").toString()));
                }
                if (Files.exists(ws.resolve("incident/incident.md")) && "release".equals(st.get("incidentFrom"))) {
                    args.addAll(List.of("--incident", ws.resolve("incident/incident.md").toString()));
                }
                Path pr = latestProject(g);
                if (pr != null) {
                    args.addAll(List.of("--project-result", pr.toString()));
                }
                log.add(cli.gate(g, args, true));
                st.put("evidence", true);
            }
            default -> throw new IllegalArgumentException("unknown step");
        }
        saveState(sr, st);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("log", log);
        return out;
    }

    // ------------------------------------------------------------------ the pipeline (runs by itself)

    static final List<String> STAGES = List.of("spec", "specblast", "gate", "approve", "release", "canary", "evidence", "feedback");
    private final Map<Path, Thread> jobs = new java.util.concurrent.ConcurrentHashMap<>();

    boolean running(Path sr) {
        Thread t = jobs.get(sr);
        return t != null && t.isAlive();
    }

    /**
     * start (an example or an uploaded document) -> SpecForge; with open questions it waits for the answers;
     * then lock, export, SpecBlast, gate and it waits for a named approver; approve -> release, canary, evidence;
     * drill -> the incident drill back into the spec. Each runs in the background; the page polls the state.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> pipeline(Path sr, String action, Map<String, String> q, Cli cli) throws IOException {
        if (running(sr)) {
            throw new IllegalArgumentException("The pipeline is still running. Wait for this step to finish.");
        }
        Map<String, Object> st = loadState(sr);
        Map<String, Object> stages = (Map<String, Object>) st.getOrDefault("stages", Map.of());
        Runnable job;
        switch (action) {
            case "start" -> {
                String by = name(q.get("by"));
                String file = q.get("file");
                String input = q.getOrDefault("input", "ref");
                if (file == null && !INPUTS.contains(input)) {
                    throw new IllegalArgumentException("unknown input");
                }
                if (!available()) {
                    throw new IllegalArgumentException("SpecForge is not installed next to the gate (../specforge).");
                }
                reset(sr);
                Map<String, Object> fresh = loadState(sr);
                fresh.put("by", by);
                fresh.put("stages", new LinkedHashMap<>());
                fresh.put("logs", new ArrayList<>());
                saveState(sr, fresh);
                job = () -> {
                    if (file != null) {
                        step(sr, "spec", "running", "SpecForge 的 AI 正在讀規格書", "extract", Map.of("file", file, "keep", "1"), cli);
                    } else {
                        step(sr, "spec", "running", "SpecForge 正在檢查規格", "load", Map.of("input", input, "keep", "1"), cli);
                    }
                    afterSpec(sr, by, cli);
                };
            }
            case "answer" -> {
                if (!"needs-answers".equals(stageStatus(stages, "spec"))) {
                    throw new IllegalArgumentException("No questions are waiting for answers.");
                }
                String by = String.valueOf(st.getOrDefault("by", "PM"));
                String answers = q.getOrDefault("answers", "[]");
                job = () -> {
                    step(sr, "spec", "running", "SpecForge 的 AI 正在把 PM 的答案寫回規格", "refine", Map.of("answers", answers), cli);
                    afterSpec(sr, by, cli);
                };
            }
            case "approve" -> {
                if (!"needs-human".equals(stageStatus(stages, "approve"))) {
                    throw new IllegalArgumentException("Nothing is waiting for an approval.");
                }
                Map<String, String> p = new LinkedHashMap<>();
                p.put("by", name(q.get("by")));
                p.put("note", q.getOrDefault("note", ""));
                p.put("acceptFindings", q.getOrDefault("acceptFindings", "false"));
                p.put("ackHigh", q.getOrDefault("ackHigh", "false"));
                List<String> errs = projectErrors(ws(sr).resolve("gate"));
                if (!errs.isEmpty() && !"true".equals(p.get("acceptFindings"))) {
                    throw new IllegalArgumentException("專案有擋下的問題（" + String.join("；", errs)
                            + "）。要核准，請勾「我接受專案的已知問題」，它們會具名寫進核准紀錄。");
                }
                Map<String, Object> run = runResult(sr);
                if (run != null && !"true".equals(p.get("ackHigh"))) {
                    for (Object go : (List<Object>) run.get("gates")) {
                        Map<String, Object> g = (Map<String, Object>) go;
                        if ("G5".equals(g.get("id")) && g.get("details") instanceof Map<?, ?> d
                                && d.get("highFindings") instanceof Number n && n.intValue() > 0) {
                            throw new IllegalArgumentException("AI 審查有 " + n + " 個 HIGH 問題。要核准，請勾「我接受這個風險」。");
                        }
                    }
                }
                job = () -> {
                    step(sr, "approve", "running", "記錄核准", "approve", p, cli);
                    if (loadStateQuiet(sr).get("approved") == null) {
                        throw new IllegalStateException("The approval was refused (see the output).");
                    }
                    setStage(sr, "approve", "done", p.get("by") + " 核准");
                    step(sr, "release", "running", "寫入發布紀錄，release-check", "release", Map.of(), cli);
                    setStage(sr, "release", "done", "已發布（不推送、不部署）");
                    step(sr, "canary", "running", "600 筆交易同時送新舊版本", "canary", Map.of("mode", "release"), cli);
                    Object v = loadStateQuiet(sr).get("canary-release");
                    setStage(sr, "canary", "PROMOTE".equals(v) ? "done" : "failed", "PROMOTE".equals(v) ? "PROMOTE：可以上線" : "ROLLBACK：自動回滾");
                    step(sr, "evidence", "running", "產生證據包並寄給收件人", "evidence", Map.of(), cli);
                    setStage(sr, "evidence", "done", "證據包已產生並寄出");
                    setStage(sr, "feedback", "optional", "可以跑事故演練，看事故怎麼回到規格");
                };
            }
            case "drill" -> {
                if (loadState(sr).get("released") == null) {
                    throw new IllegalArgumentException("Approve and release first.");
                }
                String by = String.valueOf(st.getOrDefault("by", "PM"));
                job = () -> {
                    step(sr, "feedback", "running", "事故演練：植入缺陷的版本當金絲雀", "canary", Map.of("mode", "drill"), cli);
                    step(sr, "feedback", "running", "SpecForge：答錯的交易變成新範例", "incident", Map.of("mode", "drill"), cli);
                    step(sr, "feedback", "running", "鎖成 v1.1 並匯出", "lock-next", Map.of("by", by), cli);
                    step(sr, "feedback", "running", "閘門用 v1.1 重跑", "rerun", Map.of(), cli);
                    setStage(sr, "feedback", "done", "v1.1 已鎖版：植入缺陷的程式被擋，SpecBlast 的程式通過");
                };
            }
            default -> throw new IllegalArgumentException("unknown pipeline action");
        }
        Thread t = new Thread(() -> {
            try {
                job.run();
            } catch (RuntimeException e) {
                failRunning(sr, e.getCause() != null && e instanceof Halt ? e.getCause().getMessage() : e.getMessage());
            }
        }, "chain-pipeline");
        t.setDaemon(true);
        jobs.put(sr, t);
        t.start();
        return Map.of("started", action);
    }

    /** After SpecForge: questions open -> wait for the PM; else lock, export, SpecBlast, gate, and wait for the approver. */
    @SuppressWarnings("unchecked")
    private void afterSpec(Path sr, String by, Cli cli) {
        try {
            Path spec = ws(sr).resolve("spec");
            List<Object> findings = (List<Object>) Json.parse(Files.readString(spec.resolve("findings.json")));
            long errors = findings.stream().filter(f -> "ERROR".equals(((Map<String, Object>) f).get("severity"))).count();
            if (errors > 0) {
                setStage(sr, "spec", "needs-answers", errors + " 個問題要 PM 回答，回答前不能鎖版");
                return;
            }
        } catch (IOException e) {
            throw new Halt(e);
        }
        step(sr, "spec", "running", "鎖版（" + by + "）", "lock", Map.of("by", by), cli);
        step(sr, "spec", "running", "匯出給 SpecBlast 與閘門", "export", Map.of(), cli);
        setStage(sr, "spec", "done", "v1.0 由 " + by + " 鎖版");
        step(sr, "specblast", "running", "SpecBlast 正在產生整個專案（約 3 分鐘）", "specblast", Map.of(), cli);
        Map<String, Object> st = loadStateQuiet(sr);
        setStage(sr, "specblast", "done", "saved".equals(st.get("specblast")) ? "SpecBlast 的真實產出（2026-11-02，同一份規格）" : "SpecBlast 剛產生的專案");
        step(sr, "gate", "running", "P1–P7 與 G1–G5", "verify", Map.of(), cli);
        Map<String, Object> run = runResult(sr);
        if (run == null) {
            String why = "閘門沒有跑完";
            for (Object o : (List<Object>) loadStateQuiet(sr).getOrDefault("logs", List.of())) {
                Map<String, Object> e = (Map<String, Object>) o;
                if ("gate".equals(e.get("stage"))) {
                    for (String line : String.valueOf(e.get("output")).split("\n")) {
                        if (line.startsWith("Error:")) {
                            why = line.substring(6).strip();
                        }
                    }
                }
            }
            if (why.contains("sensitive data")) {
                why = "DLP 擋下：送給 AI 的內容裡有看起來像真實卡號的資料，什麼都沒有送出。（" + why + "）";
            }
            setStage(sr, "gate", "failed", why);
            return;
        }
        String verdict = String.valueOf(run.get("verdict"));
        if ("BLOCKED".equals(verdict)) {
            setStage(sr, "gate", "failed", "閘門擋下：程式不會上線");
            return;
        }
        setStage(sr, "gate", "done", "全部自動關卡通過");
        setStage(sr, "approve", "needs-human", "等具名的人核准");
    }

    /** One step of the pipeline: shows it running, runs it, keeps its output; a failure stops the pipeline. */
    @SuppressWarnings("unchecked")
    private void step(Path sr, String stage, String status, String note, String act, Map<String, String> q, Cli cli) {
        setStage(sr, stage, status, note);
        try {
            Map<String, Object> r = act(sr, act, new LinkedHashMap<>(q), cli);
            Map<String, Object> st = loadState(sr);
            List<Object> logs = (List<Object>) st.computeIfAbsent("logs", k -> new ArrayList<>());
            for (Object o : (List<Object>) r.getOrDefault("log", List.of())) {
                Map<String, Object> e = new LinkedHashMap<>((Map<String, Object>) o);
                e.put("stage", stage);
                String out = String.valueOf(e.getOrDefault("output", ""));
                e.put("output", out.length() > 8000 ? out.substring(0, 8000) + "\n…" : out);
                e.remove("result");
                logs.add(e);
            }
            saveState(sr, st);
        } catch (IOException | InterruptedException | RuntimeException e) {
            throw new Halt(e);
        }
    }

    private static final class Halt extends RuntimeException {
        Halt(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    private void setStage(Path sr, String stage, String status, String note) {
        try {
            Map<String, Object> st = loadState(sr);
            Map<String, Object> stages = (Map<String, Object>) st.computeIfAbsent("stages", k -> new LinkedHashMap<>());
            stages.put(stage, Map.of("status", status, "note", note, "at", now()));
            saveState(sr, st);
        } catch (IOException e) {
            throw new Halt(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void failRunning(Path sr, String why) {
        try {
            Map<String, Object> st = loadState(sr);
            Map<String, Object> stages = (Map<String, Object>) st.computeIfAbsent("stages", k -> new LinkedHashMap<>());
            for (String k : STAGES) {
                if ("running".equals(stageStatus(stages, k))) {
                    stages.put(k, Map.of("status", "failed", "note", String.valueOf(why), "at", now()));
                }
            }
            st.put("error", String.valueOf(why));
            saveState(sr, st);
        } catch (IOException ignored) {
            // nothing more to report
        }
    }

    @SuppressWarnings("unchecked")
    private static String stageStatus(Map<String, Object> stages, String k) {
        Object v = stages.get(k);
        return v instanceof Map<?, ?> m ? String.valueOf(m.get("status")) : "";
    }

    private Map<String, Object> loadStateQuiet(Path sr) {
        try {
            return loadState(sr);
        } catch (IOException e) {
            throw new Halt(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runResult(Path sr) {
        try {
            Map<String, Object> st = loadState(sr);
            if (st.get("run") instanceof String run && run.matches(RUN_RE)) {
                Path f = ws(sr).resolve("gate/runs/specblast").resolve(run).resolve("result.json");
                return Files.exists(f) ? (Map<String, Object>) Json.parse(Files.readString(f)) : null;
            }
            return null;
        } catch (IOException e) {
            throw new Halt(e);
        }
    }

    /** Both versions served in this process; canary-check (the same command as the workflows) compares them. */
    private Map<String, Object> canary(Path g, Path stable, Path canary, Cli cli) throws IOException, InterruptedException {
        HttpServer s = null, c = null;
        try {
            s = ReleaseServer.startLocal(g, stable);
            c = ReleaseServer.startLocal(g, canary);
            Map<String, Object> r = new LinkedHashMap<>(cli.gate(g, List.of("canary-check",
                    "--stable", "http://127.0.0.1:" + s.getAddress().getPort(),
                    "--canary", "http://127.0.0.1:" + c.getAddress().getPort(), "--n", "600",
                    "--source", canary.resolve("AuthorizationPrecheckImpl.java").toString()), false));
            StringBuilder shown = new StringBuilder();
            for (String line : String.valueOf(r.get("output")).split("\n")) {
                if (line.startsWith("CANARY_RESULT ")) {
                    r.put("result", line.substring(14));
                } else {
                    shown.append(line).append('\n');
                }
            }
            r.put("output", shown.toString());
            return r;
        } finally {
            if (s != null) {
                s.stop(0);
            }
            if (c != null) {
                c.stop(0);
            }
        }
    }

    // ------------------------------------------------------------------ state for the page

    @SuppressWarnings("unchecked")
    Map<String, Object> state(Path sr) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("specforge", available());
        Path ws = ws(sr);
        Map<String, Object> st = loadState(sr);
        out.put("progress", st);
        Path spec = ws.resolve("spec");
        if (Files.exists(spec.resolve("canonical.json"))) {
            Map<String, Object> s1 = new LinkedHashMap<>();
            s1.put("spec", Json.parse(Files.readString(spec.resolve("canonical.json"))));
            s1.put("findings", Files.exists(spec.resolve("findings.json")) ? Json.parse(Files.readString(spec.resolve("findings.json"))) : List.of());
            if (Files.exists(spec.resolve("answers.template.json"))) {
                s1.put("answers", Json.parse(Files.readString(spec.resolve("answers.template.json"))));
            }
            if (Files.exists(spec.resolve("source.txt"))) {
                s1.put("source", Files.readString(spec.resolve("source.txt")));
            }
            if (Files.exists(spec.resolve("canonical.locked.json"))) {
                Map<String, Object> l = (Map<String, Object>) Json.parse(Files.readString(spec.resolve("canonical.locked.json")));
                s1.put("approval", l.get("approval"));
            }
            List<String> files = new ArrayList<>();
            for (String f : List.of("specblast/requirements.md", "specblast/openapi.yaml", "gate/spec.md", "gate/golden-tests.json")) {
                if (Files.exists(ws.resolve("export").resolve(f))) {
                    files.add(f);
                }
            }
            s1.put("exported", files);
            if (Files.exists(ws.resolve("export/gate/golden-tests.json"))) {
                Object g = Json.parse(Files.readString(ws.resolve("export/gate/golden-tests.json")));
                s1.put("goldenCount", g instanceof Map<?, ?> m && m.get("cases") instanceof List<?> l ? l.size() : null);
            }
            out.put("s1", s1);
        }
        Path p = ws.resolve("specblast-project");
        if (Files.isDirectory(p)) {
            Map<String, Object> s2 = new LinkedHashMap<>();
            List<String> files = new ArrayList<>();
            try (Stream<Path> w = Files.walk(p)) {
                w.filter(Files::isRegularFile).forEach(f -> files.add(p.relativize(f).toString()));
            }
            files.sort(null);
            s2.put("files", files);
            try (Stream<Path> w = Files.walk(p)) {
                Path rules = w.filter(f -> f.getFileName().toString().equals("AuthorizationRules.java")).findFirst().orElse(null);
                if (rules != null) {
                    s2.put("rulesPath", p.relativize(rules).toString());
                    s2.put("rules", Files.readString(rules));
                }
            }
            if (Files.exists(ws.resolve("export/specblast/requirements.md"))) {
                s2.put("requirements", Files.readString(ws.resolve("export/specblast/requirements.md")));
            }
            out.put("s2", s2);
        }
        Path g = ws.resolve("gate");
        if (st.get("run") instanceof String run && run.matches(RUN_RE)) {
            Path rf = g.resolve("runs/specblast").resolve(run).resolve("result.json");
            if (Files.exists(rf)) {
                out.put("run", Json.parse(Files.readString(rf)));
            }
            Path pr = latestProject(g);
            if (pr != null) {
                out.put("project", Json.parse(Files.readString(pr)));
            }
        }
        Path rel = g.resolve("release/iso8583/approval.json");
        if (st.get("released") != null && Files.exists(rel)) {
            out.put("release", Json.parse(Files.readString(rel)));
        }
        for (String m : List.of("release", "drill")) {
            Path c = ws.resolve("canary-" + m + ".json");
            if (Files.exists(c)) {
                out.put("canary-" + m, Json.parse(Files.readString(c)));
            }
        }
        if (Files.exists(ws.resolve("incident/incident.md"))) {
            Map<String, Object> s7 = new LinkedHashMap<>();
            s7.put("report", Files.readString(ws.resolve("incident/incident.md")));
            Map<String, Object> draft = (Map<String, Object>) Json.parse(Files.readString(ws.resolve("incident/canonical.json")));
            int added = 0;
            for (Object r : (List<Object>) draft.getOrDefault("rules", List.of())) {
                for (Object e : (List<Object>) ((Map<String, Object>) r).getOrDefault("examples", List.of())) {
                    added += Boolean.TRUE.equals(((Map<String, Object>) e).get("fromIncident")) ? 1 : 0;
                }
            }
            s7.put("added", added);
            s7.put("questions", draft.getOrDefault("openQuestions", List.of()));
            if (Files.exists(ws.resolve("incident/canonical.locked.json"))) {
                s7.put("approval", ((Map<String, Object>) Json.parse(Files.readString(ws.resolve("incident/canonical.locked.json")))).get("approval"));
            }
            if (Boolean.TRUE.equals(st.get("rerun"))) {
                List<Object> reruns = new ArrayList<>();
                for (Path d : List.of(g.resolve("runs"), g.resolve("runs/specblast"))) {
                    if (Files.isDirectory(d)) {
                        try (Stream<Path> w = Files.list(d)) {
                            for (Path r : w.filter(x -> x.getFileName().toString().matches("run-.*-(a1|sb)")).sorted().toList()) {
                                Path f = r.resolve("result.json");
                                if (Files.exists(f)) {
                                    Map<String, Object> m = (Map<String, Object>) Json.parse(Files.readString(f));
                                    if (m.get("specSource") instanceof Map<?, ?> ss && "1.1".equals(String.valueOf(ss.get("version")))
                                            || "1".equals(String.valueOf(m.get("attempt"))) && r.getFileName().toString().endsWith("-a1")) {
                                        reruns.add(Map.of("runId", m.get("runId"), "verdict", m.get("verdict"),
                                                "spec", m.get("specSource") instanceof Map<?, ?> s2 ? String.valueOf(s2.get("version")) : "?",
                                                "code", r.getFileName().toString().endsWith("-a1") ? "attempt-1" : "specblast",
                                                "g3", g3(m)));
                                    }
                                }
                            }
                        }
                    }
                }
                s7.put("reruns", reruns);
            }
            out.put("s7", s7);
        }
        if (st.get("approved") instanceof String run && run.matches(RUN_RE)
                && Files.exists(g.resolve("runs/specblast").resolve(run).resolve("evidence/evidence.html"))) {
            out.put("evidence", run);
        }
        return out;
    }

    Path evidenceFile(Path sr) throws IOException {
        Map<String, Object> st = loadState(sr);
        if (st.get("approved") instanceof String run && run.matches(RUN_RE)) {
            Path f = ws(sr).resolve("gate/runs/specblast").resolve(run).resolve("evidence/evidence.html");
            return Files.exists(f) ? f : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String g3(Map<String, Object> m) {
        for (Object o : (List<Object>) m.getOrDefault("gates", List.of())) {
            Map<String, Object> g = (Map<String, Object>) o;
            if ("G3".equals(g.get("id"))) {
                return String.valueOf(g.get("summary"));
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<String> projectErrors(Path g) throws IOException {
        List<String> out = new ArrayList<>();
        Path pr = latestProject(g);
        if (pr == null) {
            return out;
        }
        Map<String, Object> r = (Map<String, Object>) Json.parse(Files.readString(pr));
        for (Object go : (List<Object>) r.get("gates")) {
            for (Object fo : (List<Object>) ((Map<String, Object>) go).get("findings")) {
                Map<String, Object> f = (Map<String, Object>) fo;
                if ("ERROR".equals(f.get("severity"))) {
                    out.add(f.get("rule") + " " + f.get("title"));
                }
            }
        }
        return out;
    }

    private static Path latestProject(Path g) throws IOException {
        Path d = g.resolve("runs/projects");
        if (!Files.isDirectory(d)) {
            return null;
        }
        try (Stream<Path> st = Files.list(d)) {
            return st.filter(p -> Files.exists(p.resolve("result.json"))).max(Comparator.naturalOrder())
                    .map(p -> p.resolve("result.json")).orElse(null);
        }
    }

    private static String latest(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> st = Files.list(dir)) {
            return st.map(p -> p.getFileName().toString()).filter(n -> n.matches(RUN_RE)).max(Comparator.naturalOrder()).orElse(null);
        }
    }

    private static String name(String s) {
        String n = s == null ? "" : s.replaceAll("[\\r\\n\"<>]", " ").strip();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("A named person must do this: enter a name.");
        }
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    private static void need(Path p, String why) {
        if (!Files.exists(p)) {
            throw new IllegalArgumentException(why);
        }
    }

    private static String lastLine(Map<String, Object> r) {
        String[] l = String.valueOf(r.get("output")).strip().split("\n");
        return l.length == 0 ? "" : l[l.length - 1];
    }

    private static String now() {
        return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    static void copyTree(Path from, Path to) throws IOException {
        if (!Files.exists(from)) {
            return;
        }
        Path src = from.toRealPath();
        try (Stream<Path> st = Files.walk(src)) {
            for (Path p : st.toList()) {
                Path t = to.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.createDirectories(t.getParent());
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> st = Files.walk(p)) {
            for (Path x : st.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(x); // links are removed, never followed
            }
        }
    }

    static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
