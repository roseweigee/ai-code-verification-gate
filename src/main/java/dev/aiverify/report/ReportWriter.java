package dev.aiverify.report;

import dev.aiverify.json.Json;
import dev.aiverify.pipeline.AuditLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds one self-contained HTML report (no server, no CDN) from the result.json of every run. */
public final class ReportWriter {

    private static final String PLACEHOLDER = "/*__REPORT_DATA__*/null";

    public static Path write(Path projectRoot) throws IOException {
        Path runs = projectRoot.resolve("runs");
        List<Object> results = new ArrayList<>();
        try (var s = Files.list(runs)) {
            for (Path dir : s.filter(Files::isDirectory).sorted().toList()) {
                Path r = dir.resolve("result.json");
                if (Files.exists(r)) {
                    results.add(Json.parse(Files.readString(r)));
                }
            }
        }
        AuditLog audit = new AuditLog(runs.resolve("audit-log.jsonl"));
        String broken = audit.verify();
        Map<String, Object> auditInfo = new LinkedHashMap<>();
        auditInfo.put("entries", audit.size());
        auditInfo.put("intact", broken == null);
        auditInfo.put("problem", broken);
        List<Object> entries = new ArrayList<>();
        Path logFile = runs.resolve("audit-log.jsonl");
        if (Files.exists(logFile)) {
            for (String line : Files.readAllLines(logFile)) {
                if (!line.isBlank()) {
                    entries.add(Json.parse(line));
                }
            }
        }
        auditInfo.put("log", entries);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("spec", Files.readString(projectRoot.resolve("spec/authorization-precheck.md")));
        data.put("runs", results);
        data.put("audit", auditInfo);

        String template = Files.readString(projectRoot.resolve("report/template.html"));
        if (!template.contains(PLACEHOLDER)) {
            throw new IllegalStateException("report/template.html is missing the data placeholder");
        }
        Path out = runs.resolve("report.html");
        Files.writeString(out, template.replace(PLACEHOLDER, Json.writeCompact(data)));
        return out;
    }

    private ReportWriter() {
    }
}
