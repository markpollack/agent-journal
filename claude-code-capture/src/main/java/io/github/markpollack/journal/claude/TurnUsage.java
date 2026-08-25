package io.github.markpollack.journal.claude;

import java.util.List;

/**
 * Per-turn usage for one assistant message (one API request), parsed from the wire
 * {@code message.usage} block. The typed {@code AssistantMessage} exposes only
 * {@code content}, so this is recovered from {@code RegularMessage.rawJson} (SDK
 * &ge; 1.3.0) — see {@code SessionLogParser}.
 *
 * <p>
 * Tokens are the per-turn breakdown; <strong>cost is intentionally absent.</strong>
 * Claude Code does not put a per-turn {@code cost_usd} on the wire — the only native cost
 * is the run total ({@code total_cost_usd}) and its per-model decomposition
 * ({@link ModelCost}). Per-turn / per-step cost is <em>attributed</em> downstream
 * (Round 2 R2.3) from those anchors plus a documented split rule, not read here.
 *
 * <p>
 * <strong>Named caveat: {@code PER_TURN_INPUT_NOT_ADDITIVE}.</strong> Per-turn token fields do
 * <em>not</em> sum to the run aggregate reported on the terminal {@code ResultMessage}, and this
 * is a property of the data, not a capture defect. {@code input_tokens} and the cache fields are
 * a <em>per-request</em> measurement over an accumulating context window: the same prompt prefix
 * is re-read on every turn, so Σ per-turn input exceeds any notion of "the input of the run",
 * while the result's own {@code usage} block is a final-request snapshot that under-counts a long
 * run. Neither is wrong; they measure different things.
 *
 * <p>
 * The reconciliation that <em>does</em> hold is the cost identity, not a token identity: Σ
 * {@link ModelCost#costUsd()} over {@code modelUsage} equals {@code total_cost_usd} (±float).
 * That is the check to run — see {@code PhaseCapture.reconcilesToModelCosts()}. Anything that
 * needs a summable token figure must sum <em>by type across turns</em>
 * ({@code PhaseCapture.aggregateUsage()}) and treat the result as billed volume, never as
 * context size. Do not "fix" the discrepancy by making the numbers agree; they are answers to
 * different questions.
 *
 * @param messageId               the wire {@code message.id} ({@code msg_...}); turn identity
 * @param model                   the model that served this turn (e.g. {@code claude-opus-4-8})
 * @param inputTokens             non-cached input tokens for this turn
 * @param outputTokens            output tokens generated this turn
 * @param cacheCreationInputTokens tokens written to the prompt cache this turn
 * @param cacheReadInputTokens    tokens read from the prompt cache this turn
 * @param toolUseIds              ids of the tool calls issued in this turn (empty if none);
 *                                used by {@code JournalSteps} to attribute the turn's cost
 *                                to its tool calls (R2.3)
 * @param thinkingTokens          extended-thinking tokens for this turn, read from the wire's
 *                                {@code usage.output_tokens_details.thinking_tokens}. This is a
 *                                <strong>subset of {@code outputTokens}</strong> — the provider
 *                                bills thinking inside output — so it must never be added to a
 *                                billed total. 0 when the provider reports none. Before 1.9.0
 *                                this was not captured per turn at all and the run-level figure
 *                                was a chars/4 estimate; the exact per-turn count was on the wire
 *                                the whole time and was discarded at parse time.
 * @param stopReason              the wire {@code message.stop_reason} for this turn, verbatim
 *                                ({@code end_turn}, {@code tool_use}, {@code max_tokens},
 *                                {@code stop_sequence}, {@code refusal}), or null when absent.
 *                                Normalized by {@link ClaudeStopReasons}; kept raw here so an
 *                                unrecognized future value survives capture.
 * @param turnIndex               0-based ordinal of this turn within the capture, or -1 when
 *                                unknown. Orders the trajectory for dwell-time analysis.
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
     * Back-compat constructor for callers that don't supply tool-call ids.
     */
    public TurnUsage(String messageId, String model, long inputTokens, long outputTokens,
            long cacheCreationInputTokens, long cacheReadInputTokens) {
        this(messageId, model, inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, List.of());
    }

    /**
     * Back-compat constructor for callers written before per-turn thinking tokens, the wire stop
     * reason and the turn ordinal were captured (1.9.0). Those default to "not captured": 0, null
     * and -1 respectively.
     */
    public TurnUsage(String messageId, String model, long inputTokens, long outputTokens,
            long cacheCreationInputTokens, long cacheReadInputTokens, List<String> toolUseIds) {
        this(messageId, model, inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens,
                toolUseIds, 0L, null, -1);
    }

    /**
     * Total input including prompt-cache reads and cache creation.
     */
    public long totalInputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    /**
     * This turn's wire stop reason normalized onto the portable
     * {@link io.github.markpollack.journal.event.StopReason}.
     */
    public io.github.markpollack.journal.event.StopReason normalizedStopReason() {
        return ClaudeStopReasons.fromTurnStopReason(stopReason);
    }
}
