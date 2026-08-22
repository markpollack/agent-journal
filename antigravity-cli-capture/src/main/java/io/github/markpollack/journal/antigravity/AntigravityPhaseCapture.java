package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/** Captures one Antigravity CLI {@code stream-json} run. */
public record AntigravityPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String conversationId,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheReadTokens,
        long durationMs,
        int numTurns,
        boolean isError,
        String status,
        String textOutput,
        String errorMessage,
        List<AntigravityToolUseRecord> toolUses
) {

    public AntigravityPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, thinkingTokens, 0, cacheReadTokens, 0);
    }
}
