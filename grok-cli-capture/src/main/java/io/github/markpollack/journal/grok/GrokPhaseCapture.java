package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/** Captures one Grok CLI {@code streaming-json} run. */
public record GrokPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        double totalCostUsd,
        String sessionId,
        int numTurns,
        boolean isError,
        String stopReason,
        String textOutput,
        String thinkingOutput,
        List<GrokToolUseRecord> toolUses
) {

    public GrokPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    public boolean hasOutput() {
        return textOutput != null && !textOutput.isEmpty();
    }

    /** The cost-bearing aggregate token vector reported by Grok's terminal {@code end} line. */
    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, thinkingTokens,
                cacheCreationInputTokens, cacheReadInputTokens, 0);
    }
}
