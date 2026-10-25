package dev.aiverify.ai;

import dev.aiverify.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Live mode: calls the Claude Messages API. The key comes from ANTHROPIC_API_KEY and is never logged. */
public final class ClaudeClient implements LlmClient {

    private static final URI ENDPOINT = URI.create("https://api.anthropic.com/v1/messages");

    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public ClaudeClient(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("ANTHROPIC_API_KEY is not set. Run without --live to use replay mode.");
        }
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String role, String system, String user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", 8000);
        body.put("system", system);
        body.put("messages", List.of(Map.of("role", "user", "content", user)));

        HttpRequest req = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(3))
                .header("content-type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(Json.writeCompact(body)))
                .build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Claude API returned HTTP " + res.statusCode() + " for role '"
                        + role + "': " + res.body());
            }
            Map<String, Object> json = Json.parseObject(res.body());
            StringBuilder text = new StringBuilder();
            for (Object block : (List<?>) json.get("content")) {
                Map<?, ?> b = (Map<?, ?>) block;
                if ("text".equals(b.get("type"))) {
                    text.append(b.get("text"));
                }
            }
            return text.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reach the Claude API: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the Claude API", e);
        }
    }

    @Override
    public String describe() {
        return "live: " + model;
    }
}
