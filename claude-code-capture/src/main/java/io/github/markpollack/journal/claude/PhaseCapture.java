package io.github.markpollack.journal.claude;

import java.util.List;

/**
 * Captures all data from one SDK interaction phase.
 * Unified record merging agent/PhaseResult and spring-upgrade-agent/PhaseCapture.
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
