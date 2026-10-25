package dev.aiverify.pipeline;

import dev.aiverify.ai.LlmClient;
import dev.aiverify.contract.AuthorizationPrecheck;
import dev.aiverify.testing.TestCase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything one pipeline run knows. Every AI answer is saved to the run folder and hashed for the audit trail. */
public final class RunContext {

    public static final String CANDIDATE_CLASS = "dev.aiverify.candidate.AuthorizationPrecheckImpl";

    public final Path projectRoot;
    public final Path runDir;
    public final String runId;
    public final int attempt;
    public final LlmClient ai;

    public String goldenVersion = "latest";
    public String spec;
    public String contractSource;
    public String candidateSource;
    public List<TestCase> goldenTests = new ArrayList<>();
    public List<TestCase> aiTests = new ArrayList<>();
    public Class<?> compiledClass;
    public Map<String, Object> diagnosis;

    /** role -> {file, sha256} for every AI exchange in this run. */
    public final Map<String, Object> aiExchanges = new LinkedHashMap<>();

    public RunContext(Path projectRoot, Path runDir, String runId, int attempt, LlmClient ai) {
        this.projectRoot = projectRoot;
        this.runDir = runDir;
        this.runId = runId;
        this.attempt = attempt;
        this.ai = ai;
    }

    public AuthorizationPrecheck newCandidate() {
        try {
            return (AuthorizationPrecheck) compiledClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create the candidate: " + e.getMessage(), e);
        }
    }

    /** Calls one AI role in a fresh context and stores prompt and answer for the audit trail. */
    public String askAi(String role, String system, String user) {
        // Responsible AI: check the prompt BEFORE it leaves the machine. Blocked prompts are never sent.
        java.util.List<String> problems = dev.aiverify.ai.AiPolicy.scanOutbound(system + "\n" + user);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Blocked: the prompt for the " + role
                    + " contains sensitive data " + problems + ". Nothing was sent to the model.");
        }
        java.util.List<String> injection = dev.aiverify.ai.AiPolicy.scanInjection(user);
        if (!injection.isEmpty()) {
            throw new IllegalStateException("Blocked: the input for the " + role + " contains text that tries to "
                    + "steer the AI " + injection + ". Nothing was sent to the model. A human must review the input.");
        }
        String answer = ai.complete(role, system, user);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("promptFile", "ai/" + role + ".prompt.txt");
        rec.put("answerFile", "ai/" + role + ".answer.txt");
        rec.put("promptSha256", Hashing.sha256(system + "\n---\n" + user));
        rec.put("answerSha256", Hashing.sha256(answer));
        rec.put("outboundCheck", "PASSED: no card numbers, secrets, emails or national IDs in the prompt");
        rec.put("injectionCheck", "PASSED: no instructions aimed at the AI in the input");
        write("ai/" + role + ".prompt.txt", "### SYSTEM\n" + system + "\n### USER\n" + user);
        write("ai/" + role + ".answer.txt", answer);
        aiExchanges.put(role, rec);
        return answer;
    }

    public void write(String relative, String content) {
        try {
            Path p = runDir.resolve(relative);
            Files.createDirectories(p.getParent());
            Files.writeString(p, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
