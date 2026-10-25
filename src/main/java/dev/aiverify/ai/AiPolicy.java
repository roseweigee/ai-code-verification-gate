package dev.aiverify.ai;

import dev.aiverify.gates.PolicyGate;
import dev.aiverify.json.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsible AI guardrails around every model call:
 *  1. only approved models may be called (config/ai-policy.json)
 *  2. nothing sensitive leaves the machine: every prompt is scanned before it is sent,
 *     and the call is blocked if it contains a card number, a secret, an email or a national ID.
 */
public final class AiPolicy {

    private static final Pattern DIGITS = Pattern.compile("(?<![0-9])[0-9]{13,19}(?![0-9])");
    private static final Pattern SECRET = Pattern.compile("(?i)sk-ant-[A-Za-z0-9_-]{8,}|(password|secret|api[_-]?key)\\s*[=:]\\s*\\S{6,}");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TW_ID = Pattern.compile("(?<![A-Za-z0-9])[A-Z][12][0-9]{8}(?![0-9])");

    private static final Pattern INJECTION = Pattern.compile("(?i)ignore (all |any )?(the )?(previous|prior|above|earlier) "
            + "(instructions|rules)|disregard (the |all )?(previous|prior|system|above)|you are now\\b|new instructions\\s*:"
            + "|reveal (the |your )?system prompt|do not (tell|inform) the (user|reviewer|human)"
            + "|(this|the) code is (safe|secure|correct)[,.]? (approve|no need)|<\\|im_start\\|>|\\[/?INST\\]");

    private final List<String> approvedModels;
    private final String provider;

    @SuppressWarnings("unchecked")
    public AiPolicy(Path projectRoot) {
        try {
            Map<String, Object> cfg = Json.parseObject(Files.readString(projectRoot.resolve("config/ai-policy.json")));
            this.approvedModels = (List<String>) (List<?>) cfg.get("approvedModels");
            this.provider = String.valueOf(cfg.get("approvedProvider"));
        } catch (IOException e) {
            throw new UncheckedIOException("config/ai-policy.json is required", e);
        }
    }

    public List<String> approvedModels() {
        return approvedModels;
    }

    public String provider() {
        return provider;
    }

    /**
     * Prompt injection: text written to steer an AI instead of informing it, for example an instruction
     * hidden in a client's spec, or a comment in generated code addressed to the AI reviewer.
     * Returns the phrases found (empty list = none).
     */
    public static List<String> scanInjection(String text) {
        List<String> found = new ArrayList<>();
        Matcher m = INJECTION.matcher(text);
        while (m.find()) {
            found.add("\"" + m.group() + "\"");
        }
        return found;
    }

    /** Principle 1: approved tools only. */
    public void requireApprovedModel(String model) {
        if (!approvedModels.contains(model)) {
            throw new IllegalStateException("Model '" + model + "' is not on the approved list in config/ai-policy.json "
                    + approvedModels + ". Ask the architect to review and approve it first.");
        }
    }

    /**
     * Principle 2: no sensitive data in prompts. Returns the problems found (empty list = safe to send).
     * The matched values themselves are never returned, so they cannot leak into logs.
     */
    public static List<String> scanOutbound(String text) {
        List<String> problems = new ArrayList<>();
        Matcher m = DIGITS.matcher(text);
        while (m.find()) {
            if (PolicyGate.luhn(m.group())) {
                problems.add("card number (" + m.group().length() + " digits, passes Luhn)");
            }
        }
        if (SECRET.matcher(text).find()) {
            problems.add("API key or password");
        }
        if (EMAIL.matcher(text).find()) {
            problems.add("email address");
        }
        if (TW_ID.matcher(text).find()) {
            problems.add("Taiwan national ID number");
        }
        return problems;
    }
}
