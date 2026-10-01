package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tokens used by an LLM call, split by type: input, output, thinking, cache writes, cache
 * reads and tool definitions. It is part of an {@link LLMCallEvent}, and capture records such as
 * Claude Code's {@code PhaseCapture} build one per turn or per call. Add usages up by type with
 * {@link #plus(TokenUsage)} or {@link #sum(Iterable)}, and get input plus output plus thinking
 * tokens with {@link #total()}.
 *
 * <p>Whether {@code inputTokens} counts cache reads depends on what the agent reports. Claude
 * Code reports input without cache reads; the recorded Codex sessions show input that includes
 * them. Compare input tokens across vendors with care. No capture module sets
 * {@code toolUseTokens}.
 *
 * <p>The counts are {@code int}s and are not checked: a sum above {@link Integer#MAX_VALUE} for
 * one type overflows without an error. The record is immutable.
 *
 * @param inputTokens the input tokens
 * @param outputTokens the output tokens
 * @param thinkingTokens the thinking (reasoning) tokens
 * @param cacheCreationTokens the tokens written to the prompt cache
 * @param cacheReadTokens the tokens read from the prompt cache
 * @param toolUseTokens the tokens used for tool definitions
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationTokens,
        int cacheReadTokens,
        int toolUseTokens
) {
    /**
     * Creates a usage with input and output tokens; the other counts are 0.
     *
     * @param input the input tokens
     * @param output the output tokens
     * @return the new usage
     */
    public static TokenUsage of(int input, int output) {
        return new TokenUsage(input, output, 0, 0, 0, 0);
    }

    /**
     * Creates a usage with input, output and thinking tokens; the other counts are 0.
     *
     * @param input the input tokens
     * @param output the output tokens
     * @param thinking the thinking tokens
     * @return the new usage
     */
    public static TokenUsage of(int input, int output, int thinking) {
        return new TokenUsage(input, output, thinking, 0, 0, 0);
    }

    /**
     * Returns the input, output and thinking tokens added together. Cache writes, cache reads and
     * tool-definition tokens are not counted; add those components yourself when you need them.
     * This is the value of {@code total_tokens} in {@link #toMap()} and of
     * {@link LLMCallEvent#totalTokens()}.
     *
     * @return the total tokens
     */
    public int total() {
        return inputTokens + outputTokens + thinkingTokens;
    }

    /**
     * Returns the sum of this usage and another, type by type. Use it to add up the usage of
     * several turns or calls; each type is billed on every turn, so the sum matches the cost.
     *
     * @param other the usage to add, or {@code null}, which counts as zero
     * @return the sum, or this usage if {@code other} is {@code null}
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
     * Returns the sum of several usages, type by type, as {@link #plus(TokenUsage)} does.
     *
     * @param usages the usages to add; {@code null} elements count as zero
     * @return the sum, or a usage of all zeros if {@code usages} is {@code null} or empty
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

    /**
     * Returns {@code inputTokens} minus {@code cacheReadTokens}. That is the input not read from
     * the cache only when {@code inputTokens} includes cache reads. When it does not, as for
     * Claude Code, the result is too small and can be negative: 100 input tokens and 1,000 cache
     * reads give -900.
     *
     * @return the input tokens less the cache reads
     */
    public int effectiveInputTokens() {
        return inputTokens - cacheReadTokens;
    }

    /**
     * Returns {@code cacheReadTokens} divided by {@code inputTokens}. That is the share of input
     * read from the cache, between 0 and 1, only when {@code inputTokens} includes cache reads.
     * When it does not, as for Claude Code, the ratio can be above 1.
     *
     * @return the ratio, or 0 if {@code inputTokens} is 0
     */
    public double cacheHitRatio() {
        if (inputTokens == 0) return 0.0;
        return (double) cacheReadTokens / inputTokens;
    }

    /**
     * Returns the counts as a map with snake_case keys: {@code input_tokens},
     * {@code output_tokens} and {@code total_tokens} (see {@link #total()}) always, and
     * {@code thinking_tokens}, {@code cache_creation_tokens}, {@code cache_read_tokens} and
     * {@code tool_use_tokens} when they are above 0. {@link LLMCallEvent#toMap()} nests this map
     * under {@code token_usage}. File storage does not use it.
     *
     * @return a new map of the counts
     */
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
