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
    }

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
        Files.writeString(ws(sessionRoot).resolve("state.json"), Json.write(s));
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
                reset(sr);
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
                copyTree(root.resolve("chain/examples/specblast-output"), p);
                log.add(Map.of("command", "(SpecBlast project: chain/examples/specblast-output)", "exitCode", 0,
                        "output", "SpecBlast generated this project on 2026-11-02 from the same export (spec v1.0 and the gate's contract). "
                                + "Unchanged, including SpecBlast's own mistakes."));
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
