package dev.aiverify.pipeline;

import dev.aiverify.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained audit log (one JSON object per line).
 * Each entry stores the hash of the previous entry, so editing or deleting any past
 * entry breaks the chain and {@link #verify} reports where.
 */
public final class AuditLog {

    private static final String GENESIS = "0".repeat(64);
    private final Path file;

    public AuditLog(Path file) {
        this.file = file;
    }

    public synchronized Map<String, Object> append(Map<String, Object> event) {
        try {
            Files.createDirectories(file.getParent());
            String prev = lastHash();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("prevHash", prev);
            entry.putAll(event);
            String hash = Hashing.sha256(prev + Json.writeCompact(event));
            entry.put("entryHash", hash);
            Files.writeString(file, Json.writeCompact(entry) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return entry;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns null if the chain is intact, otherwise a description of the first broken entry. */
    @SuppressWarnings("unchecked")
    public String verify() {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String prev = GENESIS;
            List<String> lines = Files.readAllLines(file);
            for (int k = 0; k < lines.size(); k++) {
                if (lines.get(k).isBlank()) {
                    continue;
                }
                Map<String, Object> entry = (Map<String, Object>) Json.parse(lines.get(k));
                Map<String, Object> event = new LinkedHashMap<>(entry);
                String storedPrev = (String) event.remove("prevHash");
                String storedHash = (String) event.remove("entryHash");
                if (!prev.equals(storedPrev)) {
                    return "Entry " + (k + 1) + ": previous-hash link is broken (an entry was removed or reordered).";
                }
                if (!Hashing.sha256(prev + Json.writeCompact(event)).equals(storedHash)) {
                    return "Entry " + (k + 1) + ": content does not match its hash (the entry was edited).";
                }
                prev = storedHash;
            }
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int size() {
        try {
            return Files.exists(file) ? (int) Files.readAllLines(file).stream().filter(l -> !l.isBlank()).count() : 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private String lastHash() throws IOException {
        if (!Files.exists(file)) {
            return GENESIS;
        }
        List<String> lines = Files.readAllLines(file);
        for (int k = lines.size() - 1; k >= 0; k--) {
            if (!lines.get(k).isBlank()) {
                return (String) ((Map<String, Object>) Json.parse(lines.get(k))).get("entryHash");
            }
        }
        return GENESIS;
    }
}
