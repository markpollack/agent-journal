package io.github.markpollack.journal.claude;

import java.util.List;

/**
 * The token usage of one turn of a Claude Code call; a turn is one assistant message, which is
 * one API request. {@link SessionLogParser} reads it from the usage of each assistant message,
 * and {@link PhaseCapture#turns()} holds one per turn. {@link JournalSteps} uses the turns to
 * split a phase's cost across its steps, and the run recorder stores them with the phase's LLM
 * call so that the split can be done again from the events.
 *
 * <p>A turn has no cost: Claude Code reports cost only for the whole call and per model
 * ({@link ModelCost}).
 *
 * <p>The turns' token counts do not add up to the counts on the final result message, and are
 * not meant to. Each turn's input and cache counts measure one request over a context that grows,
 * so the same prompt is counted again on every turn, while the result message reports a
 * snapshot. For a total to price, add up each token type over the turns, as
 * {@link PhaseCapture#aggregateUsage()} does. To check that a capture is complete, compare costs
 * with {@link PhaseCapture#reconcilesToModelCosts()}, not tokens.
 *
 * <p>The parser needs each message's original JSON, so turns are captured only with
 * claude-code-sdk 1.3.0 or later. The record does not copy {@code toolUseIds}.
 *
 * @param messageId the assistant message ID, such as {@code msg_...}, which identifies the turn,
 *        or {@code null} if not reported
 * @param model the model that served the turn, such as {@code claude-opus-4-8}, or {@code null}
 * @param inputTokens the input tokens, not counting cache reads and writes
 * @param outputTokens the output tokens, including thinking tokens
 * @param cacheCreationInputTokens the tokens written to the prompt cache
 * @param cacheReadInputTokens the tokens read from the prompt cache
 * @param toolUseIds the IDs of the tool calls the turn made, in order; empty if it made none
 * @param thinkingTokens the thinking tokens, which are part of {@code outputTokens}, or 0 if none
 *        were reported
 * @param stopReason why the turn stopped, as Claude reported it, such as {@code end_turn},
 *        {@code tool_use} or {@code max_tokens}, or {@code null} if not reported
 * @param turnIndex the 0-based number of the turn within the phase, or -1 if not known
 */
public record TurnUsage(
        String messageId,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens,
        List<String> toolUseIds,
        long thinkingTokens,
        String stopReason,
        int turnIndex
) {

    /**
     * Creates a turn without tool calls, thinking tokens, stop reason or turn number: the list of
     * tool-call IDs is empty, thinking tokens are 0, the stop reason is {@code null} and the turn
     * index is -1.
     *
     * @param messageId the assistant message ID, or {@code null}
     * @param model the model, or {@code null}
     * @param inputTokens the input tokens
     * @param outputTokens the output tokens
     * @param cacheCreationInputTokens the tokens written to the prompt cache
     * @param cacheReadInputTokens the tokens read from the prompt cache
     */
    public TurnUsage(String messageId, String model, long inputTokens, long outputTokens,
            long cacheCreationInputTokens, long cacheReadInputTokens) {
        this(messageId, model, inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, List.of());
    }

    /**
     * Creates a turn without thinking tokens, stop reason or turn number, for code written before
     * 1.9.0. Thinking tokens are 0, the stop reason is {@code null} and the turn index is -1.
     *
     * @param messageId the assistant message ID, or {@code null}
     * @param model the model, or {@code null}
     * @param inputTokens the input tokens
     * @param outputTokens the output tokens
     * @param cacheCreationInputTokens the tokens written to the prompt cache
     * @param cacheReadInputTokens the tokens read from the prompt cache
     * @param toolUseIds the IDs of the turn's tool calls
     */
    public TurnUsage(String messageId, String model, long inputTokens, long outputTokens,
            long cacheCreationInputTokens, long cacheReadInputTokens, List<String> toolUseIds) {
        this(messageId, model, inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
                toolUseIds, 0L, null, -1);
    }

    /**
     * Returns all input tokens of the turn: input plus cache writes plus cache reads.
     *
     * @return the sum of {@code inputTokens}, {@code cacheCreationInputTokens} and
     *         {@code cacheReadInputTokens}
     */
    public long totalInputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    /**
     * Returns the turn's stop reason as a vendor-neutral
     * {@link io.github.markpollack.journal.event.StopReason}, using
     * {@link ClaudeStopReasons#fromTurnStopReason(String)}: {@code end_turn} and
     * {@code stop_sequence} become {@code NATURAL_DONE}, {@code tool_use} becomes
     * {@code TOOL_USE}, {@code max_tokens} becomes {@code MAX_TOKENS}, {@code refusal} becomes
     * {@code REFUSAL}, and anything else, or no reason, becomes {@code UNKNOWN}.
     *
     * @return the stop reason; never {@code null}
     */
    public io.github.markpollack.journal.event.StopReason normalizedStopReason() {
        return ClaudeStopReasons.fromTurnStopReason(stopReason);
    }
}
