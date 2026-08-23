package io.agentteams.manager;

/**
 * Token usage used for pricing calculations.
 *
 * <p>This is measured usage, not a request limit or a billing statement.
 */
public record ModelTokenUsage(long inputTokens, long outputTokens) {
    public ModelTokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token usage must not be negative");
        }
    }
}
