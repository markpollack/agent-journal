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
 * @param thinkingTokens Thinking tokens consumed (extended thinking)
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
 */
public record PhaseCapture(
        String phaseName,
        String promptText,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
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
        List<ToolResultRecord> toolResults
) {
    /**
     * Backward-compatible constructor for callers that don't provide toolResults.
     */
    public PhaseCapture(String phaseName, String promptText, int inputTokens, int outputTokens,
            int thinkingTokens, long durationMs, long apiDurationMs, double totalCostUsd,
            String sessionId, int numTurns, boolean isError, String textOutput,
            List<String> thinkingBlocks, List<ToolUseRecord> toolUses, String rawResult) {
        this(phaseName, promptText, inputTokens, outputTokens, thinkingTokens, durationMs,
                apiDurationMs, totalCostUsd, sessionId, numTurns, isError, textOutput,
                thinkingBlocks, toolUses, rawResult, null);
    }

    public int totalTokens() {
        return inputTokens + outputTokens + thinkingTokens;
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
}
