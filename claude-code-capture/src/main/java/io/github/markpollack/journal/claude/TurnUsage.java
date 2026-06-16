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
 * Note: per-turn token fields do <em>not</em> sum to the run aggregate — {@code input}
 * and cache tokens reflect the accumulating context window per turn, not additive slices.
 * The exact summation identity is {@link ModelCost} {@code costUsd} → {@code total_cost_usd}.
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
 */
public record TurnUsage(
        String messageId,
        String model,
        long inputTokens,
        long outputTokens,
        long cacheCreationInputTokens,
        long cacheReadInputTokens,
        List<String> toolUseIds
) {

    /**
     * Back-compat constructor for callers that don't supply tool-call ids.
     */
    public TurnUsage(String messageId, String model, long inputTokens, long outputTokens,
            long cacheCreationInputTokens, long cacheReadInputTokens) {
        this(messageId, model, inputTokens, outputTokens, cacheCreationInputTokens, cacheReadInputTokens, List.of());
    }

    /**
     * Total input including prompt-cache reads and cache creation.
     */
    public long totalInputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }
}
