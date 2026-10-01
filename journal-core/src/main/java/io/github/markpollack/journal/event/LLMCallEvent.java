package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records one call to a language model, or one agent call summed over its turns: the model, the
 * tokens used ({@link TokenUsage}), the cost ({@link CostBreakdown}), the time taken
 * ({@link TimingInfo}) and provider details. Log one with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}, built with
 * {@link #of(String, int, int, double)} or {@link #builder()}. The run recorders in the capture
 * modules log one per phase, with the token usage and cost of the whole phase. Read it back with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)};
 * {@link io.github.markpollack.journal.eval.EvalSubjectSources} turns it into an
 * {@code LLM_CALL} evaluation subject, identified by {@link #responseId()} when it is set.
 *
 * <p>The recorders put details of the phase in {@link #metadata()}: always {@code phaseName} and
 * {@code isError}, and vendor details such as {@code numTurns} or {@code sessionId}. Claude Code's
 * recorder also stores each turn's usage there, so the cost of each step can be computed again
 * from the events alone. The Grok, Codex, Antigravity and Junie recorders set
 * {@link #provider()}; the Claude Code and Gemini recorders leave it {@code null}.
 *
 * <p>Every component may be {@code null}; {@link #totalTokens()} and {@link #totalCostUsd()}
 * return 0 when the usage or cost is missing, but {@link #toMap()} needs a timestamp. The record
 * does not copy {@code metadata}. File storage writes it with {@code @type} {@code "llm_call"}.
 *
 * @param timestamp when the call was recorded
 * @param provider the model provider, such as {@code "anthropic"} or {@code "xai"}, or
 *        {@code null} if not known
 * @param model the model name, such as {@code "claude-opus-4.5"}
 * @param tokenUsage the tokens used, or {@code null} if not known
 * @param cost the cost in US dollars, or {@code null} if not known
 * @param timing how long the call took, or {@code null} if not measured
 * @param finishReason why the call ended, in the provider's words, such as {@code "stop"} or
 *        {@code "tool_calls"}, or {@code null}
 * @param responseId the provider's ID for the response, or {@code null}
 * @param metadata other details, keyed by name; may be empty
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

    /**
     * Creates an event with the current time, a model, input and output token counts, and a total
     * cost. The cost is stored with {@link CostBreakdown#of(double)}. The provider, timing,
     * finish reason and response ID are {@code null}, and the metadata is empty.
     *
     * @param model the model name
     * @param inputTokens the input tokens
     * @param outputTokens the output tokens
     * @param costUsd the total cost in US dollars
     * @return the new event
     */
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

    /**
     * Returns a new builder. It starts with the current time, empty metadata and every other
     * field {@code null}.
     *
     * @return the new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the input, output and thinking tokens added together, as
     * {@link TokenUsage#total()} does; cache tokens are not counted.
     *
     * @return the total tokens, or 0 if {@link #tokenUsage()} is {@code null}
     */
    public int totalTokens() {
        return tokenUsage != null ? tokenUsage.total() : 0;
    }

    /**
     * Returns the total cost, as {@link CostBreakdown#totalUsd()} gives it.
     *
     * @return the total cost in US dollars, or 0 if {@link #cost()} is {@code null}
     */
    public double totalCostUsd() {
        return cost != null ? cost.totalUsd() : 0.0;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code type}, {@code timestamp} and {@code model}, then, for each value that
     * is set, {@code provider}, {@code token_usage}, {@code cost}, {@code timing},
     * {@code finish_reason}, {@code response_id} and {@code metadata}. The usage, cost and timing
     * are nested maps from their own {@code toMap} methods.
     *
     * @throws NullPointerException if {@link #timestamp()} is {@code null}
     */
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

    /**
     * Builds an {@link LLMCallEvent}. Every field is optional. The timestamp defaults to the time
     * the builder was created, the metadata to an empty map, and the other fields to
     * {@code null}. See the {@link LLMCallEvent} components for what each field means. A builder
     * is not safe for use from several threads.
     */
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

        /**
         * Sets when the call was recorded.
         *
         * @param timestamp the time
         * @return this builder
         */
        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        /**
         * Sets the model provider.
         *
         * @param provider the provider, such as {@code "anthropic"}
         * @return this builder
         */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /**
         * Sets the model name.
         *
         * @param model the model name
         * @return this builder
         */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /**
         * Sets the token usage.
         *
         * @param tokenUsage the tokens used
         * @return this builder
         */
        public Builder tokenUsage(TokenUsage tokenUsage) {
            this.tokenUsage = tokenUsage;
            return this;
        }

        /**
         * Sets the token usage to {@code TokenUsage.of(inputTokens, outputTokens)}, taking the
         * output tokens from the usage already set, or 0.
         *
         * @param inputTokens the input tokens
         * @return this builder
         */
        public Builder inputTokens(int inputTokens) {
            this.tokenUsage = TokenUsage.of(inputTokens,
                    this.tokenUsage != null ? this.tokenUsage.outputTokens() : 0);
            return this;
        }

        /**
         * Sets the token usage to {@code TokenUsage.of(inputTokens, outputTokens)}, taking the
         * input tokens from the usage already set, or 0.
         *
         * @param outputTokens the output tokens
         * @return this builder
         */
        public Builder outputTokens(int outputTokens) {
            this.tokenUsage = TokenUsage.of(
                    this.tokenUsage != null ? this.tokenUsage.inputTokens() : 0,
                    outputTokens);
            return this;
        }

        /**
         * Sets the cost.
         *
         * @param cost the cost
         * @return this builder
         */
        public Builder cost(CostBreakdown cost) {
            this.cost = cost;
            return this;
        }

        /**
         * Sets the cost from a single total, with {@link CostBreakdown#of(double)}.
         *
         * @param costUsd the total cost in US dollars
         * @return this builder
         */
        public Builder totalCostUsd(double costUsd) {
            this.cost = CostBreakdown.of(costUsd);
            return this;
        }

        /**
         * Sets the timing.
         *
         * @param timing how long the call took
         * @return this builder
         */
        public Builder timing(TimingInfo timing) {
            this.timing = timing;
            return this;
        }

        /**
         * Sets the timing from a single duration, with {@link TimingInfo#of(long)}.
         *
         * @param durationMs the total duration in milliseconds
         * @return this builder
         */
        public Builder durationMs(long durationMs) {
            this.timing = TimingInfo.of(durationMs);
            return this;
        }

        /**
         * Sets why the call ended.
         *
         * @param finishReason the provider's finish reason
         * @return this builder
         */
        public Builder finishReason(String finishReason) {
            this.finishReason = finishReason;
            return this;
        }

        /**
         * Sets the provider's ID for the response.
         *
         * @param responseId the response ID
         * @return this builder
         */
        public Builder responseId(String responseId) {
            this.responseId = responseId;
            return this;
        }

        /**
         * Sets the metadata. The map is not copied.
         *
         * @param metadata other details, keyed by name
         * @return this builder
         */
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /**
         * Returns a new event with the values set so far.
         *
         * @return the new event
         */
        public LLMCallEvent build() {
            return new LLMCallEvent(timestamp, provider, model, tokenUsage, cost,
                    timing, finishReason, responseId, metadata);
        }
    }
}
