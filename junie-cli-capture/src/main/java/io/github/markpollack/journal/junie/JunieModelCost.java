package io.github.markpollack.journal.junie;

/**
 * One entry of a Junie {@code LlmResponseMetadataEvent.modelUsage} array — the per-call,
 * per-model cost and token breakdown.
 *
 * <p>
 * <strong>Junie reports a real cost, and it reconciles exactly.</strong> Unlike the Grok, Codex
 * and Antigravity adapters — which record {@code costAvailable=false} because their CLIs report
 * no cost at all — Junie puts a {@code cost} on every LLM call. Summing {@code cost} across every
 * {@code modelUsage} entry in a trace reproduces the session's own
 * {@code completion.taskCostUsd} bit-for-bit (verified on all three captured fixtures). That makes
 * the cost decomposition an <em>anchor</em> here rather than an estimate, so
 * {@link JuniePhaseCapture#reconcilesToModelCosts()} is a real closure check and not a formality.
 *
 * <p>
 * A single Junie task fans out across several models: the fixtures show the BYOK main model
 * ({@code gpt-5.3-codex}) alongside internal helper models ({@code gpt-4.1-mini}, {@code gpt-5.4-nano})
 * used for summarisation and routing. All of them are billed and all of them are folded, which is
 * why the folded totals match the ACP {@code session/prompt} usage block exactly rather than
 * matching only the main model.
 *
 * @param model              model id as Junie spells it (e.g. {@code gpt-5.3-codex})
 * @param costUsd            this call's cost, verbatim from the wire {@code cost}
 * @param inputTokens        fresh (non-cached) input tokens
 * @param cacheInputTokens   tokens read from the prompt cache (wire {@code cacheInputTokens})
 * @param cacheCreateTokens  tokens written to the prompt cache (wire {@code cacheCreateTokens});
 *                           zero in every captured fixture
 * @param outputTokens       output tokens generated
 */
public record JunieModelCost(
        String model,
        double costUsd,
        long inputTokens,
        long cacheInputTokens,
        long cacheCreateTokens,
        long outputTokens
) {
}
