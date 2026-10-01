package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cost of an LLM call in US dollars: input, output and thinking costs, less what the prompt
 * cache saved. It is part of an {@link LLMCallEvent}; read the total with {@link #totalUsd()}.
 *
 * <p>Agents usually report only a total cost. The run recorders and
 * {@link LLMCallEvent#of(String, int, int, double)} store it with {@link #of(double)}, which puts
 * the whole amount in {@code inputCostUsd} and leaves the other parts 0. For those events only
 * the total is meaningful: {@code inputCostUsd} is the total, not the cost of input tokens.
 * Recorders for agents that report no cost, such as Codex and Antigravity, store 0 and set
 * {@code costAvailable} to {@code false} in the event's metadata.
 *
 * <p>The values are not checked; a total can be negative if the savings are larger than the
 * costs. The record is immutable.
 *
 * @param inputCostUsd the cost of input tokens, or the whole cost when built with
 *        {@link #of(double)}
 * @param outputCostUsd the cost of output tokens
 * @param thinkingCostUsd the cost of thinking (reasoning) tokens
 * @param cacheSavingsUsd the amount saved by reading from the prompt cache, subtracted from the
 *        total
 */
public record CostBreakdown(
        double inputCostUsd,
        double outputCostUsd,
        double thinkingCostUsd,
        double cacheSavingsUsd
) {
    /**
     * Creates a cost from a single total. The total is stored as {@code inputCostUsd}, and the
     * other parts are 0, so {@link #totalUsd()} returns it unchanged.
     *
     * @param totalUsd the total cost in US dollars
     * @return the new cost
     */
    public static CostBreakdown of(double totalUsd) {
        return new CostBreakdown(totalUsd, 0.0, 0.0, 0.0);
    }

    /**
     * Creates a cost from input and output costs; the thinking cost and cache savings are 0.
     *
     * @param inputUsd the cost of input tokens in US dollars
     * @param outputUsd the cost of output tokens in US dollars
     * @return the new cost
     */
    public static CostBreakdown of(double inputUsd, double outputUsd) {
        return new CostBreakdown(inputUsd, outputUsd, 0.0, 0.0);
    }

    /**
     * Returns the input, output and thinking costs added together, less the cache savings.
     *
     * @return the total cost in US dollars
     */
    public double totalUsd() {
        return inputCostUsd + outputCostUsd + thinkingCostUsd - cacheSavingsUsd;
    }

    /**
     * Returns the cost as a map with snake_case keys: {@code input_cost_usd},
     * {@code output_cost_usd} and {@code total_cost_usd} always, and {@code thinking_cost_usd}
     * and {@code cache_savings_usd} when they are above 0. {@link LLMCallEvent#toMap()} nests this
     * map under {@code cost}. File storage does not use it.
     *
     * @return a new map of the cost
     */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("input_cost_usd", inputCostUsd);
        map.put("output_cost_usd", outputCostUsd);
        if (thinkingCostUsd > 0) map.put("thinking_cost_usd", thinkingCostUsd);
        if (cacheSavingsUsd > 0) map.put("cache_savings_usd", cacheSavingsUsd);
        map.put("total_cost_usd", totalUsd());
        return map;
    }
}
