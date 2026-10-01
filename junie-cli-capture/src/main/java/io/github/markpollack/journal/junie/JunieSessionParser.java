package io.github.markpollack.journal.junie;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.ToolKind;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Junie CLI session file and returns a {@link JuniePhaseCapture}: the agent's final result
 * and patch, its tool steps, its thinking, its token usage and cost, and why it stopped. Use it
 * after a Junie session has ended: pass the {@code events.jsonl} file that Junie keeps under
 * {@code ~/.junie/sessions/<sessionId>/}, or a reader over its lines, to a {@code parse} method.
 * To store the result as a journal run, pass the capture to a {@link JunieRunRecorder}.
 *
 * <p>Like the Codex parser, and unlike Claude Code's {@code SessionLogParser}, it reads the
 * session record that Junie writes for itself, not the output stream of a call, and it writes no
 * trace. It reads the files of plain CLI runs and of runs over the Agent Client Protocol; see
 * {@link JuniePhaseCapture} for what each kind records.
 *
 * <p>Each line is a JSON object. Most lines have the top-level {@code kind}
 * {@code SessionA2uxEvent} and carry the real event kind in {@code event.agentEvent.kind}; a few,
 * such as {@code TaskStartedEvent}, {@code UserPromptEvent} and {@code TaskState}, carry it at the
 * top level. The parser reads both places. It takes the task ID, prompt, launch model and task
 * state from the top-level lines; the usage and cost of each model call from
 * {@code LlmResponseMetadataEvent}; the task name, thinking text, patch, context-window reports
 * and final result from the matching agent events; and the session's total cost and its start and
 * end times from the {@code completion} object that some lines carry. Blank lines and kinds it
 * does not know are skipped.
 *
 * <p>Junie sends a step again each time its status changes, and over the Agent Client Protocol it
 * sends every finished step once more at the end. The parser joins all updates with the same
 * {@code stepId} into one tool record that keeps the latest value of each field. Junie gives steps
 * no tool name, so the record's name is the kind of event that described the step, and its
 * {@link io.github.markpollack.journal.event.ToolKind} follows from it:
 * {@code TerminalBlockUpdatedEvent} is {@code EXECUTE}, {@code ViewFilesBlockUpdatedEvent} is
 * {@code READ}, {@code FileChangesBlockUpdatedEvent} is {@code EDIT}, and
 * {@code ToolBlockUpdatedEvent}, a prose description such as {@code "Open calc.py"}, is
 * {@code OTHER}. When one of the first three describes the same step as the prose, it names the
 * step, and the prose is kept in the tool's input map under {@code description}. A step is an
 * error if its last status is {@code FAILED} or its exit code is above 0.
 *
 * <p>A Junie session file also records the agent's environment variables, unredacted, which can
 * include API keys. The parser never copies them into the capture, so they do not reach the
 * journal, but treat the session file itself as secret.
 *
 * <p>All methods are static and keep no state between calls. Calls from several threads are safe
 * if each has its own input.
 */
public final class JunieSessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JunieSessionParser() {
    }

    /**
     * Parses a session file, read as UTF-8.
     *
     * @param eventsFile the session's {@code events.jsonl} file
     * @param phaseName the caller's name for this session, such as {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured; a prompt
     *        recorded in the file is used instead when there is one
     * @return the capture, never {@code null}
     * @throws IOException if the file cannot be read or a line is not valid JSON
     */
    public static JuniePhaseCapture parse(Path eventsFile, String phaseName, String promptText) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(eventsFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

    /**
     * Parses session lines from a reader, reading it to the end. The reader is not closed.
     *
     * @param reader the lines of a session's {@code events.jsonl} file
     * @param phaseName the caller's name for this session, such as {@code "execute"}
     * @param promptText the prompt that was sent, or {@code null} if not captured; a prompt
     *        recorded in the lines is used instead when there is one
     * @return the capture, never {@code null}
     * @throws IOException if reading fails, or if a line is not valid JSON; the message gives the
     *         line number
     */
    public static JuniePhaseCapture parse(BufferedReader reader, String phaseName, String promptText)
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
                throw new IOException("Invalid Junie events JSONL at line " + lineNumber, ex);
            }
        }
        return state.capture(phaseName, promptText);
    }

    private static final class ParserState {

        private final Map<String, MutableStep> steps = new LinkedHashMap<>();
        private final List<JunieModelCost> modelCosts = new ArrayList<>();
        private final List<String> thinkingBlocks = new ArrayList<>();

        private String tracePrompt;
        private String launchModel;
        private String taskId;
        private String taskName;
        private String taskState;
        private String errorCode;
        private String textOutput;
        private String patch;
        private boolean cancelled;
        private boolean sawResult;

        private long startedAtMs = -1L;
        private long taskStartedMs = -1L;
        private long endedAtMs = -1L;
        private double totalCostUsd;
        private boolean sawCompletion;
        private long contextWindowUsed = -1L;
        private long contextWindowSize = -1L;

        void accept(JsonNode line) {
            // The completion block rides alongside the event rather than inside it, and the ACP
            // path emits it more than once with identical values; last one wins.
            JsonNode completion = line.path("completion");
            if (completion.isObject()) {
                sawCompletion = true;
                startedAtMs = longOr(completion, "startedAtMs", startedAtMs);
                endedAtMs = longOr(completion, "endedAtMs", endedAtMs);
                if (completion.hasNonNull("taskCostUsd")) {
                    totalCostUsd = completion.path("taskCostUsd").asDouble(0.0);
                }
            }

            String topLevelKind = text(line, "kind");
            if (topLevelKind == null) {
                topLevelKind = "";
            }
            switch (topLevelKind) {
                case "TaskStartedEvent" -> {
                    taskId = firstNonBlank(text(line, "taskId"), taskId);
                    taskStartedMs = longOr(line, "timestampMs", taskStartedMs);
                    return;
                }
                case "UserPromptEvent" -> {
                    tracePrompt = firstNonBlank(text(line, "prompt"), tracePrompt);
                    launchModel = firstNonBlank(launchModelFrom(line), launchModel);
                    return;
                }
                case "TaskState" -> {
                    taskState = firstNonBlank(text(line, "state"), taskState);
                    return;
                }
                default -> {
                    // SessionA2uxEvent and anything else: the type is one level deeper.
                }
            }

            JsonNode agentEvent = line.path("event").path("agentEvent");
            if (agentEvent.isObject()) {
                acceptAgentEvent(agentEvent);
            }
        }

        private void acceptAgentEvent(JsonNode agentEvent) {
            String kind = text(agentEvent, "kind");
            if (kind == null) {
                return;
            }
            switch (kind) {
                case "LlmResponseMetadataEvent" -> acceptModelUsage(agentEvent.path("modelUsage"));
                case "AgentTaskNameUpdatedEvent" -> taskName = firstNonBlank(text(agentEvent, "name"), taskName);
                case "AgentThoughtBlockUpdatedEvent" -> acceptThought(agentEvent);
                case "AgentPatchCreatedEvent" -> patch = firstNonBlank(text(agentEvent, "patch"), patch);
                case "ContextWindowReportEvent" -> {
                    contextWindowUsed = longOr(agentEvent, "used", contextWindowUsed);
                    contextWindowSize = longOr(agentEvent, "size", contextWindowSize);
                }
                case "ResultBlockUpdatedEvent" -> acceptResult(agentEvent);
                case JunieToolClassifier.TERMINAL_BLOCK,
                     JunieToolClassifier.VIEW_FILES_BLOCK,
                     JunieToolClassifier.FILE_CHANGES_BLOCK,
                     JunieToolClassifier.TOOL_BLOCK -> acceptStep(kind, agentEvent);
                default -> {
                    // Status narration, cwd, environment (deliberately dropped — see class note),
                    // plans, tips, history commits, and any future kind.
                }
            }
        }

        private void acceptModelUsage(JsonNode modelUsage) {
            if (!modelUsage.isArray()) {
                return;
            }
            for (JsonNode entry : modelUsage) {
                modelCosts.add(new JunieModelCost(
                        text(entry, "model"),
                        entry.path("cost").asDouble(0.0),
                        entry.path("inputTokens").asLong(0L),
                        entry.path("cacheInputTokens").asLong(0L),
                        entry.path("cacheCreateTokens").asLong(0L),
                        entry.path("outputTokens").asLong(0L)));
            }
        }

        private void acceptThought(JsonNode agentEvent) {
            String thought = text(agentEvent, "text");
            if (thought != null) {
                thinkingBlocks.add(thought);
            }
        }

        private void acceptResult(JsonNode agentEvent) {
            sawResult = true;
            textOutput = firstNonBlank(text(agentEvent, "result"), textOutput);
            errorCode = firstNonBlank(text(agentEvent, "errorCode"), errorCode);
            if (agentEvent.path("cancelled").isBoolean()) {
                cancelled = agentEvent.path("cancelled").asBoolean();
            }
        }

        private void acceptStep(String kind, JsonNode agentEvent) {
            String stepId = text(agentEvent, "stepId");
            if (stepId == null) {
                return;
            }
            MutableStep step = steps.computeIfAbsent(stepId, MutableStep::new);

            // A structured kind outranks the prose ToolBlockUpdatedEvent for the same step,
            // whichever arrived last: "Open calc.py" and files:[calc.py] are one read, and the
            // structured half is the one that names the action. A step seen only as prose keeps
            // ToolBlockUpdatedEvent rather than being dropped.
            if (step.name == null || JunieToolClassifier.isStructured(kind)) {
                step.name = kind;
            }

            String status = text(agentEvent, "status");
            if (status != null) {
                step.status = status;
            }
            if (agentEvent.hasNonNull("exitCode")) {
                step.exitCode = agentEvent.path("exitCode").asInt(-1);
            }

            switch (kind) {
                case JunieToolClassifier.TOOL_BLOCK -> putIfPresent(step.input, "description", text(agentEvent, "text"));
                case JunieToolClassifier.TERMINAL_BLOCK -> {
                    putIfPresent(step.input, "command", text(agentEvent, "command"));
                    String output = text(agentEvent, "output");
                    if (output != null) {
                        step.output = output;
                    }
                    if (agentEvent.hasNonNull("outputLinesCount")) {
                        step.input.put("outputLinesCount", agentEvent.path("outputLinesCount").asInt(0));
                    }
                }
                case JunieToolClassifier.VIEW_FILES_BLOCK -> {
                    List<String> files = new ArrayList<>();
                    for (JsonNode file : agentEvent.path("files")) {
                        String relativePath = text(file, "relativePath");
                        if (relativePath != null) {
                            files.add(relativePath);
                        }
                    }
                    if (!files.isEmpty()) {
                        step.input.put("files", List.copyOf(files));
                    }
                }
                case JunieToolClassifier.FILE_CHANGES_BLOCK -> {
                    List<String> paths = new ArrayList<>();
                    for (JsonNode change : agentEvent.path("changes")) {
                        String after = firstNonBlank(text(change, "afterRelativePath"),
                                text(change, "beforeRelativePath"));
                        if (after != null) {
                            paths.add(after);
                        }
                    }
                    if (!paths.isEmpty()) {
                        step.input.put("paths", List.copyOf(paths));
                        step.output = "changed " + paths.size() + " file(s)";
                    }
                }
                default -> {
                    // unreachable: acceptAgentEvent only routes the four block kinds here
                }
            }
        }

        JuniePhaseCapture capture(String phaseName, String promptText) {
            List<JunieToolUseRecord> toolUses = new ArrayList<>(steps.size());
            for (MutableStep step : steps.values()) {
                toolUses.add(step.freeze());
            }

            int inputTokens = 0;
            int outputTokens = 0;
            int cacheReadTokens = 0;
            int cacheCreationTokens = 0;
            for (JunieModelCost cost : modelCosts) {
                inputTokens += (int) cost.inputTokens();
                outputTokens += (int) cost.outputTokens();
                cacheReadTokens += (int) cost.cacheInputTokens();
                cacheCreationTokens += (int) cost.cacheCreateTokens();
            }

            long start = startedAtMs >= 0 ? startedAtMs : taskStartedMs;
            long durationMs = (endedAtMs >= 0 && start >= 0 && endedAtMs >= start) ? endedAtMs - start : 0L;

            StopReason stopReason = JunieStopReasons.from(taskState, errorCode, cancelled, sawResult);
            // A session with no completion block never finished being written; that is an
            // incomplete capture, not a successful run.
            boolean isError = cancelled || stopReason == StopReason.ERROR
                    || (!sawCompletion && !sawResult);

            return new JuniePhaseCapture(
                    phaseName,
                    firstNonBlank(tracePrompt, promptText),
                    firstNonBlank(launchModel, dominantModel()),
                    taskId,
                    taskName,
                    inputTokens,
                    outputTokens,
                    cacheReadTokens,
                    cacheCreationTokens,
                    totalCostUsd,
                    durationMs,
                    modelCosts.size(),
                    isError,
                    taskState,
                    errorCode,
                    cancelled,
                    textOutput,
                    patch,
                    thinkingBlocks,
                    contextWindowUsed,
                    contextWindowSize,
                    stopReason,
                    -1,
                    modelCosts,
                    toolUses);
        }

        /**
         * The model that carried the most cost. Junie fans a task across a main model and internal
         * helper models, so "most expensive" is a better guess at the model that did the work than
         * "first seen" — used only when the trace never states the launch model.
         */
        private String dominantModel() {
            Map<String, Double> byModel = new LinkedHashMap<>();
            for (JunieModelCost cost : modelCosts) {
                if (cost.model() != null) {
                    byModel.merge(cost.model(), cost.costUsd(), Double::sum);
                }
            }
            return byModel.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        private static String launchModelFrom(JsonNode line) {
            for (JsonNode attachment : line.path("customAttachments")) {
                String modelId = text(attachment, "modelId");
                if (modelId != null) {
                    return modelId;
                }
            }
            return null;
        }
    }

    private static final class MutableStep {

        private final String id;
        private final Map<String, Object> input = new LinkedHashMap<>();
        private String name;
        private String status;
        private Object output;
        private int exitCode = -1;

        MutableStep(String id) {
            this.id = id;
        }

        JunieToolUseRecord freeze() {
            ToolKind kind = JunieToolClassifier.classify(name);
            boolean isError = "FAILED".equalsIgnoreCase(status) || exitCode > 0;
            String errorMessage = null;
            if (isError) {
                errorMessage = output != null ? String.valueOf(output)
                        : "Junie step status: " + status;
            }
            return new JunieToolUseRecord(id, kind, name, input, output, status, exitCode, isError, errorMessage);
        }
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static long longOr(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asLong() : fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
