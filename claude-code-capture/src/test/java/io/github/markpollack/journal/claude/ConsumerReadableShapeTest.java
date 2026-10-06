package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The files of a recorded run, read the way a consumer written in another language reads them:
 * as JSON lines, by key name, with no Java type in between. An analysis pipeline that reads
 * {@code events.jsonl}, {@code analysis.jsonl} and {@code run.json} depends on these names and
 * on their nesting. A release may add keys; this test fails if one that such a reader uses is
 * renamed, moved or dropped, or if a tool call's identity or input changes.
 *
 * <p>The key sets below are those of the files written since 1.6.0. The capture is synthetic.
 */
@DisplayName("Run files keep the shapes a JSON consumer reads")
class ConsumerReadableShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> TOOL_CALL_KEYS = Set.of("@type", "timestamp", "id", "toolName", "input",
            "output", "durationMs", "success", "errorMessage");
    private static final Set<String> LLM_CALL_KEYS = Set.of("@type", "timestamp", "provider", "model", "tokenUsage",
            "cost", "timing", "finishReason", "responseId", "metadata");
    private static final Set<String> TOKEN_USAGE_KEYS = Set.of("inputTokens", "outputTokens", "thinkingTokens",
            "cacheCreationTokens", "cacheReadTokens", "toolUseTokens");
    private static final Set<String> COST_KEYS = Set.of("inputCostUsd", "outputCostUsd", "thinkingCostUsd",
            "cacheSavingsUsd");
    private static final Set<String> TIMING_KEYS = Set.of("totalDurationMs", "apiDurationMs", "timeToFirstTokenMs");
    private static final Set<String> STEP_COST_KEYS = Set.of("@type", "timestamp", "runId", "stepId", "turnId",
            "toolName", "inputTokens", "outputTokens", "attributedCostUsd", "actualRunCostUsd", "attributionMethod",
            "vendor");
    private static final Set<String> RUN_KEYS = Set.of("id", "experimentId", "name", "status", "config", "summary",
            "tags", "agentId", "previousRunId", "parentRunId", "startTime", "endTime", "errorMessage", "errorType");

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    /** Three turns: two Read calls in one message, a Bash call that fails, then a closing answer. */
    private static PhaseCapture capture() {
        List<TurnUsage> turns = List.of(
                new TurnUsage("msg_1", "claude-synthetic-1", 1200, 100, 50, 8000, List.of("toolu_01", "toolu_02")),
                new TurnUsage("msg_2", "claude-synthetic-1", 1500, 300, 0, 9200, List.of("toolu_03")),
                new TurnUsage("msg_3", "claude-synthetic-1", 1700, 50, 0, 9800, List.of()));
        List<ToolUseRecord> tools = List.of(
                new ToolUseRecord("toolu_01", "Read", Map.of("file_path", "/work/pom.xml")),
                new ToolUseRecord("toolu_02", "Read", Map.of("file_path", "/work/README.md")),
                new ToolUseRecord("toolu_03", "Bash", Map.of("command", "./mvnw -q test", "timeout", 120000)));
        List<ToolResultRecord> results = List.of(
                new ToolResultRecord("toolu_01", "<project/>", false, 12L),
                new ToolResultRecord("toolu_02", "# Read me", false, 9L),
                new ToolResultRecord("toolu_03", "BUILD FAILURE", true, 4100L));
        return new PhaseCapture("explore", "Investigate the failing build.", 4400, 450, 0, 50, 27000, 4200L, 3100L,
                0.094841, "sess-synthetic", 3, false, "The build fails in one test.", List.of(), tools,
                "The build fails in one test.", results, turns, List.of());
    }

    private static List<JsonNode> lines(Path file) throws Exception {
        List<JsonNode> nodes = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.startsWith("{")) {
                nodes.add(JSON.readTree(line));
            }
        }
        return nodes;
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private static List<JsonNode> ofType(List<JsonNode> nodes, String type) {
        return nodes.stream().filter(n -> type.equals(n.path("@type").asText())).toList();
    }

    @Test
    @DisplayName("events.jsonl, analysis.jsonl and run.json keep every key name and its nesting")
    void keyNamesAndNestingAreKept(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").config("model", "claude-synthetic-1").config("itemId", "item-7")
                .config("variant", "baseline").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(capture());
            recorder.finish();
        }
        Path runDir = dir.resolve("experiments/exp/runs/" + run.id());

        // ---- events.jsonl
        List<JsonNode> events = lines(runDir.resolve("events.jsonl"));
        JsonNode header = events.get(0);
        assertThat(header.path("@type").asText()).isEqualTo("header");
        assertThat(header.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(header.path("stream").asText()).isEqualTo("events");
        assertThat(header.path("runId").asText()).isEqualTo(run.id());

        // File order is execution order, and a tool call's identity and input are what the agent sent.
        List<JsonNode> tools = ofType(events, "tool_call");
        assertThat(tools).extracting(n -> n.path("id").asText()).containsExactly("toolu_01", "toolu_02", "toolu_03");
        assertThat(tools).extracting(n -> n.path("toolName").asText()).containsExactly("Read", "Read", "Bash");
        assertThat(tools).allSatisfy(tool -> assertThat(keys(tool)).containsAll(TOOL_CALL_KEYS));
        assertThat(tools.get(0).path("input")).isEqualTo(JSON.readTree("{\"file_path\":\"/work/pom.xml\"}"));
        assertThat(tools.get(2).path("input"))
                .isEqualTo(JSON.readTree("{\"command\":\"./mvnw -q test\",\"timeout\":120000}"));
        assertThat(tools.get(0).path("success").asBoolean()).isTrue();
        assertThat(tools.get(2).path("success").asBoolean()).isFalse();
        assertThat(tools.get(2).path("errorMessage").asText()).isEqualTo("BUILD FAILURE");

        JsonNode llm = ofType(events, "llm_call").get(0);
        assertThat(ofType(events, "llm_call")).hasSize(1);
        assertThat(keys(llm)).containsAll(LLM_CALL_KEYS);
        assertThat(keys(llm.path("tokenUsage"))).isEqualTo(new TreeSet<>(TOKEN_USAGE_KEYS));
        assertThat(keys(llm.path("cost"))).isEqualTo(new TreeSet<>(COST_KEYS));
        assertThat(keys(llm.path("timing"))).isEqualTo(new TreeSet<>(TIMING_KEYS));
        // Numbers a reader converts without a null check.
        assertThat(llm.path("tokenUsage").path("inputTokens").isIntegralNumber()).isTrue();
        assertThat(llm.path("tokenUsage").path("outputTokens").isIntegralNumber()).isTrue();
        assertThat(llm.path("tokenUsage").path("inputTokens").asInt()).isEqualTo(4400);
        assertThat(llm.path("tokenUsage").path("outputTokens").asInt()).isEqualTo(450);
        assertThat(llm.path("timing").path("totalDurationMs").asLong()).isEqualTo(4200L);
        assertThat(llm.path("cost").path("inputCostUsd").isNumber()).isTrue();
        assertThat(llm.path("cost").path("inputCostUsd").asDouble()).isEqualTo(0.094841);
        assertThat(llm.path("metadata").path("isError").isBoolean()).isTrue();
        assertThat(llm.path("metadata").path("turns")).hasSize(3);
        // A call without sub-agents has no sub-agent key.
        assertThat(keys(llm.path("metadata"))).noneMatch(key -> key.toLowerCase().contains("subagent"));

        // ---- analysis.jsonl
        List<JsonNode> analysis = lines(runDir.resolve("analysis.jsonl"));
        assertThat(analysis.get(0).path("@type").asText()).isEqualTo("header");
        assertThat(analysis.get(0).path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(analysis.get(0).path("stream").asText()).isEqualTo("analysis");
        List<JsonNode> steps = ofType(analysis, "step_cost");
        assertThat(steps).allSatisfy(step -> assertThat(keys(step)).containsAll(STEP_COST_KEYS));
        // One step per tool call, joined by the tool call's ID, and one for the closing answer.
        assertThat(steps).extracting(n -> n.path("stepId").asText())
                .containsExactly("toolu_01", "toolu_02", "toolu_03", "msg_3");
        assertThat(steps).allSatisfy(step -> {
            assertThat(step.path("runId").asText()).isEqualTo(run.id());
            assertThat(step.path("attributionMethod").asText()).isEqualTo("OUTPUT_TOKEN_PROPORTIONAL");
            assertThat(step.path("attributedCostUsd").isNumber()).isTrue();
            assertThat(step.path("actualRunCostUsd").asDouble()).isEqualTo(0.094841);
            assertThat(step.path("vendor").asText()).isEqualTo("claude-code");
        });
        assertThat(steps.get(3).path("toolName").isNull()).isTrue();
        assertThat(steps.stream().mapToDouble(s -> s.path("attributedCostUsd").asDouble()).sum())
                .isEqualTo(0.094841);

        // ---- run.json
        JsonNode runJson = JSON.readTree(runDir.resolve("run.json").toFile());
        assertThat(keys(runJson)).isEqualTo(new TreeSet<>(RUN_KEYS));
        assertThat(runJson.path("status").asText()).isEqualTo("FINISHED");
        assertThat(runJson.path("summary").path("success").asBoolean()).isTrue();
        assertThat(runJson.path("errorMessage").isNull()).isTrue();
        assertThat(runJson.path("parentRunId").isNull()).isTrue();
        assertThat(runJson.path("config").path("itemId").asText()).isEqualTo("item-7");
        assertThat(runJson.path("config").path("variant").asText()).isEqualTo("baseline");
        assertThat(runJson.path("tags").isObject()).isTrue();
    }

    @Test
    @DisplayName("every run of a call with sub-agents is readable the same way, and a sub-agent's run says it is one")
    void subagentRunsHaveTheSameShapes(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").config("model", "claude-synthetic-1").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(SubagentCaptureTest.parseFixture());
            recorder.finish();
        }

        List<Path> runDirs;
        try (Stream<Path> dirs = Files.list(dir.resolve("experiments/exp/runs"))) {
            runDirs = dirs.toList();
        }
        assertThat(runDirs).hasSize(5);
        int subagentRuns = 0;
        for (Path runDir : runDirs) {
            List<JsonNode> events = lines(runDir.resolve("events.jsonl"));
            assertThat(events.get(0).path("@type").asText()).isEqualTo("header");
            List<JsonNode> tools = ofType(events, "tool_call");
            assertThat(tools).allSatisfy(tool -> assertThat(keys(tool)).containsAll(TOOL_CALL_KEYS));
            // A reader that requires a cost record for each tool call finds one in every run.
            List<String> stepIds = ofType(lines(runDir.resolve("analysis.jsonl")), "step_cost").stream()
                    .map(step -> step.path("stepId").asText()).toList();
            assertThat(stepIds).containsAll(tools.stream().map(tool -> tool.path("id").asText()).toList());
            assertThat(ofType(lines(runDir.resolve("analysis.jsonl")), "step_cost"))
                    .allSatisfy(step -> assertThat(step.path("attributionMethod").isTextual()).isTrue());

            JsonNode llm = ofType(events, "llm_call").get(0);
            assertThat(keys(llm.path("tokenUsage"))).isEqualTo(new TreeSet<>(TOKEN_USAGE_KEYS));
            assertThat(llm.path("timing").path("totalDurationMs").asLong()).isNotNegative();
            assertThat(llm.path("cost").path("inputCostUsd").isNumber()).isTrue();

            JsonNode runJson = JSON.readTree(runDir.resolve("run.json").toFile());
            assertThat(keys(runJson)).isEqualTo(new TreeSet<>(RUN_KEYS));
            if (runJson.path("id").asText().equals(run.id())) {
                assertThat(runJson.path("parentRunId").isNull()).isTrue();
                assertThat(runJson.path("tags").has("track")).isFalse();
            } else {
                // The two marks a reader can use to skip or group sub-agent runs.
                subagentRuns++;
                assertThat(runJson.path("parentRunId").isTextual()).isTrue();
                assertThat(runJson.path("tags").path("track").asText()).isEqualTo("subagent");
                assertThat(llm.path("metadata").path("costAvailable").asBoolean()).isFalse();
            }
        }
        assertThat(subagentRuns).isEqualTo(4);
    }
}
