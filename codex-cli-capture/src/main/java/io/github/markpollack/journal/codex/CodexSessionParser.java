package io.github.markpollack.journal.codex;

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

/** Parses the durable {@code ~/.codex/sessions/.../rollout-*.jsonl} format. */
public final class CodexSessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CodexSessionParser() {
    }

    public static CodexPhaseCapture parse(Path rolloutFile, String phaseName, String promptText) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(rolloutFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

    public static CodexPhaseCapture parse(BufferedReader reader, String phaseName, String promptText)
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
                throw new IOException("Invalid Codex rollout JSONL at line " + lineNumber, ex);
            }
        }
        return state.capture(phaseName, promptText);
    }

    private static final class ParserState {
        private final Map<String, MutableToolCall> tools = new LinkedHashMap<>();

        private String model;
        private String cliVersion;
        private String sessionId;
        private int inputTokens;
        private int outputTokens;
        private int reasoningOutputTokens;
        private int cacheWriteInputTokens;
        private int cachedInputTokens;
        private long durationMs;
        private boolean isError;
        private String textOutput = "";

        void accept(JsonNode envelope) {
            String envelopeType = text(envelope, "type");
            JsonNode payload = envelope.path("payload");
            if ("session_meta".equals(envelopeType)) {
                sessionId = firstNonBlank(text(payload, "session_id"), text(payload, "id"));
                cliVersion = text(payload, "cli_version");
                return;
            }
            if ("turn_context".equals(envelopeType)) {
                model = text(payload, "model");
                return;
            }

            String payloadType = text(payload, "type");
            if (payloadType == null) {
                return;
            }
            switch (payloadType) {
                case "custom_tool_call" -> acceptToolCall(payload);
                case "custom_tool_call_output" -> acceptToolOutput(payload);
                case "token_count" -> acceptTokenCount(payload);
                case "task_complete" -> acceptTaskComplete(payload);
                default -> {
                    // Reasoning, messages, rate limits, and future records do not alter tool pairing.
                }
            }
        }

        private void acceptToolCall(JsonNode payload) {
            String id = text(payload, "call_id");
            if (id == null) {
                return;
            }
            String rawName = text(payload, "name");
            String rawInput = payload.path("input").asText("");
            CodexToolClassifier.Classification classification = CodexToolClassifier.classify(rawName, rawInput);
            MutableToolCall tool = tools.computeIfAbsent(id, MutableToolCall::new);
            tool.name = classification.name();
            tool.rawName = rawName;
            tool.input = classification.input();
            String status = text(payload, "status");
            if (status != null && !"completed".equalsIgnoreCase(status)) {
                tool.isError = true;
                tool.errorMessage = "Codex tool call status: " + status;
            }
        }

        private void acceptToolOutput(JsonNode payload) {
            String id = text(payload, "call_id");
            if (id == null) {
                return;
            }
            MutableToolCall tool = tools.computeIfAbsent(id, MutableToolCall::new);
            tool.output = asObject(payload.get("output"));
            String outputText = payload.path("output").toString();
            if (outputText.contains("Script failed") || outputText.contains("Process exited with code")) {
                tool.isError = true;
                tool.errorMessage = outputText;
            }
        }

        private void acceptTokenCount(JsonNode payload) {
            JsonNode usage = payload.path("info").path("total_token_usage");
            if (!usage.isObject()) {
                return;
            }
            inputTokens = usage.path("input_tokens").asInt(0);
            outputTokens = usage.path("output_tokens").asInt(0);
            reasoningOutputTokens = usage.path("reasoning_output_tokens").asInt(0);
            cacheWriteInputTokens = usage.path("cache_write_input_tokens").asInt(0);
            cachedInputTokens = usage.path("cached_input_tokens").asInt(0);
        }

        private void acceptTaskComplete(JsonNode payload) {
            durationMs = payload.path("duration_ms").asLong(0L);
            textOutput = payload.path("last_agent_message").asText("");
            if (payload.has("status") && !"completed".equalsIgnoreCase(payload.path("status").asText())) {
                isError = true;
            }
        }

        CodexPhaseCapture capture(String phaseName, String promptText) {
            List<CodexToolUseRecord> toolUses = new ArrayList<>(tools.size());
            for (MutableToolCall tool : tools.values()) {
                toolUses.add(tool.freeze());
            }
            return new CodexPhaseCapture(phaseName, promptText, model, cliVersion, sessionId,
                    inputTokens, outputTokens, reasoningOutputTokens, cacheWriteInputTokens,
                    cachedInputTokens, durationMs, isError, textOutput, toolUses);
        }
    }

    private static final class MutableToolCall {
        private final String id;
        private String name = "Shell";
        private String rawName;
        private Map<String, Object> input = Map.of();
        private Object output;
        private boolean isError;
        private String errorMessage;

        MutableToolCall(String id) {
            this.id = id;
        }

        CodexToolUseRecord freeze() {
            return new CodexToolUseRecord(id, name, rawName, input, output, isError, errorMessage);
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

    private static Object asObject(JsonNode node) {
        return node == null || node.isNull() ? null : MAPPER.convertValue(node, Object.class);
    }
}
