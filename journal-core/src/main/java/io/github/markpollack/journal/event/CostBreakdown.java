package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Itemized cost breakdown by token type.
 *
 * @param inputCostUsd cost for input tokens
 * @param outputCostUsd cost for output tokens
 * @param thinkingCostUsd cost for thinking/reasoning tokens
 * @param cacheSavingsUsd savings from cache hits (reduces total)
 */
public record CostBreakdown(
        double inputCostUsd,
        double outputCostUsd,
        double thinkingCostUsd,
        double cacheSavingsUsd
) {
    /** Creates a cost breakdown from a single total. */
    public static CostBreakdown of(double totalUsd) {
        return new CostBreakdown(totalUsd, 0.0, 0.0, 0.0);
    }

    /** Creates a cost breakdown with input and output costs. */
    public static CostBreakdown of(double inputUsd, double outputUsd) {
        return new CostBreakdown(inputUsd, outputUsd, 0.0, 0.0);
    }

    /** Total cost in USD. */
    public double totalUsd() {
        return inputCostUsd + outputCostUsd + thinkingCostUsd - cacheSavingsUsd;
    }

    /** Converts to map for serialization. */
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
