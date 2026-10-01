package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records one tool call made by an agent: the tool's name and {@link ToolKind}, its input and
 * output, whether it succeeded, how long it took, and which model turn issued it. Log one with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}, built with a
 * {@code success} or {@code failure} factory method or with {@link #builder()}. The run recorders
 * in the capture modules log one per tool call in a phase, with the vendor's tool-call ID as
 * {@link #id()}. Read it back with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)};
 * {@link io.github.markpollack.journal.eval.EvalSubjectSources} turns it into a {@code TOOL_CALL}
 * evaluation subject, identified by {@link #id()} when it is set, so feedback can target the call
 * by that ID.
 *
 * <p>{@link #toolName()} keeps the vendor's own name for the tool, such as {@code Bash},
 * {@code view_file} or {@code exec}, and {@link #kind()} gives its vendor-neutral category, so
 * tool calls from different agents can be compared. A {@code null} kind becomes
 * {@link ToolKind#OTHER}. Claude Code's recorder records no output; on a failed call it puts the
 * tool's result text in {@link #errorMessage()}.
 *
 * <p>Some fields use a special value for "not known". {@link #turnIndex()} is -1 when the turn is
 * not known; only Claude Code's recorder sets it and {@link #turnId()}, and events written before
 * those fields existed load with -1. {@link #durationMs()} is -1 or 0 when the time was not
 * measured: Claude Code's recorder writes -1 for a call that got no result, and the builder's
 * default, which the Grok, Codex and Junie recorders keep, is 0. So a duration of 0 does not mean
 * that the call took no time.
 *
 * <p>The record does not copy {@code input}. File storage writes it with {@code @type}
 * {@code "tool_call"}, including the input and output, which can hold file contents.
 *
 * @param timestamp when the call was recorded
 * @param toolName the vendor's name for the tool, such as {@code "Bash"}, {@code "view_file"} or
 *        {@code "exec"}
 * @param input the tool's input parameters
 * @param output the tool's output, or {@code null} if it failed or no output was recorded
 * @param durationMs the time from the tool call being issued to its result arriving, in
 *        milliseconds, or -1 or 0 if it was not measured
 * @param success whether the tool call succeeded
 * @param errorMessage the error text, or {@code null} if the call succeeded
 * @param id the vendor's ID for this tool call, such as {@code toolu_...}, or {@code null} if
 *        there is none
 * @param kind the vendor-neutral category of the tool; never {@code null}
 * @param turnIndex the 0-based number of the model turn that issued the call, or -1 if not known
 * @param turnId the ID of the model turn that issued the call (the assistant message ID, such as
 *        {@code msg_...}), or {@code null} if not known
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

    /**
     * Creates a tool call event, replacing a {@code null} kind with {@link ToolKind#OTHER}. See
     * the class description for what each component means.
     *
     * @param timestamp when the call was recorded
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param output the output
     * @param durationMs the duration in milliseconds
     * @param success whether the call succeeded
     * @param errorMessage the error text
     * @param id the vendor's tool-call ID
     * @param kind the tool category
     * @param turnIndex the 0-based turn number
     * @param turnId the turn ID
     */
    public ToolCallEvent {
        kind = kind != null ? kind : ToolKind.OTHER;
    }

    /**
     * Creates a tool call event with no turn: {@code turnIndex} is -1, not 0, so the call is not
     * mistaken for one from the first turn, and {@code turnId} is {@code null}.
     *
     * @param timestamp when the call was recorded
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param output the output
     * @param durationMs the duration in milliseconds
     * @param success whether the call succeeded
     * @param errorMessage the error text
     * @param id the vendor's tool-call ID
     * @param kind the tool category
     */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage, String id, ToolKind kind) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, id, kind, -1, null);
    }

    /**
     * Creates a tool call event with kind {@link ToolKind#OTHER} and no turn.
     *
     * @param timestamp when the call was recorded
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param output the output
     * @param durationMs the duration in milliseconds
     * @param success whether the call succeeded
     * @param errorMessage the error text
     * @param id the vendor's tool-call ID
     */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage, String id) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, id, ToolKind.OTHER);
    }

    /**
     * Creates a tool call event with no ID, kind {@link ToolKind#OTHER} and no turn.
     *
     * @param timestamp when the call was recorded
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param output the output
     * @param durationMs the duration in milliseconds
     * @param success whether the call succeeded
     * @param errorMessage the error text
     */
    public ToolCallEvent(Instant timestamp, String toolName, Map<String, Object> input, Object output,
            long durationMs, boolean success, String errorMessage) {
        this(timestamp, toolName, input, output, durationMs, success, errorMessage, null, ToolKind.OTHER);
    }

    @Override
    public String type() {
        return "tool_call";
    }

    /**
     * Creates an event for a successful tool call, with the current time, no ID, kind
     * {@link ToolKind#OTHER} and no turn.
     *
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param output the output
     * @param durationMs the duration in milliseconds
     * @return the new event
     */
    public static ToolCallEvent success(String toolName, Map<String, Object> input,
                                        Object output, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, output, durationMs, true, null);
    }

    /**
     * Creates an event for a successful tool call with the vendor's tool-call ID, the current
     * time, kind {@link ToolKind#OTHER} and no turn.
     *
     * @param id the vendor's tool-call ID
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param output the output
     * @param durationMs the duration in milliseconds
     * @return the new event
     */
    public static ToolCallEvent success(String id, String toolName, Map<String, Object> input,
                                        Object output, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, output, durationMs, true, null, id);
    }

    /**
     * Creates an event for a failed tool call, with the current time, no output, no ID, kind
     * {@link ToolKind#OTHER} and no turn.
     *
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param error the error text
     * @param durationMs the duration in milliseconds
     * @return the new event
     */
    public static ToolCallEvent failure(String toolName, Map<String, Object> input,
                                        String error, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, null, durationMs, false, error);
    }

    /**
     * Creates an event for a failed tool call with the vendor's tool-call ID, the current time,
     * no output, kind {@link ToolKind#OTHER} and no turn.
     *
     * @param id the vendor's tool-call ID
     * @param toolName the vendor's tool name
     * @param input the input parameters
     * @param error the error text
     * @param durationMs the duration in milliseconds
     * @return the new event
     */
    public static ToolCallEvent failure(String id, String toolName, Map<String, Object> input,
                                        String error, long durationMs) {
        return new ToolCallEvent(Instant.now(), toolName, input, null, durationMs, false, error, id);
    }

    /**
     * Returns a new builder. It starts with the current time, an empty input, success, kind
     * {@link ToolKind#OTHER}, a duration of 0 and a turn index of -1.
     *
     * @return the new builder
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code type}, {@code timestamp}, {@code tool}, {@code kind} (the wire value,
     * such as {@code "read"}), {@code duration_ms}, {@code turn_index} and {@code success}, then
     * {@code turn_id}, {@code error} and {@code id} when they are set. The input and output are
     * left out.
     *
     * @throws NullPointerException if {@link #timestamp()} is {@code null}
     */
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

    /**
     * Builds a {@link ToolCallEvent}. The timestamp defaults to the time the builder was created,
     * the input to an empty map, {@code success} to {@code true}, the kind to
     * {@link ToolKind#OTHER}, the duration to 0, the turn index to -1, and the other fields to
     * {@code null}. Set the duration to -1 when it was not measured. See the
     * {@link ToolCallEvent} components for what each field means. A builder is not safe for use
     * from several threads.
     */
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
         * Sets the vendor's name for the tool.
         *
         * @param toolName the tool name
         * @return this builder
         */
        public Builder toolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        /**
         * Sets the input parameters. The map is not copied.
         *
         * @param input the input parameters
         * @return this builder
         */
        public Builder input(Map<String, Object> input) {
            this.input = input;
            return this;
        }

        /**
         * Sets the tool's output.
         *
         * @param output the output
         * @return this builder
         */
        public Builder output(Object output) {
            this.output = output;
            return this;
        }

        /**
         * Sets the duration, or -1 if it was not measured.
         *
         * @param durationMs the duration in milliseconds
         * @return this builder
         */
        public Builder durationMs(long durationMs) {
            this.durationMs = durationMs;
            return this;
        }

        /**
         * Sets whether the call succeeded.
         *
         * @param success whether the call succeeded
         * @return this builder
         */
        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        /**
         * Sets the error text.
         *
         * @param errorMessage the error text
         * @return this builder
         */
        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        /**
         * Sets the vendor's ID for the tool call.
         *
         * @param id the tool-call ID
         * @return this builder
         */
        public Builder id(String id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the tool category.
         *
         * @param kind the tool category
         * @return this builder
         */
        public Builder kind(ToolKind kind) {
            this.kind = kind;
            return this;
        }

        /**
         * Sets the 0-based number of the model turn that issued the call.
         *
         * @param turnIndex the turn number, or -1 if not known
         * @return this builder
         */
        public Builder turnIndex(int turnIndex) {
            this.turnIndex = turnIndex;
            return this;
        }

        /**
         * Sets the ID of the model turn that issued the call (the assistant message ID).
         *
         * @param turnId the turn ID
         * @return this builder
         */
        public Builder turnId(String turnId) {
            this.turnId = turnId;
            return this;
        }

        /**
         * Returns a new event with the values set so far.
         *
         * @return the new event
         */
        public ToolCallEvent build() {
            return new ToolCallEvent(timestamp, toolName, input, output, durationMs, success, errorMessage, id, kind,
                    turnIndex, turnId);
        }
    }
}
