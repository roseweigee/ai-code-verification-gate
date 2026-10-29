package dev.aiverify.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Offline mode: returns saved model answers from replay/attempt-N/&lt;role&gt;.txt
 * (or replay/&lt;role&gt;.txt for roles shared by all attempts). Lets the demo run anywhere,
 * without an API key, with identical gates. The answers are clearly labelled as samples.
 */
public final class ReplayClient implements LlmClient {

    private final Path root;
    private final int attempt;

    public ReplayClient(Path root, int attempt) {
        this.root = root;
        this.attempt = attempt;
    }

    @Override
    public String complete(String role, String system, String user) {
        Path perAttempt = root.resolve("attempt-" + attempt).resolve(role + ".txt");
        Path shared = root.resolve(role + ".txt");
        Path file = Files.exists(perAttempt) ? perAttempt : shared;
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException("No replay answer for role '" + role + "' (looked for "
                    + perAttempt + " and " + shared + ")", e);
        }
    }

    @Override
    public String describe() {
        return "replay: sample answers from " + root.getFileName() + "/attempt-" + attempt;
    }
}
