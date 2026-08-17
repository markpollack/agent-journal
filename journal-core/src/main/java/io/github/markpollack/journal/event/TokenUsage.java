package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Comprehensive token usage breakdown.
 *
 * <p>Captures all token types from various providers:
 * <ul>
 *   <li>Standard: input, output tokens</li>
 *   <li>Extended thinking: reasoning tokens (Claude, o1)</li>
 *   <li>Prompt caching: cache creation/read tokens</li>
 *   <li>Tool use: tokens for function definitions</li>
 * </ul>
 *
 * @param inputTokens prompt/input tokens
 * @param outputTokens completion/output tokens
 * @param thinkingTokens reasoning tokens (Claude extended thinking, o1)
 * @param cacheCreationTokens tokens written to prompt cache
 * @param cacheReadTokens tokens read from prompt cache
 * @param toolUseTokens tokens used for tool/function definitions
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationTokens,
        int cacheReadTokens,
        int toolUseTokens
) {
    /** Creates usage with just input and output tokens. */
    public static TokenUsage of(int input, int output) {
        return new TokenUsage(input, output, 0, 0, 0, 0);
    }

    /** Creates usage with input, output, and thinking tokens. */
    public static TokenUsage of(int input, int output, int thinking) {
        return new TokenUsage(input, output, thinking, 0, 0, 0);
    }

    /**
     * Sum of the input, output and thinking tokens — the tokens the model generated or was
     * charged for reading fresh. Cache creation, cache read and tool-use tokens are
     * deliberately <em>excluded</em>; add those components explicitly when you need them.
     * This is the value published as {@code total_tokens} by {@link #toMap()} and returned
     * by {@code LLMCallEvent.totalTokens()}.
     */
    public int total() {
        return inputTokens + outputTokens + thinkingTokens;
    }

    /**
     * Field-wise sum of two usage vectors — the per-type aggregator. Vendor-neutral, so any capture
     * adapter (Claude/Gemini/Codex) builds a cost-bearing aggregate the same way: Σ per-turn usage by
     * token type. Null is treated as the zero vector.
     */
    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                thinkingTokens + other.thinkingTokens,
                cacheCreationTokens + other.cacheCreationTokens,
                cacheReadTokens + other.cacheReadTokens,
                toolUseTokens + other.toolUseTokens);
    }

    /**
     * Field-wise sum of a sequence of usage vectors (empty/null → the zero vector). This is the
     * cost-bearing aggregate: each token type is billed every turn, so summing per-turn usage by type
     * is additive and reconciles to the run cost (unlike the context-size view, where cache re-reads
     * over-count). Caveat: fields are {@code int}; an aggregate above ~2.1B tokens of a single type
     * would overflow (not reachable for current runs).
     */
    public static TokenUsage sum(Iterable<TokenUsage> usages) {
        TokenUsage acc = new TokenUsage(0, 0, 0, 0, 0, 0);
        if (usages != null) {
            for (TokenUsage u : usages) {
                acc = acc.plus(u);
            }
        }
        return acc;
    }

    /** Effective input tokens accounting for cache. */
    public int effectiveInputTokens() {
        return inputTokens - cacheReadTokens;
    }

    /** Cache hit ratio (0.0 to 1.0). */
    public double cacheHitRatio() {
        if (inputTokens == 0) return 0.0;
        return (double) cacheReadTokens / inputTokens;
    }

    /** Converts to map for serialization. */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("input_tokens", inputTokens);
        map.put("output_tokens", outputTokens);
        if (thinkingTokens > 0) map.put("thinking_tokens", thinkingTokens);
        if (cacheCreationTokens > 0) map.put("cache_creation_tokens", cacheCreationTokens);
        if (cacheReadTokens > 0) map.put("cache_read_tokens", cacheReadTokens);
        if (toolUseTokens > 0) map.put("tool_use_tokens", toolUseTokens);
        map.put("total_tokens", total());
        return map;
    }
}
