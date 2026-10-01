package io.github.markpollack.journal.gemini;

/**
 * The parsed record of one Gemini CLI query: the agent's text, the tokens it used, what it cost,
 * how long it took, and its status. {@link GeminiSessionParser} builds one from the Gemini CLI
 * SDK's {@code QueryResult}. Pass it to a {@link GeminiRunRecorder} to log it as events on a
 * {@link io.github.markpollack.journal.Run}, or read its fields when you only need usage and cost.
 *
 * <p>It is the Gemini counterpart of Claude Code's {@code PhaseCapture}, with less detail. The
 * SDK's result holds only text messages and totals, so the record has no tool calls, no thinking,
 * no cache counts, no per-turn usage and no session ID. In place of a stop reason it has the
 * SDK's status. The cost is the total the SDK reports with the result.
 *
 * <p>The record is immutable and can be shared between threads.
 *
 * @param phaseName the caller's name for this phase, such as {@code "plan"} or {@code "execute"}
 * @param promptText the prompt sent for this phase, or {@code null} if it was not captured
 * @param model the model the SDK reported for the query
 * @param promptTokens the input tokens the SDK reported
 * @param completionTokens the output tokens the SDK reported
 * @param totalTokens the total tokens the SDK reported
 * @param durationMs the duration of the query that the SDK reported, in milliseconds, or 0 if none
 *        was reported
 * @param totalCostUsd the total cost the SDK reported, in US dollars, or 0 if none was reported
 * @param isError whether the status is anything other than {@code SUCCESS}
 * @param status the name of the SDK's result status, such as {@code SUCCESS}, {@code ERROR},
 *        {@code PARTIAL}, {@code TIMEOUT} or {@code CANCELLED}
 * @param textOutput the text of the assistant messages, joined in order with nothing between them
 */
public record GeminiPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long durationMs,
        double totalCostUsd,
        boolean isError,
        String status,
        String textOutput
) {

    /**
     * Returns whether the agent wrote any text.
     *
     * @return {@code true} if {@code textOutput} is not {@code null} and not empty
     */
    public boolean hasOutput() {
        return textOutput != null && !textOutput.isEmpty();
    }
}
