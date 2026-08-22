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

/** Parses Antigravity CLI {@code --output-format stream-json} output. */
public final class AntigravitySessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AntigravitySessionParser() {
    }

    public static AntigravityPhaseCapture parse(Path streamFile, String phaseName, String promptText)
            throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(streamFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

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
            return new AntigravityToolUseRecord(prefix + ":step:" + stepIndex, stepIndex, name,
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
