package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tokens used by an LLM call, split by type: input, output, thinking, cache writes, cache
 * reads and tool definitions. It is part of an {@link LLMCallEvent}, and capture records such as
 * Claude Code's {@code PhaseCapture} build one per turn or per call. Add usages up by type with
 * {@link #plus(TokenUsage)} or {@link #sum(Iterable)}, and get input plus output tokens with
 * {@link #total()}.
 *
 * <p>Two conventions hold for every usage the capture modules build. {@code thinkingTokens} are
 * part of {@code outputTokens}, not an addition to them. {@code inputTokens} do not include cache
 * reads or cache writes, which have their own counts. Claude Code reports its usage this way.
 * Codex reports input that includes cached input, and the Codex module subtracts it since 1.11.0;
 * Codex events written by earlier versions carry the larger, cache-inclusive {@code inputTokens}
 * and are not rewritten. No capture module sets {@code toolUseTokens}.
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
     * Returns the input and output tokens added together. Thinking tokens are part of the output
     * tokens, so they are not added again; up to 1.10.1 this method added them a second time.
     * Cache writes, cache reads and tool-definition tokens are not counted; add those components
     * yourself when you need them. This is the value of {@code total_tokens} in {@link #toMap()}
     * and of {@link LLMCallEvent#totalTokens()}. It is computed on each call and is not stored.
     *
     * @return the total tokens
     */
    public int total() {
        return inputTokens + outputTokens;
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
     * Returns {@code inputTokens}, which already exclude cache reads. Up to 1.10.1 this method
     * subtracted the cache reads from them, which gave a negative number for Claude Code.
     *
     * @return the input tokens
     * @deprecated use {@link #inputTokens()}
     */
    @Deprecated(since = "1.11.0")
    public int effectiveInputTokens() {
        return inputTokens;
    }

    /**
     * Returns the cache reads' share of the input tokens plus the cache reads, between 0 and 1.
     * Cache writes are not counted on either side. For a Codex usage written before 1.11.0, whose
     * {@code inputTokens} include the cache reads, the result is lower than the true share.
     *
     * @return {@code cacheReadTokens / (inputTokens + cacheReadTokens)}, or 0 if both are 0
     */
    public double cacheHitRatio() {
        long inputAndReads = (long) inputTokens + cacheReadTokens;
        if (inputAndReads <= 0) return 0.0;
        return (double) cacheReadTokens / inputAndReads;
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
