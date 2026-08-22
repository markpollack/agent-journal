package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/** Captures one harvested Codex rollout JSONL session or bounded slice. */
public record CodexPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String cliVersion,
        String sessionId,
        int inputTokens,
        int outputTokens,
        int reasoningOutputTokens,
        int cacheWriteInputTokens,
        int cachedInputTokens,
        long durationMs,
        boolean isError,
        String textOutput,
        List<CodexToolUseRecord> toolUses
) {

    public CodexPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, reasoningOutputTokens,
                cacheWriteInputTokens, cachedInputTokens, 0);
    }
}
