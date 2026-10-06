package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.event.ToolKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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
 * for the session), and the duration and final message from {@code task_complete}. A rollout with
 * no {@code task_complete}, as when Codex was killed or the turn was interrupted, is an error with
 * no duration and no final message. It pairs each {@code custom_tool_call} with its
 * {@code custom_tool_call_output}, and each {@code function_call} with its
 * {@code function_call_output}, by {@code call_id}, keeping the calls in the order they are first
 * seen. An output whose call is missing gives a call named {@code unknown}, and a record with no
 * {@code call_id} is skipped. Blank lines and other record types are skipped; another record type
 * that carries a {@code call_id} is logged at debug level.
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
 * <p>A {@code function_call} keeps its own name, such as {@code exec_command} or {@code shell}.
 * Its input map holds {@code codex_record_type} {@code "function_call"}, the name as
 * {@code codex_tool}, the {@code arguments} string unchanged as {@code raw_input}, and, when that
 * string is a JSON object, the parsed object unchanged as {@code arguments}. The command the kind
 * was taken from is added as {@code command_text} beside them, never in their place, and
 * {@code classification_source} says where the kind came from.
 *
 * <p>A {@code custom_tool_call} is marked as an error when its status is anything other than
 * {@code completed}, or when its output text contains {@code Script failed} or reports a non-zero
 * exit code ({@code Process exited with code N} with N other than 0). A {@code function_call} has
 * no status; it is an error when its output text contains {@code Script failed},
 * {@code Process exited with code N}, {@code Exit code: N} or an {@code exit_code} field of N, with
 * N other than 0.
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own input.
 */
public final class CodexSessionParser {

    private static final Logger log = LoggerFactory.getLogger(CodexSessionParser.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern EXIT_CODE = Pattern.compile("Process exited with code (\\d+)");

    // Only function_call_output is checked for these, so custom tool call records stay as they were.
    private static final Pattern SHELL_EXIT_CODE = Pattern.compile("Exit code: (-?\\d+)");

    // Matches the field in the output's JSON text, where a string output escapes its quotes.
    private static final Pattern STRUCTURED_EXIT_CODE = Pattern.compile("exit_code\\\\?\"\\s*:\\s*(-?\\d+)");

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

    /**
     * Parses the root rollout of {@code rollouts} as {@link #parse(BufferedReader, String, String)}
     * does and each child rollout as a sub-agent track.
     *
     * <p>A child's identity is the {@code id} of the first {@code session_meta} in its file; a forked
     * child's replayed records (those with an {@code ordinal} below its
     * {@code subagent_history_start_ordinal}) are skipped and counted. A child is joined to the
     * {@code spawn_agent} call of its parent thread through the parent's {@code SubAgentActivity}
     * {@code started} record that names the child's thread id. A child whose parent thread is not
     * among the rollouts is still captured, without a spawn call id and with depth -1. Tokens, tool
     * uses and text are read from each file only.
     *
     * @throws IllegalArgumentException if {@code rollouts} has no root or several
     * @throws IOException if a line is not valid JSON
     */
    public static CodexPhaseCapture parse(CodexRollouts rollouts, String phaseName, String promptText)
            throws IOException {
        CodexRollout root = rollouts.root();
        ParserState rootState = read(root, false);
        CodexPhaseCapture core = rootState.capture(phaseName, promptText);

        Map<String, ParserState> childStates = new LinkedHashMap<>();
        Map<String, ParserState> statesByThread = new LinkedHashMap<>();
        statesByThread.put(firstNonBlank(rootState.sessionId, root.threadId()), rootState);
        for (CodexRollout child : rollouts.children()) {
            ParserState state = read(child, true);
            String threadId = firstNonBlank(state.threadId, child.threadId());
            if (state.parentThreadId == null) {
                state.parentThreadId = child.parentThreadId();
            }
            childStates.put(threadId, state);
            statesByThread.put(threadId, state);
        }

        List<CodexSubagentCapture> subagents = new ArrayList<>(childStates.size());
        for (Map.Entry<String, ParserState> entry : childStates.entrySet()) {
            subagents.add(entry.getValue().subagent(entry.getKey(), statesByThread, rootState));
        }
        return new CodexPhaseCapture(core.phaseName(), core.promptText(), core.model(), core.cliVersion(),
                core.sessionId(), core.inputTokens(), core.outputTokens(), core.reasoningOutputTokens(),
                core.cacheWriteInputTokens(), core.cachedInputTokens(), core.durationMs(), core.isError(),
                core.textOutput(), core.toolUses(), subagents, true, rootState.spawnedThreadIds);
    }

    private static ParserState read(CodexRollout rollout, boolean childTrack) throws IOException {
        ParserState state = new ParserState(childTrack);
        int lineNumber = 0;
        for (String line : rollout.lines()) {
            lineNumber++;
            if (line == null || line.isBlank()) {
                continue;
            }
            try {
                state.accept(MAPPER.readTree(line));
            } catch (JsonProcessingException ex) {
                throw new IOException("Invalid Codex rollout JSONL at line " + lineNumber + " of thread "
                        + rollout.threadId(), ex);
            }
        }
        return state;
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
        private boolean sawTaskComplete;
        private String textOutput = "";
        // The child's last own turn-terminal record: task_complete -> completed, turn_aborted -> interrupted.
        private String lastTurnTerminal;

        // Sub-agent evidence of this thread: spawn call id -> child thread id / agent path from
        // SubAgentActivity "started", and child thread id -> last later activity kind.
        private final Map<String, String> spawnedThreadIds = new LinkedHashMap<>();
        private final Map<String, String> spawnedAgentPaths = new LinkedHashMap<>();
        private final Map<String, String> activityKindByThread = new LinkedHashMap<>();

        // Child-track state. Only the first session_meta is read; records below the fork's history
        // start are skipped and counted.
        private final boolean childTrack;
        private boolean sawSessionMeta;
        private String threadId;
        private String parentThreadId;
        private String sourceAgentPath;
        private boolean forked;
        private long historyStartOrdinal = -1;
        private int replayedRecordsSkipped;
        private String firstUserMessage;

        ParserState() {
            this(false);
        }

        ParserState(boolean childTrack) {
            this.childTrack = childTrack;
        }

        void accept(JsonNode envelope) {
            String envelopeType = text(envelope, "type");
            JsonNode payload = envelope.path("payload");
            if (childTrack && sawSessionMeta && historyStartOrdinal >= 0
                    && envelope.path("ordinal").asLong(Long.MAX_VALUE) < historyStartOrdinal) {
                replayedRecordsSkipped++;
                return;
            }
            if ("session_meta".equals(envelopeType)) {
                if (childTrack) {
                    acceptChildSessionMeta(payload);
                    return;
                }
                sessionId = firstNonBlank(text(payload, "session_id"), text(payload, "id"));
                cliVersion = text(payload, "cli_version");
                return;
            }
            if ("event_msg".equals(envelopeType) && "item_completed".equals(text(payload, "type"))) {
                acceptItemCompleted(payload.path("item"));
                return;
            }
            if (childTrack && firstUserMessage == null) {
                firstUserMessage = userMessageText(payload);
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
                case "function_call" -> acceptFunctionCall(payload);
                case "function_call_output" -> acceptFunctionCallOutput(payload);
                case "token_count" -> acceptTokenCount(payload);
                case "task_complete" -> acceptTaskComplete(payload);
                case "turn_aborted" -> lastTurnTerminal = "interrupted";
                default -> {
                    // Reasoning, messages, rate limits, and future records do not alter tool pairing.
                    if (text(payload, "call_id") != null) {
                        log.debug("Skipping Codex record of type {} with call_id {}", payloadType,
                                text(payload, "call_id"));
                    }
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

        private void acceptFunctionCall(JsonNode payload) {
            String id = text(payload, "call_id");
            if (id == null) {
                return;
            }
            String name = firstNonBlank(text(payload, "name"), "unknown");
            CodexToolClassifier.Classification classification =
                    CodexToolClassifier.classifyFunctionCall(name, argumentsText(payload.get("arguments")));
            MutableToolCall tool = tools.computeIfAbsent(id, MutableToolCall::new);
            tool.kind = classification.kind();
            tool.name = name;
            tool.input = classification.input();
        }

        private void acceptFunctionCallOutput(JsonNode payload) {
            String id = text(payload, "call_id");
            if (id == null) {
                return;
            }
            MutableToolCall tool = tools.computeIfAbsent(id, MutableToolCall::new);
            tool.output = asObject(payload.get("output"));
            String outputText = payload.path("output").toString();
            if (outputText.contains("Script failed") || hasNonZeroExitCode(outputText)
                    || hasNonZero(SHELL_EXIT_CODE, outputText) || hasNonZero(STRUCTURED_EXIT_CODE, outputText)) {
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

        private void acceptChildSessionMeta(JsonNode payload) {
            if (sawSessionMeta) {
                return; // a forked child replays its parent's session_meta
            }
            sawSessionMeta = true;
            threadId = text(payload, "id");
            sessionId = firstNonBlank(text(payload, "session_id"), threadId);
            cliVersion = text(payload, "cli_version");
            parentThreadId = text(payload, "parent_thread_id");
            forked = text(payload, "forked_from_id") != null;
            historyStartOrdinal = payload.path("subagent_history_start_ordinal").asLong(-1);
            sourceAgentPath = text(payload.path("source"), "subagent");
        }

        private void acceptItemCompleted(JsonNode item) {
            if (!"SubAgentActivity".equals(text(item, "type"))) {
                return;
            }
            String kind = text(item, "kind");
            String agentThreadId = text(item, "agent_thread_id");
            if (agentThreadId == null || kind == null) {
                return;
            }
            if ("started".equals(kind)) {
                String callId = text(item, "id");
                if (callId != null) {
                    spawnedThreadIds.put(callId, agentThreadId);
                    String agentPath = text(item, "agent_path");
                    if (agentPath != null) {
                        spawnedAgentPaths.put(callId, agentPath);
                    }
                }
            } else if ("completed".equals(kind) || "interrupted".equals(kind)) {
                activityKindByThread.put(agentThreadId, kind); // the last one in file order wins
            }
        }

        private void acceptTaskComplete(JsonNode payload) {
            sawTaskComplete = true;
            lastTurnTerminal = "completed";
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
                    cachedInputTokens, durationMs, isError || !sawTaskComplete, textOutput, toolUses);
        }

        CodexSubagentCapture subagent(String ownThreadId, Map<String, ParserState> statesByThread,
                ParserState rootState) {
            ParserState parent = parentThreadId == null ? null : statesByThread.get(parentThreadId);
            String spawnCallId = parent == null ? null : parent.spawnCallIdOf(ownThreadId);
            String parentSpawnCallId = null;
            int depth = -1;
            if (parent == rootState) {
                depth = 1;
            } else if (parent != null) {
                int parentDepth = parent.depthOf(parent.parentThreadId, statesByThread, rootState, new ArrayList<>());
                depth = parentDepth < 0 ? -1 : parentDepth + 1;
                parentSpawnCallId = parent.parentThreadId == null ? null
                        : statesByThread.containsKey(parent.parentThreadId)
                        ? statesByThread.get(parent.parentThreadId).spawnCallIdOf(parentThreadId) : null;
            }
            String agentPath = spawnCallId != null && parent.spawnedAgentPaths.containsKey(spawnCallId)
                    ? parent.spawnedAgentPaths.get(spawnCallId) : sourceAgentPath;

            // C5: Codex writes no thread-terminal status. The parent's last completed/interrupted
            // activity for this thread outranks the child's own last turn-terminal record.
            String status;
            String statusSource;
            if (parent != null && parent.activityKindByThread.containsKey(ownThreadId)) {
                status = parent.activityKindByThread.get(ownThreadId);
                statusSource = "parent_sub_agent_activity_last";
            } else if (lastTurnTerminal != null) {
                status = lastTurnTerminal;
                statusSource = "child_last_turn";
            } else {
                status = "unknown";
                statusSource = "none";
            }

            List<CodexToolUseRecord> toolUses = new ArrayList<>(tools.size());
            for (MutableToolCall tool : tools.values()) {
                toolUses.add(tool.freeze());
            }
            return new CodexSubagentCapture(ownThreadId, spawnCallId, parentSpawnCallId, depth, agentPath,
                    status, statusSource, forked, replayedRecordsSkipped, cliVersion, model, firstUserMessage,
                    textOutput, toolUses, inputTokens, outputTokens, reasoningOutputTokens,
                    cacheWriteInputTokens, cachedInputTokens, sawTaskComplete ? durationMs : -1L);
        }

        private String spawnCallIdOf(String childThreadId) {
            for (Map.Entry<String, String> entry : spawnedThreadIds.entrySet()) {
                if (childThreadId.equals(entry.getValue())) {
                    return entry.getKey();
                }
            }
            return null;
        }

        // Depth of this state: 1 for a child of the root; -1 when the chain does not reach the root.
        private int depthOf(String myParentThreadId, Map<String, ParserState> statesByThread,
                ParserState rootState, List<ParserState> visited) {
            if (visited.contains(this)) {
                return -1;
            }
            visited.add(this);
            ParserState parent = myParentThreadId == null ? null : statesByThread.get(myParentThreadId);
            if (parent == null) {
                return -1;
            }
            if (parent == rootState) {
                return 1;
            }
            int parentDepth = parent.depthOf(parent.parentThreadId, statesByThread, rootState, visited);
            return parentDepth < 0 ? -1 : parentDepth + 1;
        }
    }

    // The text of a user message record: response_item/message with role user, or
    // event_msg/user_message. Null for any other record.
    private static String userMessageText(JsonNode payload) {
        String type = text(payload, "type");
        if ("user_message".equals(type)) {
            return text(payload, "message");
        }
        if ("message".equals(type) && "user".equals(text(payload, "role"))) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : payload.path("content")) {
                String partText = text(part, "text");
                if (partText != null) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(partText);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
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

    private static boolean hasNonZero(Pattern exitCode, String outputText) {
        Matcher exit = exitCode.matcher(outputText);
        while (exit.find()) {
            if (!exit.group(1).matches("-?0+")) {
                return true;
            }
        }
        return false;
    }

    // Codex writes the arguments as a JSON string; anything else is kept as its JSON text.
    private static String argumentsText(JsonNode arguments) {
        if (arguments == null || arguments.isNull() || arguments.isMissingNode()) {
            return "";
        }
        return arguments.isTextual() ? arguments.asText() : arguments.toString();
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
