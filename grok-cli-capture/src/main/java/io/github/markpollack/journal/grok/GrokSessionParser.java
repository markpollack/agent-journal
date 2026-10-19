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
     * @throws IOException if the file cannot be read; a line that is not valid JSON is kept as a vendor event
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
                // The CLI prints plain text too (for example when it is not signed in). Keep the line, whole.
                state.keepNonJson(line, lineNumber);
                continue;
            }
            state.accept(event, lineNumber);
        }
        return state.capture(phaseName, promptText);
    }

    private static final class ParserState {
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final Map<String, MutableToolCall> tools = new LinkedHashMap<>();
        private final List<GrokVendorEvent> vendorEvents = new ArrayList<>();

        private int inputTokens = GrokPhaseCapture.NOT_REPORTED;
        private int outputTokens = GrokPhaseCapture.NOT_REPORTED;
        private int thinkingTokens = GrokPhaseCapture.NOT_REPORTED;
        private int cacheCreationInputTokens = GrokPhaseCapture.NOT_REPORTED;
        private int cacheReadInputTokens = GrokPhaseCapture.NOT_REPORTED;
        private double totalCostUsd;
        private boolean costReported;
        private String sessionId;
        private int numTurns;
        private String stopReason;
        private boolean sawEnd;
        private String model;

        void accept(JsonNode event, int lineNumber) {
            String type = text(event, "type");
            if (type == null) {
                keepWhole(event, null, lineNumber);
                return;
            }
            switch (type) {
                case "text" -> text.append(event.path("data").asText(""));
                case "thought" -> thinking.append(event.path("data").asText(""));
                case "tool_call" -> {
                    if (text(event, "toolCallId") == null) {
                        keepWhole(event, type, lineNumber);
                    } else {
                        acceptToolCall(event);
                    }
                }
                case "tool_call_update" -> {
                    if (text(event, "toolCallId") == null) {
                        keepWhole(event, type, lineNumber);
                    } else {
                        acceptToolUpdate(event);
                    }
                }
                case "usage" -> acceptUsage(event.path("usage"), false);
                case "end" -> acceptEnd(event);
                // New ACP/vendor update types: kept whole, never dropped.
                default -> keepWhole(event, type, lineNumber);
            }
        }

        /** Keeps a line the parser does not consume, whole, in output order. */
        private void keepWhole(JsonNode event, String kind, int lineNumber) {
            Map<String, Object> payload = event.isObject()
                    ? MAPPER.convertValue(event, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    })
                    : Map.of("value", event.asText());
            vendorEvents.add(new GrokVendorEvent(kind, lineNumber, payload));
        }

        /** Keeps a line that is not JSON, whole. */
        void keepNonJson(String line, int lineNumber) {
            vendorEvents.add(new GrokVendorEvent(GrokVendorEvent.NON_JSON_LINE, lineNumber, Map.of("text", line)));
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
            tool.noteFailure(event);
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
            tool.noteFailure(event);
        }

        private void acceptEnd(JsonNode event) {
            sawEnd = true;
            sessionId = text(event, "sessionId");
            numTurns = event.path("num_turns").asInt(0);
            stopReason = text(event, "stopReason");
            JsonNode cost = event.path("total_cost_usd");
            costReported = cost.isNumber();
            totalCostUsd = costReported ? cost.asDouble() : 0.0;
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

        /**
         * Takes the token counts a usage object names. A count it does not name is left as it was,
         * so one that no usage object ever named stays not reported rather than becoming 0. Usage
         * lines add to the running count; the {@code end} line replaces the count it names.
         */
        private void acceptUsage(JsonNode usage, boolean replace) {
            if (!usage.isObject()) {
                return;
            }
            inputTokens = merge(inputTokens, usage.path("input_tokens"), replace);
            outputTokens = merge(outputTokens, usage.path("output_tokens"), replace);
            thinkingTokens = merge(thinkingTokens, usage.path("reasoning_tokens"), replace);
            cacheCreationInputTokens = merge(cacheCreationInputTokens, usage.path("cache_creation_input_tokens"),
                    replace);
            cacheReadInputTokens = merge(cacheReadInputTokens, usage.path("cache_read_input_tokens"), replace);
        }

        private static int merge(int current, JsonNode reported, boolean replace) {
            if (!reported.isNumber()) {
                return current;
            }
            int value = reported.asInt();
            return replace || current < 0 ? value : current + value;
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
                    costReported, sessionId, numTurns, isError, stopReason, text.toString(), thinking.toString(), toolUses,
                    vendorEvents);
        }
    }

    private static final class MutableToolCall {
        private final String id;
        private String name;
        private ToolKind kind = ToolKind.OTHER;
        private Map<String, Object> input = Map.of();
        private Object output;
        private String status;
        private String errorMessage;

        MutableToolCall(String id) {
            this.id = id;
        }

        /** Keeps the error message of the latest line that reported the call as failed. */
        void noteFailure(JsonNode event) {
            if (isFailed(text(event, "status"))) {
                errorMessage = errorMessage(event.path("rawOutput"));
            }
        }

        GrokToolUseRecord freeze() {
            // Whether the call failed follows its final status alone
            boolean isError = isFailed(status);
            return new GrokToolUseRecord(id, name != null ? name : "unknown", kind, input, output,
                    status, isError, isError ? errorMessage : null);
        }

        private static boolean isFailed(String status) {
            return "failed".equalsIgnoreCase(status);
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
