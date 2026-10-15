package io.github.markpollack.journal.antigravity;

/**
 * The token usage of one model call in an Antigravity stream: the {@code usage} object a
 * {@code step_update} carries on an {@code agent_response} step. Unlike Claude Code's stream, the figures
 * are final values, and across a run they add up to the {@code result} event's usage.
 *
 * @param stepIndex       the step's index in the conversation
 * @param stepType        the step's type, for example {@code agent_response}
 * @param durationMs      how long the step took in milliseconds, or -1 if it did not say
 * @param inputTokens     the input tokens
 * @param outputTokens    the output tokens, including thinking tokens
 * @param thinkingTokens  the thinking tokens, which are part of {@code outputTokens}
 * @param cacheReadTokens the tokens read from cache
 */
public record AntigravityCallUsage(
        int stepIndex,
        String stepType,
        long durationMs,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheReadTokens) {
}
