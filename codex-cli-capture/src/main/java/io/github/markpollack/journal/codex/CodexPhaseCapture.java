package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/**
 * The parsed record of one Codex CLI session: the agent's final message, the tool calls it made,
 * the tokens it used, and how long it took. {@link CodexSessionParser} builds one from a Codex
 * rollout file, or from any run of its lines. Pass it to a {@link CodexRunRecorder} to log it as
 * events on a {@link io.github.markpollack.journal.Run}, or read its fields when you only need
 * usage.
 *
 * <p>It is the Codex counterpart of Claude Code's {@code PhaseCapture}, with less detail. Codex
 * reports no cost, so the record has no cost field, and the recorder logs a cost of 0 marked
 * {@code costAvailable=false}. It also has no turn count, no stop reason, no thinking text and no
 * per-turn usage. The token counts are Codex's running total for the session, taken from the last
 * {@code token_count} record the parser read. In the recorded output of Codex 0.148.0, the input
 * count includes the cached input and the output count includes the reasoning tokens.
 *
 * <p>The record copies {@code toolUses} into an unmodifiable list, so a capture can be shared
 * between threads.
 *
 * @param phaseName the caller's name for this session or part of it, such as {@code "execute"}
 * @param promptText the prompt that was sent, or {@code null} if it was not captured
 * @param model the model named in the last {@code turn_context} record, or {@code null} if none
 *        was named
 * @param cliVersion the Codex CLI version named in the {@code session_meta} record, or
 *        {@code null} if none was named
 * @param sessionId the Codex session ID, or {@code null} if none was recorded
 * @param inputTokens the input tokens Codex reported
 * @param outputTokens the output tokens Codex reported
 * @param reasoningOutputTokens the reasoning tokens Codex reported
 * @param cacheWriteInputTokens the tokens written to the prompt cache
 * @param cachedInputTokens the input tokens read from the prompt cache
 * @param durationMs the duration of the task that Codex reported, in milliseconds, or 0 if no
 *        {@code task_complete} record was read
 * @param isError whether the rollout has no {@code task_complete} record, or one with a status
 *        other than {@code completed}; a failed tool call does not set it
 * @param textOutput the agent's last message, from {@code task_complete}; empty if there was none
 * @param toolUses the tool calls, in the order they first appeared; never {@code null}
 */
public record CodexPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String cliVersion,
        String sessionId,
        int inputTokens,
        int outputTokens,
        int reasoningOutputTokens,
        int cacheWriteInputTokens,
        int cachedInputTokens,
        long durationMs,
        boolean isError,
        String textOutput,
        List<CodexToolUseRecord> toolUses
) {

    /**
     * Creates a capture from all of its parts. A {@code null} {@code toolUses} becomes an empty
     * list; any other list is copied.
     */
    public CodexPhaseCapture {
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
     * Returns the token counts as a {@link TokenUsage}, with {@code reasoningOutputTokens} as the
     * thinking tokens and {@code cachedInputTokens} as the cache reads. The counts are passed on as
     * Codex reported them; see the class comment for what they include.
     *
     * @return the usage of this session; its tool-use token count is 0
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, reasoningOutputTokens,
                cacheWriteInputTokens, cachedInputTokens, 0);
    }
}
