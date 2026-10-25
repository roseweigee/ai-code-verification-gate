package dev.aiverify.report;

import dev.aiverify.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One self-contained HTML page for every project verification in runs/projects (oldest first). */
public final class ProjectReportWriter {

    private static final String PLACEHOLDER = "/*__REPORT_DATA__*/null";

    public static Path write(Path gateRoot) throws IOException {
        Path dir = gateRoot.resolve("runs/projects");
        List<Object> results = new ArrayList<>();
        try (var s = Files.list(dir)) {
            for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                Path r = d.resolve("result.json");
                if (Files.exists(r)) {
                    results.add(Json.parse(Files.readString(r)));
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("generatedAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("projects", results);
        String template = Files.readString(gateRoot.resolve("report/project-template.html"));
        Path out = dir.resolve("report.html");
        Files.writeString(out, template.replace(PLACEHOLDER, Json.writeCompact(data)));
        return out;
    }

    private ProjectReportWriter() {
    }
}
