package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.event.ToolKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a Codex CLI rollout file and returns a {@link CodexPhaseCapture}: the agent's final
 * message, its tool calls, its token usage and how long it took. Use it after a Codex session
 * has ended: pass the rollout file that Codex keeps under {@code ~/.codex/sessions/}, or a reader
 * over its lines, to a {@code parse} method. To store the result as a journal run, pass the
 * capture to a {@link CodexRunRecorder}.
 *
 * <p>Unlike the Claude Code and Grok parsers, it reads the session record that Codex writes for
 * itself, not the output stream of a call, and it writes no trace. Codex reports no cost, turn
 * count or stop reason, and the parser does not keep the agent's thinking or its earlier
 * messages, so the capture has none of these.
 *
 * <p>Each line is a JSON envelope with a {@code type} and a {@code payload}. The parser takes the
 * session ID and CLI version from {@code session_meta}, the model from the last
 * {@code turn_context}, the token counts from the last {@code token_count} (Codex's running total
 * for the session), and the duration and final message from {@code task_complete}. It pairs each
 * {@code custom_tool_call} with its {@code custom_tool_call_output} by {@code call_id}. Blank
 * lines and other record types are skipped.
 *
 * <p>Codex names almost every tool call {@code exec} and puts the real action in the call's
 * input, for example {@code tools.exec_command({"cmd":"rg ..."})}. So the parser reads that input,
 * without running it, and sets the {@link io.github.markpollack.journal.event.ToolKind} from the
 * shell command it finds: {@code rg} is {@code SEARCH}, {@code ls} is {@code READ}, and a command
 * it does not know is {@code EXECUTE}. Other Codex tools, such as {@code apply_patch}, have fixed
 * kinds, and input with no such call is {@code OTHER}. The tool name
 * stays {@code exec}; the parsed command and the raw input are kept in the tool's input map, so
 * the call can be classified again later.
 *
 * <p>A tool call is marked as an error when its status is anything other than
 * {@code completed}, or when its output text contains {@code Script failed} or reports a non-zero
 * exit code ({@code Process exited with code N} with N other than 0).
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own input.
 */
public final class CodexSessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern EXIT_CODE = Pattern.compile("Process exited with code (\\d+)");

    private CodexSessionParser() {
    }

    /**
     * Parses a rollout file, read as UTF-8.
     *
     * @param rolloutFile the Codex rollout file, a {@code rollout-*.jsonl} file
     * @param phaseName the caller's name for this session or part of it, such as
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     * @throws IOException if the file cannot be read or a line is not valid JSON
     */
    public static CodexPhaseCapture parse(Path rolloutFile, String phaseName, String promptText) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(rolloutFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

    /**
     * Parses rollout lines from a reader, reading it to the end. The reader is not closed.
     *
     * @param reader the rollout lines
     * @param phaseName the caller's name for this session or part of it, such as
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     * @throws IOException if reading fails, or if a line is not valid JSON; the message gives the
     *         line number
     */
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
            tool.kind = classification.kind();
            tool.name = rawName;
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
            if (outputText.contains("Script failed") || hasNonZeroExitCode(outputText)) {
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
        private ToolKind kind = ToolKind.OTHER;
        private String name = "unknown";
        private Map<String, Object> input = Map.of();
        private Object output;
        private boolean isError;
        private String errorMessage;

        MutableToolCall(String id) {
            this.id = id;
        }

        CodexToolUseRecord freeze() {
            return new CodexToolUseRecord(id, kind, name, input, output, isError, errorMessage);
        }
    }

    private static boolean hasNonZeroExitCode(String outputText) {
        Matcher exit = EXIT_CODE.matcher(outputText);
        while (exit.find()) {
            if (!exit.group(1).matches("0+")) {
                return true;
            }
        }
        return false;
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
