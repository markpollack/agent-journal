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
 * Parses Junie's durable {@code ~/.junie/sessions/<sessionId>/events.jsonl} trace.
 *
 * <p>
 * <strong>The discriminator is two levels deeper than the envelope.</strong> Nearly every line has
 * top-level {@code kind = "SessionA2uxEvent"} (238 of 240 in the rich fixture), which says nothing.
 * The real event type is {@code event.agentEvent.kind}. A handful of lines — {@code TaskStartedEvent},
 * {@code UserPromptEvent}, {@code TaskState}, {@code UserMessagesCommittedToHistory} — carry their
 * type at the top level instead, so this parser reads both positions.
 *
 * <p>
 * <strong>Block events are incremental and must be folded.</strong> The same {@code stepId} is
 * re-emitted as its {@code status} progresses, and the ACP path re-emits every terminal state again
 * at the end of the session. Counting lines would report 21 tool events for the 4 real steps of the
 * ACP fixture. Steps are therefore folded by {@code stepId} with last-write-wins on each field, and
 * two different block kinds sharing a {@code stepId} are one step described twice — Junie emits a
 * prose {@code ToolBlockUpdatedEvent} ({@code "Open calc.py"}) alongside the structured
 * {@code ViewFilesBlockUpdatedEvent} for the same read.
 *
 * <p>
 * <strong>Environment variables are read and dropped on the floor, deliberately.</strong> Junie's
 * {@code EnvironmentVariablesUpdatedEvent} carries the agent's entire environment with values
 * unredacted — the captured traces contain live {@code OPENAI_API_KEY}, {@code E2B_API_KEY},
 * {@code ELEVENLABS_API_KEY} and session tokens. Nothing from that event reaches
 * {@link JuniePhaseCapture}, because a capture flows into {@code events.jsonl} and from there into
 * whatever repository holds the run. Do not "improve" this by capturing env for reproducibility.
 *
 * <p>
 * Unknown event kinds are ignored rather than rejected, so a future Junie release that adds a kind
 * still yields a capture.
 */
public final class JunieSessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JunieSessionParser() {
    }

    public static JuniePhaseCapture parse(Path eventsFile, String phaseName, String promptText) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(eventsFile)) {
            return parse(reader, phaseName, promptText);
        }
    }

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
