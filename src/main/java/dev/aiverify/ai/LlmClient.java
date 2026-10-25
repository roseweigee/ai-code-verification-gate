package dev.aiverify.ai;

/**
 * One call to a language model. Each AI role (author, test writer, reviewer, diagnostician)
 * gets its own fresh call: no role ever sees another role's conversation.
 */
public interface LlmClient {

    /**
     * @param role   which agent is calling: "author", "test-writer", "reviewer", "diagnostician"
     * @param system the role's instructions
     * @param user   the role's input (spec, code, failing cases ...)
     * @return the raw text answer
     */
    String complete(String role, String system, String user);

    /** Human-readable description for the report and the audit log, e.g. "live: claude-sonnet-5-5". */
    String describe();
}
