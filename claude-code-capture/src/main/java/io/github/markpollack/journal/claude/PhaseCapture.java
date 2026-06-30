package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures all data from one SDK interaction phase.
 * Unified record merging agent/PhaseResult and spring-upgrade-agent/PhaseCapture.
 *
 * <p>
 * <strong>Two token views — pick by intent (CM):</strong>
 * <ul>
 *   <li>{@link #aggregateUsage()} — the <em>cost-bearing</em> aggregate (Σ per-turn by type, incl.
 *       cache). Use this for anything that prices tokens or computes cost-to-go; it reconciles to
 *       {@code totalCostUsd}.</li>
 *   <li>The scalar accessors {@link #inputTokens()}/{@link #outputTokens()}/{@link #thinkingTokens()}
 *       (and {@link #snapshotUsage()}) — the <em>final-snapshot / context-size</em> view from the last
 *       {@code ResultMessage}. <strong>Deprecated as a cost basis</strong>: it is point-in-time and
 *       under-counts long runs. Kept for size/diagnostic use and back-compat.</li>
 * </ul>
 *
 * @param phaseName      Phase identifier ("explore", "act", "plan", "execute", "reflexion")
 * @param promptText     The prompt sent to the LLM for this phase (null if not captured)
 * @param inputTokens    Input tokens consumed
 * @param outputTokens   Output tokens consumed
 * @param thinkingTokens            Thinking tokens consumed (extended thinking)
 * @param cacheCreationInputTokens  Tokens written to prompt cache
 * @param cacheReadInputTokens      Tokens read from prompt cache
 * @param durationMs     Wall-clock duration from SDK (milliseconds)
 * @param apiDurationMs  API-only duration from SDK (milliseconds)
 * @param totalCostUsd   Total cost in USD
 * @param sessionId      Claude session identifier
 * @param numTurns       Number of conversation turns
 * @param isError        Whether the SDK reported an error
 * @param textOutput     Concatenated text blocks from assistant messages
 * @param thinkingBlocks Each ThinkingBlock.thinking() content
 * @param toolUses       Tool use records from assistant messages
 * @param rawResult      ResultMessage.result() content (null if not available)
 * @param toolResults    Tool result records from user messages (null for older captures)
 * @param turns          Per-turn usage (one per assistant message), parsed from the wire;
 *                       empty when rawJson is unavailable (SDK &lt; 1.3.0 or programmatic). R2.2.
 * @param modelCosts     Per-model cost decomposition from the result's modelUsage; the
 *                       costs sum to {@code totalCostUsd}. Empty when unavailable. R2.2.
 */
public record PhaseCapture(
        String phaseName,
        String promptText,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        long durationMs,
        long apiDurationMs,
        double totalCostUsd,
        String sessionId,
        int numTurns,
        boolean isError,
        String textOutput,
        List<String> thinkingBlocks,
        List<ToolUseRecord> toolUses,
        String rawResult,
        List<ToolResultRecord> toolResults,
        List<TurnUsage> turns,
        List<ModelCost> modelCosts
) {
    /**
     * Backward-compatible constructor for callers that don't provide cache tokens or toolResults.
     */
    public PhaseCapture(String phaseName, String promptText, int inputTokens, int outputTokens,
            int thinkingTokens, long durationMs, long apiDurationMs, double totalCostUsd,
            String sessionId, int numTurns, boolean isError, String textOutput,
            List<String> thinkingBlocks, List<ToolUseRecord> toolUses, String rawResult) {
        this(phaseName, promptText, inputTokens, outputTokens, thinkingTokens, 0, 0, durationMs,
                apiDurationMs, totalCostUsd, sessionId, numTurns, isError, textOutput,
                thinkingBlocks, toolUses, rawResult, null);
    }

    /**
     * Backward-compatible constructor for callers that predate per-turn usage capture
     * (R2.2): {@code turns} and {@code modelCosts} default to empty.
     */
    public PhaseCapture(String phaseName, String promptText, int inputTokens, int outputTokens,
            int thinkingTokens, int cacheCreationInputTokens, int cacheReadInputTokens, long durationMs,
            long apiDurationMs, double totalCostUsd, String sessionId, int numTurns, boolean isError,
            String textOutput, List<String> thinkingBlocks, List<ToolUseRecord> toolUses, String rawResult,
            List<ToolResultRecord> toolResults) {
        this(phaseName, promptText, inputTokens, outputTokens, thinkingTokens, cacheCreationInputTokens,
                cacheReadInputTokens, durationMs, apiDurationMs, totalCostUsd, sessionId, numTurns, isError,
                textOutput, thinkingBlocks, toolUses, rawResult, toolResults, List.of(), List.of());
    }

    /**
     * Total input tokens including prompt cache reads and cache creation.
     */
    public int totalInputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    public int totalTokens() {
        return totalInputTokens() + outputTokens + thinkingTokens;
    }

    public boolean hasThinking() {
        return thinkingBlocks != null && !thinkingBlocks.isEmpty();
    }

    public boolean hasToolUses() {
        return toolUses != null && !toolUses.isEmpty();
    }

    public boolean hasToolResults() {
        return toolResults != null && !toolResults.isEmpty();
    }

    public boolean hasTurns() {
        return turns != null && !turns.isEmpty();
    }

    public boolean hasModelCosts() {
        return modelCosts != null && !modelCosts.isEmpty();
    }

    /**
     * Eager, pure per-step attributed cost computed from this capture's own {@code turns},
     * {@code toolUses}, and {@code totalCostUsd} — no storage, no {@code Run}, no clock (DESIGN §4).
     *
     * <p>
     * This makes derived cost inseparable from any capture: a consumer that never opens a {@code Run}
     * still gets the per-step split. The shares sum to {@code totalCostUsd} (±float; residual folded
     * into the last step) under {@link io.github.markpollack.journal.trace.AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL}.
     *
     * <p>
     * Returns {@link JournalStep} (the {@code List<JournalStep>} latitude the contract allows) rather
     * than {@code StepCostEvent} precisely because purity forbids the post-run analysis timestamp a
     * {@code StepCostEvent} carries — the recorder stamps that at persist time via
     * {@link io.github.markpollack.journal.derived.StepCostEvent#fromStep}. {@code runId} is {@code null}
     * here (the capture has no run yet); a recorder re-derives with the real id.
     *
     * @return the per-step attributed costs for this capture (empty only if there is nothing to attribute)
     */
    public List<JournalStep> stepCosts() {
        return JournalSteps.fromPhaseCapture(this, null);
    }

    /**
     * The <strong>cost-bearing</strong> per-type token aggregate: Σ over {@link #turns()} of each
     * token type ({@code input + output + cacheCreation + cacheRead}). This is the headline vector
     * a cost-to-go must price — every turn's cache re-read is billed, so summing per-turn usage by
     * type is additive and reconciles to {@code totalCostUsd} (unlike the per-turn token <em>size</em>
     * view of R2.2, and unlike the snapshot, which takes the final {@code ResultMessage} usage and
     * drops cache entirely). Pure, no I/O.
     *
     * <p>
     * <strong>Thinking</strong> is recorded but never double-counted: Claude bills thinking inside
     * {@code output} and exposes no per-turn thinking count, so the priced sum is input+output+cache;
     * the best available run-level {@code thinkingTokens} is carried on the result as a documented
     * subset of {@code output} (so it is not dropped), never added to the billed total.
     *
     * <p>
     * When {@code turns} is empty (SDK &lt; 1.3.0 / programmatic capture), there is no per-turn truth
     * to sum, so this falls back to the final-snapshot vector — coarse (context-size, not Σ-billed),
     * but it still carries the snapshot's cache fields (unlike the legacy
     * {@code TokenUsage.of(in, out, thinking)} headline, which zeroed them).
     *
     * @return the cost-bearing token aggregate for this phase
     */
    public TokenUsage aggregateUsage() {
        if (turns == null || turns.isEmpty()) {
            return new TokenUsage(inputTokens, outputTokens, thinkingTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, 0);
        }
        List<TokenUsage> perTurn = new ArrayList<>(turns.size());
        for (TurnUsage t : turns) {
            perTurn.add(new TokenUsage((int) t.inputTokens(), (int) t.outputTokens(), 0,
                    (int) t.cacheCreationInputTokens(), (int) t.cacheReadInputTokens(), 0));
        }
        TokenUsage summed = TokenUsage.sum(perTurn);
        // Carry the available run-level thinking (subset of output), recorded but not in the billed sum.
        return new TokenUsage(summed.inputTokens(), summed.outputTokens(), thinkingTokens,
                summed.cacheCreationTokens(), summed.cacheReadTokens(), summed.toolUseTokens());
    }

    /**
     * The <strong>final-snapshot / context-size</strong> token view — the last {@code ResultMessage}
     * usage as captured (point-in-time, not Σ-billed). Exposed explicitly so callers choose intent;
     * for <em>cost</em> use {@link #aggregateUsage()}. The scalar accessors
     * ({@link #inputTokens()}/{@link #outputTokens()}/{@link #thinkingTokens()}) are this same view.
     */
    public TokenUsage snapshotUsage() {
        return new TokenUsage(inputTokens, outputTokens, thinkingTokens,
                cacheCreationInputTokens, cacheReadInputTokens, 0);
    }

    /**
     * Sum of per-model {@code costUsd} from {@link #modelCosts()} — the exact run cost
     * decomposition (equals {@code totalCostUsd} ±float rounding when modelUsage is present).
     */
    public double modelCostSum() {
        if (modelCosts == null) {
            return 0.0;
        }
        return modelCosts.stream().mapToDouble(ModelCost::costUsd).sum();
    }
}
