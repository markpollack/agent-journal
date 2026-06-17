package io.github.markpollack.journal.claude;

/**
 * Per-model cost + token usage for a run, parsed from the result wire's {@code modelUsage}
 * object (a sibling of {@code usage}, not a typed field — recovered from
 * {@code RegularMessage.rawJson}).
 *
 * <p>
 * This is the <strong>exact</strong> cost decomposition Claude Code provides: the
 * {@code costUsd} values across all models sum to the result's {@code total_cost_usd}
 * (±float rounding). It is the summation anchor for per-step cost attribution (Round 2
 * R2.3/R2.4) — a run may touch more than one model (e.g. a main Opus model plus a Haiku
 * fast-path), each with its own rate.
 *
 * <p>
 * The wire keys here are camelCase ({@code inputTokens}, {@code costUSD}), unlike the
 * snake_case {@code message.usage} block that {@link TurnUsage} reads.
 *
 * @param model                    model id key (e.g. {@code claude-opus-4-8[1m]})
 * @param inputTokens              total input tokens billed to this model
 * @param outputTokens             total output tokens billed to this model
 * @param cacheReadInputTokens     cache-read tokens for this model
 * @param cacheCreationInputTokens cache-creation tokens for this model
 * @param costUsd                  this model's share of the run cost (wire {@code costUSD})
 */
public record ModelCost(
        String model,
        long inputTokens,
        long outputTokens,
        long cacheReadInputTokens,
        long cacheCreationInputTokens,
        double costUsd
) {
}
