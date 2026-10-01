package io.github.markpollack.journal.antigravity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the output of one Antigravity CLI call and returns an {@link AntigravityPhaseCapture}: the
 * agent's final response, its tool steps, its token usage, how long it took, and whether it
 * succeeded. Use it after running the CLI with {@code --output-format stream-json}: pass the saved
 * output file, or a reader over the output, to a {@code parse} method. To store the result as a
 * journal run, pass the capture to an {@link AntigravityRunRecorder}.
 *
 * <p>Unlike Claude Code's {@code SessionLogParser}, it reads JSON Lines text, not SDK objects, and
 * writes no trace. Antigravity reports no cost.
 *
 * <p>Each line is one event, named by its {@code event} field. The parser takes the conversation
 * ID and model from {@code init}, tool steps from {@code step_update} events whose
 * {@code step_type} is {@code tool}, and the status, final response, error, duration, turn count
 * and token counts from the final {@code result}. Blank lines, other events and other step types
 * are skipped, including the usage and text on other step updates.
 *
 * <p>Antigravity sends several updates for one tool step, for example {@code ACTIVE} and then
 * {@code DONE} or {@code ERROR}. The parser joins them by {@code step_index} into one tool record
 * that keeps the latest name, parameters, output, state and duration. A step is an error if its
 * state is {@code ERROR} or it carries an {@code error} object. Antigravity gives tool steps no
 * ID, so the record's ID is built as {@code <conversationId>:step:<index>}, with
 * {@code antigravity} in place of a missing conversation ID. The
 * {@link io.github.markpollack.journal.event.ToolKind} comes from the tool name.
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own input.
 */
public final class AntigravitySessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AntigravitySessionParser() {
    }

    /**
     * Parses a saved output file, read as UTF-8.
     *
     * @param streamFile the file holding the CLI's {@code stream-json} output
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     * @throws IOException if the file cannot be read or a line is not valid JSON
     */
    public static AntigravityPhaseCapture parse(Path streamFile, String phaseName, String promptText)
            throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(streamFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

    /**
     * Parses the CLI's output from a reader, reading it to the end. The reader is not closed.
     *
     * @param reader the CLI's {@code stream-json} output
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     * @throws IOException if reading fails, or if a line is not valid JSON; the message gives the
     *         line number
     */
    public static AntigravityPhaseCapture parse(BufferedReader reader, String phaseName, String promptText)
            throws IOException {
        ParserState state = new ParserState();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                state.accept(MAPPER.readTree(line));
            } catch (JsonProcessingException ex) {
                throw new IOException("Invalid Antigravity stream-json at line " + lineNumber, ex);
            }
        }
        return state.capture(phaseName, promptText);
    }

    private static final class ParserState {
        private final Map<Integer, MutableToolCall> tools = new LinkedHashMap<>();

        private String model;
        private String conversationId;
        private int inputTokens;
        private int outputTokens;
        private int thinkingTokens;
        private int cacheReadTokens;
        private long durationMs;
        private int numTurns;
        private boolean isError;
        private String status;
        private String textOutput = "";
        private String errorMessage;

        void accept(JsonNode event) {
            String eventType = text(event, "event");
            if ("init".equals(eventType)) {
                conversationId = text(event, "conversation_id");
                model = text(event.path("init"), "model");
            } else if ("step_update".equals(eventType)) {
                acceptStep(event.path("step_update"));
            } else if ("result".equals(eventType)) {
                acceptResult(event.path("result"));
            }
        }

        private void acceptStep(JsonNode step) {
            if (!"tool".equals(text(step, "step_type"))) {
                return;
            }
            int index = step.path("step_index").asInt(-1);
            if (index < 0) {
                return;
            }
            MutableToolCall tool = tools.computeIfAbsent(index, MutableToolCall::new);
            JsonNode info = step.path("tool_info");
            tool.name = firstNonBlank(text(info, "name"), text(step, "tool_name"), tool.name);
            if (info.path("parameters").isObject()) {
                tool.input = asMap(info.path("parameters"));
            }
            if (info.hasNonNull("output")) {
                tool.output = asObject(info.get("output"));
            }
            String state = text(step, "state");
            if (state != null) {
                tool.state = state;
            }
            if (step.has("duration_seconds")) {
                tool.durationMs = Math.round(step.path("duration_seconds").asDouble(0.0) * 1000.0);
            }
            JsonNode error = info.path("error");
            if ("ERROR".equalsIgnoreCase(tool.state) || error.isObject()) {
                tool.isError = true;
                tool.errorMessage = text(error, "message");
                if (tool.errorMessage == null && error.isObject()) {
                    tool.errorMessage = error.toString();
                }
            }
        }

        private void acceptResult(JsonNode result) {
            conversationId = firstNonBlank(text(result, "conversation_id"), conversationId);
            status = text(result, "status");
            isError = status != null && !"SUCCESS".equalsIgnoreCase(status);
            textOutput = result.path("response").asText("");
            errorMessage = text(result, "error");
            durationMs = Math.round(result.path("duration_seconds").asDouble(0.0) * 1000.0);
            numTurns = result.path("num_turns").asInt(0);
            JsonNode usage = result.path("usage");
            inputTokens = usage.path("input_tokens").asInt(0);
            outputTokens = usage.path("output_tokens").asInt(0);
            thinkingTokens = usage.path("thinking_tokens").asInt(0);
            cacheReadTokens = usage.path("cache_read_tokens").asInt(0);
        }

        AntigravityPhaseCapture capture(String phaseName, String promptText) {
            List<AntigravityToolUseRecord> toolUses = new ArrayList<>(tools.size());
            for (MutableToolCall tool : tools.values()) {
                toolUses.add(tool.freeze(conversationId));
            }
            return new AntigravityPhaseCapture(phaseName, promptText, model, conversationId,
                    inputTokens, outputTokens, thinkingTokens, cacheReadTokens, durationMs,
                    numTurns, isError, status, textOutput, errorMessage, toolUses);
        }
    }

    private static final class MutableToolCall {
        private final int stepIndex;
        private String name = "unknown";
        private Map<String, Object> input = Map.of();
        private Object output;
        private long durationMs;
        private String state;
        private boolean isError;
        private String errorMessage;

        MutableToolCall(int stepIndex) {
            this.stepIndex = stepIndex;
        }

        AntigravityToolUseRecord freeze(String conversationId) {
            String prefix = conversationId != null ? conversationId : "antigravity";
            return new AntigravityToolUseRecord(prefix + ":step:" + stepIndex, stepIndex,
                    AntigravityToolClassifier.classify(name), name,
                    input, output, durationMs, state, isError, errorMessage);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(JsonNode node) {
        return node == null || !node.isObject() ? Map.of() : MAPPER.convertValue(node, Map.class);
    }

    private static Object asObject(JsonNode node) {
        return node == null || node.isNull() ? null : MAPPER.convertValue(node, Object.class);
    }
}
