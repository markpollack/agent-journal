package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a tool execution.
 *
 * @param timestamp when the tool was called
 * @param toolName raw vendor name of the tool (e.g., "Bash", "view_file", "exec")
 * @param input tool input parameters
 * @param output tool output (null if failed)
 * @param durationMs execution duration in milliseconds — the interval between the tool call
 *           being issued and its result arriving. 0 when never measured; see
 *           {@code ToolResultRecord.durationMs} for the capture-side caveat.
 * @param success whether the tool call succeeded
 * @param errorMessage error message if failed (null if succeeded)
 * @param id stable identity for this step — the vendor tool_use id (e.g. {@code toolu_…})
 *           when available, null otherwise. Persisted so feedback/eval can target a step
 *           by a stable id instead of a reload-order-dependent list position. (Round 2 R2.3)
 * @param kind canonical ACP-aligned tool category
 * @param turnIndex 0-based ordinal of the model turn that issued this tool call, or -1 when
 *           unknown. With {@code durationMs} this is the dwell-time pair a semi-Markov
 *           analysis needs: the ordinal places the step in the trajectory, the duration gives
 *           the state its holding time. Both were carried by the v1 capture and lost by v3/v4.
 * @param turnId identity of the model turn that issued this tool call (the assistant message
 *           id, e.g. {@code msg_…}), or null when unknown. Joins a tool call to its turn's
 *           token vector without depending on list position.
 */
public record ToolCallEvent(
        Instant timestamp,
        String toolName,
        Map<String, Object> input,
        Object output,
        long durationMs,
        boolean success,
        String errorMessage,
        String id,
        ToolKind kind,
        int turnIndex,
        String turnId
) implements JournalEvent {

    public ToolCallEvent {
        kind = kind != null ? kind : ToolKind.OTHER;
    }

    /**
     * Back-compat constructor for events written before the turn ordinal and turn id were
     * carried (1.9.0). {@code turnIndex} defaults to -1 ("not captured") rather than 0, so a
     * missing ordinal is never mistaken for the first turn.
     */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage, String id, ToolKind kind) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, id, kind, -1, null);
    }

    /** Back-compat constructor for events written before canonical tool kinds were added. */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage, String id) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, id, ToolKind.OTHER);
    }

    /** Back-compat constructor for events without a stable id. */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, null, ToolKind.OTHER);
    }

    @Override
    public String type() {
        return "tool_call";
    }

    /** Creates a successful tool call event. */
    public static ToolCallEvent success(String toolName, Map<String, Object> input,
                                        Object output, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, output, durationMs, true, null);
    }

    /** Creates a successful tool call event with a stable id (the vendor tool_use id). */
    public static ToolCallEvent success(String id, String toolName, Map<String, Object> input,
                                        Object output, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, output, durationMs, true, null, id);
    }

    /** Creates a failed tool call event. */
    public static ToolCallEvent failure(String toolName, Map<String, Object> input,
                                        String error, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, null, durationMs, false, error);
    }

    /** Creates a failed tool call event with a stable id (the vendor tool_use id). */
    public static ToolCallEvent failure(String id, String toolName, Map<String, Object> input,
                                        String error, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, null, durationMs, false, error, id);
    }

    /** Creates a builder for ToolCallEvent. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Deserialization entry point, present so that a <strong>pre-1.9.0 {@code events.jsonl} reads
     * back honestly</strong>. Those records carry no {@code turnIndex}; taking Jackson's default
     * for a primitive {@code int} would silently resolve them to {@code 0} — indistinguishable
     * from "issued by the first turn", and a plausible-looking wrong answer is worse than an
     * explicit unknown. An absent ordinal therefore becomes -1.
     *
     * <p>
     * Note that {@code durationMs} on a pre-1.9.0 record is {@code 0} for a different reason: the
     * field existed but the recorder never populated it, so those zeros are "never measured", not
     * "measured as instantaneous". They cannot be told apart from a genuine zero after the fact,
     * which is precisely why the field is now written as -1 when unmeasured.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    static ToolCallEvent fromJson(
            @com.fasterxml.jackson.annotation.JsonProperty("timestamp") Instant timestamp,
            @com.fasterxml.jackson.annotation.JsonProperty("toolName") String toolName,
            @com.fasterxml.jackson.annotation.JsonProperty("input") Map<String, Object> input,
            @com.fasterxml.jackson.annotation.JsonProperty("output") Object output,
            @com.fasterxml.jackson.annotation.JsonProperty("durationMs") long durationMs,
            @com.fasterxml.jackson.annotation.JsonProperty("success") boolean success,
            @com.fasterxml.jackson.annotation.JsonProperty("errorMessage") String errorMessage,
            @com.fasterxml.jackson.annotation.JsonProperty("id") String id,
            @com.fasterxml.jackson.annotation.JsonProperty("kind") ToolKind kind,
            @com.fasterxml.jackson.annotation.JsonProperty("turnIndex") Integer turnIndex,
            @com.fasterxml.jackson.annotation.JsonProperty("turnId") String turnId) {
        return new ToolCallEvent(timestamp, toolName, input, output, durationMs, success, errorMessage, id, kind,
                turnIndex != null ? turnIndex : -1, turnId);
    }

    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type());
        map.put("timestamp", timestamp.toString());
        map.put("tool", toolName);
        map.put("kind", kind.wireValue());
        map.put("duration_ms", durationMs);
        map.put("turn_index", turnIndex);
        if (turnId != null) {
            map.put("turn_id", turnId);
        }
        map.put("success", success);
        if (errorMessage != null) {
            map.put("error", errorMessage);
        }
        if (id != null) {
            map.put("id", id);
        }
        return map;
    }

    /** Builder for ToolCallEvent. */
    public static final class Builder {
        private Instant timestamp = Instant.now();
        private String toolName;
        private Map<String, Object> input = Map.of();
        private Object output;
        private long durationMs;
        private boolean success = true;
        private String errorMessage;
        private String id;
        private ToolKind kind = ToolKind.OTHER;
        private int turnIndex = -1;
        private String turnId;

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        public Builder output(Object output) {
            this.output = output;
            return this;
        }

        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder kind(ToolKind kind) {
            this.kind = kind;
            return this;
        }

        /** 0-based ordinal of the model turn that issued this tool call; -1 when unknown. */
        public Builder turnIndex(int turnIndex) {
            this.turnIndex = turnIndex;
            return this;
        }

        /** Identity of the model turn that issued this tool call (the assistant message id). */
        public Builder turnId(String turnId) {
            this.turnId = turnId;
            return this;
        }

        public ToolCallEvent build() {
            return new ToolCallEvent(timestamp, toolName, input, output, durationMs, success, errorMessage, id, kind,
                    turnIndex, turnId);
        }
    }
}
