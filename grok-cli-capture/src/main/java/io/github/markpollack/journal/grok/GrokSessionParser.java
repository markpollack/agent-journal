package io.github.markpollack.journal.grok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.event.ToolKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the output of one Grok CLI call and returns a {@link GrokPhaseCapture}: the agent's text,
 * thinking and tool calls, its token usage and cost, and why it stopped. Use it after running the
 * CLI with {@code --output-format streaming-json}: pass the saved output file, or a reader over
 * the output, to a {@code parse} method. To store the result as a journal run, pass the capture to
 * a {@link GrokRunRecorder}.
 *
 * <p>Unlike Claude Code's {@code SessionLogParser}, it reads JSON Lines text, not SDK objects, and
 * writes no trace. Each line is one event with a {@code type}. The parser joins the {@code text}
 * and {@code thought} pieces, pairs each {@code tool_call} with its {@code tool_call_update}s by
 * {@code toolCallId}, and takes the session ID, stop reason, turn count, cost, model and token
 * counts from the final {@code end} line. If that line has no usage, the token counts are the sum
 * of the {@code usage} lines. If there is no {@code end} line, as when the CLI was killed, the
 * capture is an error with stop reason {@code "incomplete"}, and the session ID, cost and model
 * are {@code null} or 0. Blank lines, lines without a {@code type} and types it does not know are
 * skipped.
 *
 * <p>A tool call is named by Grok's {@code toolName}, else its {@code title}, else its
 * {@code kind}, else {@code "unknown"}. Its {@link io.github.markpollack.journal.event.ToolKind}
 * comes from Grok's {@code kind} field, and it is marked as an error if its last status is
 * {@code failed}.
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own input.
 */
public final class GrokSessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String INCOMPLETE = "incomplete";

    private GrokSessionParser() {
    }

    /**
     * Parses a saved output file, read as UTF-8.
     *
     * @param streamFile the file holding the CLI's {@code streaming-json} output
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     * @throws IOException if the file cannot be read or a line is not valid JSON
     */
    public static GrokPhaseCapture parse(Path streamFile, String phaseName, String promptText) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(streamFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

    /**
     * Parses the CLI's output from a reader, reading it to the end. The reader is not closed.
     *
     * @param reader the CLI's {@code streaming-json} output
     * @param phaseName the caller's name for this call, such as {@code "plan"} or
     *        {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured
     * @return the capture, never {@code null}
     * @throws IOException if reading fails, or if a line is not valid JSON; the message gives the
     *         line number
     */
    public static GrokPhaseCapture parse(BufferedReader reader, String phaseName, String promptText)
            throws IOException {
        ParserState state = new ParserState();
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode event;
            try {
                event = MAPPER.readTree(line);
            } catch (JsonProcessingException ex) {
                throw new IOException("Invalid Grok streaming-json at line " + lineNumber, ex);
            }
            state.accept(event);
        }
        return state.capture(phaseName, promptText);
    }

    private static final class ParserState {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final Map<String, MutableToolCall> tools = new LinkedHashMap<>();

        private int inputTokens;
        private int outputTokens;
        private int thinkingTokens;
        private int cacheCreationInputTokens;
        private int cacheReadInputTokens;
        private double totalCostUsd;
        private String sessionId;
        private int numTurns;
        private String stopReason;
        private boolean sawEnd;
        private String model;

        void accept(JsonNode event) {
            String type = text(event, "type");
            if (type == null) {
                return;
            }
            switch (type) {
                case "text" -> text.append(event.path("data").asText(""));
                case "thought" -> thinking.append(event.path("data").asText(""));
                case "tool_call" -> acceptToolCall(event);
                case "tool_call_update" -> acceptToolUpdate(event);
                case "usage" -> acceptUsage(event.path("usage"), false);
                case "end" -> acceptEnd(event);
                default -> {
                    // Defensive parsing: new ACP/vendor update types are intentionally ignored.
                }
            }
        }

        private void acceptToolCall(JsonNode event) {
            String id = text(event, "toolCallId");
            if (id == null) {
                return;
            }
            MutableToolCall tool = tools.computeIfAbsent(id, MutableToolCall::new);
            tool.name = firstNonBlank(text(event, "toolName"), text(event, "title"), text(event, "kind"));
            tool.kind = ToolKind.fromWireValue(text(event, "kind"));
            tool.status = text(event, "status");
            tool.input = asMap(event.get("rawInput"));
            if (event.hasNonNull("rawOutput")) {
                tool.output = asObject(event.get("rawOutput"));
            }
        }

        private void acceptToolUpdate(JsonNode event) {
            String id = text(event, "toolCallId");
            if (id == null) {
                return;
            }
            MutableToolCall tool = tools.computeIfAbsent(id, MutableToolCall::new);
            String status = text(event, "status");
            if (status != null) {
                tool.status = status;
            }
            if (event.hasNonNull("rawOutput")) {
                tool.output = asObject(event.get("rawOutput"));
            } else if (event.hasNonNull("content") && event.path("content").size() > 0) {
                tool.output = asObject(event.get("content"));
            }
            if ("failed".equalsIgnoreCase(tool.status)) {
                tool.isError = true;
                tool.errorMessage = errorMessage(event.path("rawOutput"));
            }
        }

        private void acceptEnd(JsonNode event) {
            sawEnd = true;
            sessionId = text(event, "sessionId");
            numTurns = event.path("num_turns").asInt(0);
            stopReason = text(event, "stopReason");
            totalCostUsd = event.path("total_cost_usd").asDouble(0.0);
            acceptUsage(event.path("usage"), true);

            // When several models served the call, name the one that used the most tokens; the
            // first one named wins a tie.
            JsonNode modelUsage = event.path("modelUsage");
            if (modelUsage.isObject()) {
                long most = -1;
                Iterator<Map.Entry<String, JsonNode>> entries = modelUsage.fields();
                while (entries.hasNext()) {
                    Map.Entry<String, JsonNode> entry = entries.next();
                    JsonNode usage = entry.getValue();
                    long tokens = usage.path("inputTokens").asLong(0) + usage.path("outputTokens").asLong(0)
                            + usage.path("cacheReadInputTokens").asLong(0)
                            + usage.path("cacheCreationInputTokens").asLong(0);
                    if (tokens > most) {
                        most = tokens;
                        model = entry.getKey();
                    }
                }
            }
        }

        private void acceptUsage(JsonNode usage, boolean replace) {
            if (!usage.isObject()) {
                return;
            }
            int input = usage.path("input_tokens").asInt(0);
            int output = usage.path("output_tokens").asInt(0);
            int reasoning = usage.path("reasoning_tokens").asInt(0);
            int cacheCreation = usage.path("cache_creation_input_tokens").asInt(0);
            int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
            if (replace) {
                inputTokens = input;
                outputTokens = output;
                thinkingTokens = reasoning;
                cacheCreationInputTokens = cacheCreation;
                cacheReadInputTokens = cacheRead;
            } else {
                inputTokens += input;
                outputTokens += output;
                thinkingTokens += reasoning;
                cacheCreationInputTokens += cacheCreation;
                cacheReadInputTokens += cacheRead;
            }
        }

        GrokPhaseCapture capture(String phaseName, String promptText) {
            List<GrokToolUseRecord> toolUses = new ArrayList<>(tools.size());
            for (MutableToolCall tool : tools.values()) {
                toolUses.add(tool.freeze());
            }
            // A stream with no end line was cut off, for example because the CLI was killed
            String stopReason = sawEnd ? this.stopReason : INCOMPLETE;
            boolean isError = !sawEnd
                    || "error".equalsIgnoreCase(stopReason)
                    || "cancelled".equalsIgnoreCase(stopReason);
            return new GrokPhaseCapture(phaseName, promptText, model, inputTokens, outputTokens,
                    thinkingTokens, cacheCreationInputTokens, cacheReadInputTokens, totalCostUsd,
                    sessionId, numTurns, isError, stopReason, text.toString(), thinking.toString(), toolUses);
        }
    }

    private static final class MutableToolCall {
        private final String id;
        private String name;
        private ToolKind kind = ToolKind.OTHER;
        private Map<String, Object> input = Map.of();
        private Object output;
        private String status;
        private boolean isError;
        private String errorMessage;

        MutableToolCall(String id) {
            this.id = id;
        }

        GrokToolUseRecord freeze() {
            return new GrokToolUseRecord(id, name != null ? name : "unknown", kind, input, output,
                    status, isError, errorMessage);
        }
    }

    private static String errorMessage(JsonNode output) {
        if (output == null || output.isMissingNode() || output.isNull()) {
            return null;
        }
        JsonNode nested = output.path("error").path("message");
        if (nested.isTextual()) {
            return nested.asText();
        }
        JsonNode direct = output.path("message");
        return direct.isTextual() ? direct.asText() : output.toString();
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
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return MAPPER.convertValue(node, Map.class);
    }

    private static Object asObject(JsonNode node) {
        return node == null || node.isNull() ? null : MAPPER.convertValue(node, Object.class);
    }
}
