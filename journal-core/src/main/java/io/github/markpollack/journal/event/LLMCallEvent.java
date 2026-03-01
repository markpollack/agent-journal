package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records an LLM API call with comprehensive token, cost, and timing metrics.
 *
 * <p>Designed to capture all metrics from:
 * <ul>
 *   <li>Claude Agent SDK Java (Usage, Cost, Metadata classes)</li>
 *   <li>Spring AI (ChatResponseMetadata, Usage, RateLimit)</li>
 *   <li>W&B Weave (automatic token/cost/latency tracking)</li>
 * </ul>
 *
 * @param timestamp when the call occurred
 * @param provider LLM provider (e.g., "anthropic", "openai")
 * @param model model identifier (e.g., "claude-opus-4.5", "gpt-4")
 * @param tokenUsage comprehensive token breakdown
 * @param cost itemized cost breakdown
 * @param timing duration breakdown
 * @param finishReason why the call completed (e.g., "stop", "length", "tool_calls")
 * @param responseId provider's response identifier
 * @param metadata provider-specific extras
 */
public record LLMCallEvent(
        Instant timestamp,
        String provider,
        String model,
        TokenUsage tokenUsage,
        CostBreakdown cost,
        TimingInfo timing,
        String finishReason,
        String responseId,
        Map<String, Object> metadata
) implements JournalEvent {

    @Override
    public String type() {
        return "llm_call";
    }

    /** Creates a simple LLMCallEvent. */
    public static LLMCallEvent of(String model, int inputTokens, int outputTokens, double costUsd) {
        return new LLMCallEvent(
                Instant.now(),
                null,
                model,
                TokenUsage.of(inputTokens, outputTokens),
                CostBreakdown.of(costUsd),
                null,
                null,
                null,
                Map.of()
        );
    }

    /** Creates a builder for LLMCallEvent. */
    public static Builder builder() {
        return new Builder();
    }

    /** Total tokens (input + output + thinking). */
    public int totalTokens() {
        return tokenUsage != null ? tokenUsage.total() : 0;
    }

    /** Total cost in USD. */
    public double totalCostUsd() {
        return cost != null ? cost.totalUsd() : 0.0;
    }

    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type());
        map.put("timestamp", timestamp.toString());
        if (provider != null) map.put("provider", provider);
        map.put("model", model);
        if (tokenUsage != null) map.put("token_usage", tokenUsage.toMap());
        if (cost != null) map.put("cost", cost.toMap());
        if (timing != null) map.put("timing", timing.toMap());
        if (finishReason != null) map.put("finish_reason", finishReason);
        if (responseId != null) map.put("response_id", responseId);
        if (metadata != null && !metadata.isEmpty()) map.put("metadata", metadata);
        return map;
    }

    /** Builder for LLMCallEvent. */
    public static final class Builder {
        private Instant timestamp = Instant.now();
        private String provider;
        private String model;
        private TokenUsage tokenUsage;
        private CostBreakdown cost;
        private TimingInfo timing;
        private String finishReason;
        private String responseId;
        private Map<String, Object> metadata = Map.of();

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        public Builder inputTokens(int inputTokens) {
            this.tokenUsage = TokenUsage.of(inputTokens,
                    this.tokenUsage != null ? this.tokenUsage.outputTokens() : 0);
            return this;
        }

        public Builder outputTokens(int outputTokens) {
            this.tokenUsage = TokenUsage.of(
                    this.tokenUsage != null ? this.tokenUsage.inputTokens() : 0,
                    outputTokens);
            return this;
        }

        public Builder cost(CostBreakdown cost) {
            this.cost = cost;
            return this;
        }

        public Builder totalCostUsd(double costUsd) {
            this.cost = CostBreakdown.of(costUsd);
            return this;
        }

        public Builder timing(TimingInfo timing) {
            this.timing = timing;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.timing = TimingInfo.of(durationMs);
            return this;
        }

        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        public Builder responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public LLMCallEvent build() {
            return new LLMCallEvent(timestamp, provider, model, tokenUsage, cost,
                    timing, finishReason, responseId, metadata);
        }
    }
}
