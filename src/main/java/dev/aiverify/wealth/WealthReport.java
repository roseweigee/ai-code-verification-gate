package dev.aiverify.wealth;

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

/** One self-contained HTML report (no server, no CDN) for every wealth run, from each run's result.json. */
public final class WealthReport {

    private static final String PLACEHOLDER = "/*__REPORT_DATA__*/null";

    private WealthReport() {
    }

    public static Path write(Path root, Path runs) throws IOException {
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
        data.put("spec", Files.readString(root.resolve("spec/subscription-precheck.md")));
        data.put("runs", results);
        data.put("audit", auditInfo);
        String template = Files.readString(root.resolve("report/wealth-template.html"));
        if (!template.contains(PLACEHOLDER)) {
            throw new IllegalStateException("report/wealth-template.html is missing the data placeholder");
        }
        Path out = runs.resolve("report.html");
        Files.writeString(out, template.replace(PLACEHOLDER, Json.writeCompact(data)));
        return out;
    }
}
