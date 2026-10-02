package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/**
 * The parsed record of one Antigravity CLI call: the agent's final response, the tool steps it
 * ran, the tokens it used, how long it took, and whether it succeeded.
 * {@link AntigravitySessionParser} builds one from the CLI's {@code stream-json} output. Pass it to
 * an {@link AntigravityRunRecorder} to log it as events on a
 * {@link io.github.markpollack.journal.Run}, or read its fields when you only need usage and
 * outcome.
 *
 * <p>It is the Antigravity counterpart of Claude Code's {@code PhaseCapture}, with less detail.
 * Antigravity reports no cost, so the record has no cost field, and the recorder logs a cost of 0
 * marked {@code costAvailable=false}. It reports cache reads but not cache writes. In place of a
 * stop reason it gives a status, {@code "SUCCESS"} or another value. The parser takes the token
 * counts, duration and turn count from the CLI's final {@code result} event, and keeps neither
 * thinking text nor text sent before the final response.
 *
 * <p>The record copies {@code toolUses} into an unmodifiable list, so a capture can be shared
 * between threads.
 *
 * @param phaseName the caller's name for this phase, such as {@code "plan"} or {@code "execute"}
 * @param promptText the prompt sent for this phase, or {@code null} if it was not captured
 * @param model the model named in the {@code init} event, or {@code null} if none was named
 * @param conversationId the Antigravity conversation ID, or {@code null} if none was reported
 * @param inputTokens the input tokens Antigravity reported
 * @param outputTokens the output tokens Antigravity reported
 * @param thinkingTokens the thinking tokens Antigravity reported
 * @param cacheReadTokens the tokens read from the prompt cache
 * @param durationMs the duration of the call that Antigravity reported, in milliseconds, or 0
 *        if no {@code result} event arrived
 * @param numTurns the number of turns Antigravity reported
 * @param isError whether the call ended in error; the parser sets it when the status is not
 *        {@code SUCCESS}, ignoring case, which includes a stream with no {@code result} event
 * @param status the final status, such as {@code "SUCCESS"}; {@code "INCOMPLETE"} when no
 *        {@code result} event arrived; or {@code null} if the {@code result} event had none
 * @param textOutput the agent's final response; empty if there was none
 * @param errorMessage the error text of the {@code result} event, or {@code null} if there was
 *        none
 * @param toolUses the tool steps, in the order they first appeared; never {@code null}
 */
public record AntigravityPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String conversationId,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheReadTokens,
        long durationMs,
        int numTurns,
        boolean isError,
        String status,
        String textOutput,
        String errorMessage,
        List<AntigravityToolUseRecord> toolUses
) {

    /**
     * Creates a capture from all of its parts. A {@code null} {@code toolUses} becomes an empty
     * list; any other list is copied.
     */
    public AntigravityPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    /**
     * Returns whether the agent ran any tool steps.
     *
     * @return {@code true} if {@code toolUses} is not empty
     */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    /**
     * Returns the token counts as a {@link TokenUsage}.
     *
     * @return the usage of this call; its cache-write and tool-use token counts are 0
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, thinkingTokens, 0, cacheReadTokens, 0);
    }
}
