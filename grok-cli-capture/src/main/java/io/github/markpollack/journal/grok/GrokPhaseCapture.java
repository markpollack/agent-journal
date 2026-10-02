package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/**
 * The parsed record of one Grok CLI call: what the agent wrote, thought and did, the tokens it
 * used, what it cost, and why it stopped. {@link GrokSessionParser} builds one from the CLI's
 * {@code streaming-json} output. Pass it to a {@link GrokRunRecorder} to log it as events on a
 * {@link io.github.markpollack.journal.Run}, or read its fields when you only need usage and cost.
 *
 * <p>It is the Grok counterpart of Claude Code's {@code PhaseCapture}, with less detail. Grok
 * reports a real total cost, but no per-turn usage, no timing and no turn limit. The token counts
 * cover the whole call: the parser takes them from the CLI's final {@code end} line, which adds up
 * every turn. The stop reason is Grok's own string, not a
 * {@link io.github.markpollack.journal.event.StopReason}, and the thinking text is one string, not
 * a list of blocks.
 *
 * <p>The record copies {@code toolUses} into an unmodifiable list, so a capture can be shared
 * between threads.
 *
 * @param phaseName the caller's name for this phase, such as {@code "plan"} or {@code "execute"}
 * @param promptText the prompt sent for this phase, or {@code null} if it was not captured
 * @param model the first model named in the {@code end} line's {@code modelUsage}, or
 *        {@code null} if none was named
 * @param inputTokens the input tokens, not counting cache reads
 * @param outputTokens the output tokens
 * @param thinkingTokens the reasoning tokens Grok reported
 * @param cacheCreationInputTokens the tokens written to the prompt cache
 * @param cacheReadInputTokens the tokens read from the prompt cache
 * @param totalCostUsd the total cost Grok reported, in US dollars, or 0 if none was reported
 * @param sessionId the Grok session ID, or {@code null} if no {@code end} line arrived
 * @param numTurns the number of turns Grok reported, or 0 if none was reported
 * @param isError whether the call ended in error; the parser sets it when the stop reason is
 *        {@code error} or {@code cancelled}, ignoring case, or when the output has no {@code end}
 *        line
 * @param stopReason Grok's stop reason, such as {@code "end_turn"}; {@code "incomplete"} when the
 *        parser found no {@code end} line; or {@code null} if the {@code end} line reported none
 * @param textOutput the agent's text, joined in order
 * @param thinkingOutput the agent's thinking text, joined in order with nothing added between
 *        pieces, so the thinking of different turns runs together
 * @param toolUses the tool calls, in the order they first appeared; never {@code null}
 */
public record GrokPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        double totalCostUsd,
        String sessionId,
        int numTurns,
        boolean isError,
        String stopReason,
        String textOutput,
        String thinkingOutput,
        List<GrokToolUseRecord> toolUses
) {

    /**
     * Creates a capture from all of its parts. A {@code null} {@code toolUses} becomes an empty
     * list; any other list is copied.
     */
    public GrokPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    /**
     * Returns whether the agent made any tool calls.
     *
     * @return {@code true} if {@code toolUses} is not empty
     */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    /**
     * Returns whether the agent wrote any text.
     *
     * @return {@code true} if {@code textOutput} is not {@code null} and not empty
     */
    public boolean hasOutput() {
        return textOutput != null && !textOutput.isEmpty();
    }

    /**
     * Returns the token counts as a {@link TokenUsage}. Because Grok's counts cover the whole call,
     * this usage can be priced directly, unlike the final-message snapshot of Claude Code's
     * {@code PhaseCapture}.
     *
     * @return the usage of this call; its tool-use token count is 0
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, thinkingTokens,
                cacheCreationInputTokens, cacheReadInputTokens, 0);
    }
}
