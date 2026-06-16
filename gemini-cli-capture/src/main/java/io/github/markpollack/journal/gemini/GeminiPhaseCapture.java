package io.github.markpollack.journal.gemini;

/**
 * Captures one Gemini CLI query result. The Gemini analog of {@code claude-code-capture}'s
 * {@code PhaseCapture}.
 *
 * <p>
 * Gemini's typed SDK model is <strong>coarser than Claude's</strong>: a {@code QueryResult}
 * is a list of typed text messages (USER/ASSISTANT/SYSTEM/ERROR) plus result-level
 * {@code Metadata} (model, duration, {@code Usage}, {@code Cost}). It does <em>not</em> model
 * tool calls or content blocks, so capture is at the <strong>turn/result grain</strong>, not
 * per-tool-call. Per-tool detail exists only in Gemini's OTEL telemetry
 * ({@code gemini_cli.tool_call}) — a separate, heavier channel (future work). This is the
 * honest "completeness varies by harness" tradeoff of the harness-output capture bet.
 *
 * <p>
 * Cost is provided by the SDK's {@code Cost} (a pricing-derived total — Gemini CLI itself
 * emits only tokens), so {@code totalCostUsd} is usable directly as the run cost to attribute.
 *
 * @param phaseName        phase / run identifier (doubles as runId, as in the Claude path)
 * @param promptText       the prompt sent (null if not captured)
 * @param model            the Gemini model that served the query
 * @param promptTokens     input (prompt) tokens
 * @param completionTokens output (completion) tokens
 * @param totalTokens      total tokens reported by the SDK
 * @param durationMs       wall-clock duration (ms)
 * @param totalCostUsd     total cost in USD (SDK-provided, pricing-derived)
 * @param isError          whether the query result status was not SUCCESS
 * @param status           the {@code ResultStatus} name (SUCCESS/ERROR/PARTIAL/TIMEOUT)
 * @param textOutput       concatenated ASSISTANT message content
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

    public boolean hasOutput() {
        return textOutput != null && !textOutput.isEmpty();
    }
}
