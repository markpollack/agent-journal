package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a tool execution.
 *
 * @param timestamp when the tool was called
 * @param toolName name of the tool (e.g., "Bash", "Read", "Write")
 * @param input tool input parameters
 * @param output tool output (null if failed)
 * @param durationMs execution duration in milliseconds
 * @param success whether the tool call succeeded
 * @param errorMessage error message if failed (null if succeeded)
 * @param id stable identity for this step — the vendor tool_use id (e.g. {@code toolu_…})
 *           when available, null otherwise. Persisted so feedback/eval can target a step
 *           by a stable id instead of a reload-order-dependent list position. (Round 2 R2.3)
 */
public record ToolCallEvent(
        Instant timestamp,
        String toolName,
        Map<String, Object> input,
        Object output,
        long durationMs,
        boolean success,
        String errorMessage,
        String id
) implements JournalEvent {

    /** Back-compat constructor for events without a stable id. */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, null);
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

    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type());
        map.put("timestamp", timestamp.toString());
        map.put("tool", toolName);
        map.put("duration_ms", durationMs);
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

        public ToolCallEvent build() {
            return new ToolCallEvent(timestamp, toolName, input, output, durationMs, success, errorMessage, id);
        }
    }
}
