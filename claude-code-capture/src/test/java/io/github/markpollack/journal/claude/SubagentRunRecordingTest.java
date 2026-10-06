package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.trace.AttributionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A recorded call with sub-agents is one run for the main loop and one run per sub-agent, each
 * sub-agent's run linked to the run of the agent that started it. Everything is read back
 * through a fresh storage instance. The session is the hand-written
 * {@code fixtures/claude-subagents-synthetic.jsonl}.
 */
@DisplayName("Recording sub-agents as linked runs")
class SubagentRunRecordingTest {

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    /** Records the fixture and returns the main loop's run ID. */
    private static String record(Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        try (RunRecorder recorder = new RunRecorder(Journal.run("exp").config("model", "claude-synthetic-1").start())) {
            recorder.recordPhase(SubagentCaptureTest.parseFixture());
            recorder.finish();
            return recorder.run().id();
        }
    }

    private static RunData subagentRun(JsonFileStorage storage, String spawnToolUseId) {
        return storage.listRuns("exp").stream()
                .filter(run -> spawnToolUseId.equals(run.config().values().get("subagent.spawnToolUseId")))
                .findFirst().orElseThrow();
    }

    private static LLMCallEvent llmCall(List<JournalEvent> events) {
        return events.stream().filter(LLMCallEvent.class::isInstance).map(LLMCallEvent.class::cast)
                .findFirst().orElseThrow();
    }

    private static List<ToolCallEvent> toolCalls(List<JournalEvent> events) {
        return events.stream().filter(ToolCallEvent.class::isInstance).map(ToolCallEvent.class::cast).toList();
    }

    @Test
    @DisplayName("the main loop's run holds its own tool calls only, each with a step cost")
    void mainRunHoldsOnlyTheMainLoop(@TempDir Path dir) throws Exception {
        String mainRunId = record(dir);
        JsonFileStorage storage = new JsonFileStorage(dir);

        List<JournalEvent> events = storage.loadEvents("exp", mainRunId);
        assertThat(toolCalls(events)).extracting(ToolCallEvent::id).containsExactly(
                "toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_refused", "toolu_spawn_d");
        assertThat(toolCalls(events)).extracting(ToolCallEvent::toolName).containsOnly("Agent");
        // No sub-agent tool call is anywhere in the main loop's files. The nested spawn is named
        // in the LLM call's list of sub-agents, but is not one of the main loop's steps.
        String eventsFile = Files.readString(dir.resolve("experiments/exp/runs/" + mainRunId + "/events.jsonl"));
        String analysisFile = Files.readString(dir.resolve("experiments/exp/runs/" + mainRunId + "/analysis.jsonl"));
        assertThat(eventsFile).doesNotContain("toolu_a_bash", "toolu_c_bash", "toolu_d_read");
        assertThat(analysisFile).doesNotContain("toolu_a_bash", "toolu_c_bash", "toolu_d_read", "toolu_spawn_c");

        List<StepCostEvent> steps = storage.loadDerivedEvents("exp", mainRunId).stream()
                .map(StepCostEvent.class::cast).toList();
        assertThat(steps).extracting(StepCostEvent::stepId).containsExactly(
                "toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_refused", "toolu_spawn_d", "msg_main_3");
        // The call's cost, sub-agents' included, is all on the main loop's steps.
        assertThat(steps.stream().mapToDouble(StepCostEvent::attributedCostUsd).sum()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the main loop's LLM call counts its own tokens once and names the sub-agent runs")
    void mainLlmCallNamesTheSubagents(@TempDir Path dir) throws Exception {
        String mainRunId = record(dir);
        JsonFileStorage storage = new JsonFileStorage(dir);

        LLMCallEvent llm = llmCall(storage.loadEvents("exp", mainRunId));
        assertThat(llm.tokenUsage().inputTokens()).isEqualTo(36);
        assertThat(llm.tokenUsage().cacheCreationTokens()).isEqualTo(1300);
        assertThat(llm.tokenUsage().cacheReadTokens()).isEqualTo(2200);
        // The result message's output count, not the sum of the per-message starting counts (68).
        assertThat(llm.tokenUsage().outputTokens()).isEqualTo(310);
        assertThat(llm.totalCostUsd()).isEqualTo(0.5);

        Map<String, Object> metadata = llm.metadata();
        assertThat(metadata).containsEntry(BaseRunRecorder.META_SUBAGENT_TRACKS_AVAILABLE, true)
                .containsEntry(BaseRunRecorder.META_COST_INCLUDES_SUBAGENTS, true)
                .containsEntry(BaseRunRecorder.META_SUBAGENTS_WITHOUT_TRACK, List.of("toolu_spawn_refused"));
        assertThat(metadata.get(BaseRunRecorder.META_SUBAGENT_STATS)).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("spawned", 4);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subagents = (List<Map<String, Object>>) metadata.get(BaseRunRecorder.META_SUBAGENTS);
        assertThat(subagents).extracting(entry -> entry.get("spawnToolUseId")).containsExactly(
                "toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_c", "toolu_spawn_d");
        assertThat(subagents).extracting(entry -> entry.get("status"))
                .containsExactly("completed", "completed", "failed", "unknown");
        assertThat(subagents).extracting(entry -> entry.get("depth")).containsExactly(1, 1, 2, 1);
        for (Map<String, Object> entry : subagents) {
            RunData run = storage.loadRun("exp", (String) entry.get("runId")).orElseThrow();
            assertThat(run.config().values()).containsEntry("subagent.spawnToolUseId", entry.get("spawnToolUseId"));
        }
    }

    @Test
    @DisplayName("each sub-agent's run is linked to the run of the agent that started it")
    void subagentRunsAreLinked(@TempDir Path dir) throws Exception {
        String mainRunId = record(dir);
        JsonFileStorage storage = new JsonFileStorage(dir);

        assertThat(storage.listRuns("exp")).hasSize(5);
        RunData a = subagentRun(storage, "toolu_spawn_a");
        RunData b = subagentRun(storage, "toolu_spawn_b");
        RunData c = subagentRun(storage, "toolu_spawn_c");
        RunData d = subagentRun(storage, "toolu_spawn_d");

        assertThat(a.parentRunId()).isEqualTo(mainRunId);
        assertThat(b.parentRunId()).isEqualTo(mainRunId);
        assertThat(d.parentRunId()).isEqualTo(mainRunId);
        // The nested sub-agent hangs under the sub-agent that started it.
        assertThat(c.parentRunId()).isEqualTo(b.id());
        assertThat(storage.loadRun("exp", mainRunId).orElseThrow().parentRunId()).isNull();

        assertThat(a.agentId()).isEqualTo("agent-a");
        assertThat(a.tags().get("track")).isEqualTo("subagent");
        assertThat(a.config().values())
                .containsEntry("subagent.depth", 1)
                .containsEntry("subagent.type", "general-purpose")
                .containsEntry("subagent.description", "List changed files")
                .containsEntry("subagent.sessionId", "sess-synthetic-0001")
                .containsEntry("model", "claude-synthetic-1");
        assertThat(c.config().values()).containsEntry("subagent.depth", 2);
    }

    @Test
    @DisplayName("a sub-agent's run ends as its reported status says, and as CRASHED when nothing says")
    void subagentRunStatusFollowsTheReport(@TempDir Path dir) throws Exception {
        record(dir);
        JsonFileStorage storage = new JsonFileStorage(dir);

        RunData a = subagentRun(storage, "toolu_spawn_a");
        assertThat(a.status()).isEqualTo(RunStatus.FINISHED);
        assertThat(a.summary().values()).containsEntry("success", true)
                .containsEntry("subagent.status", "completed")
                .containsEntry("subagent.statusSource", "task_notification")
                .containsEntry("subagent.reportedTotalTokens", 1085)
                .containsEntry("subagent.reportedToolUses", 1)
                .containsEntry("subagent.reportedDurationMs", 1500);

        RunData b = subagentRun(storage, "toolu_spawn_b");
        assertThat(b.status()).isEqualTo(RunStatus.FINISHED);
        assertThat(b.summary().values()).containsEntry("subagent.statusSource", "tool_use_result");

        RunData c = subagentRun(storage, "toolu_spawn_c");
        assertThat(c.status()).isEqualTo(RunStatus.FAILED);
        assertThat(c.summary().values()).containsEntry("success", false).containsEntry("subagent.status", "failed");

        // Messages arrived, but no end was reported: not a success, and the summary says why.
        RunData d = subagentRun(storage, "toolu_spawn_d");
        assertThat(d.status()).isEqualTo(RunStatus.CRASHED);
        assertThat(d.summary().values()).containsEntry("success", false)
                .containsEntry("subagent.status", "unknown")
                .containsEntry("subagent.statusSource", "none")
                .doesNotContainKey("subagent.reportedTotalTokens");
        assertThat(d.endTime()).isNotNull();
    }

    @Test
    @DisplayName("a sub-agent's run holds its prompt, tokens, tool calls and thinking, with no cost of its own")
    void subagentRunHoldsItsActivity(@TempDir Path dir) throws Exception {
        record(dir);
        JsonFileStorage storage = new JsonFileStorage(dir);

        RunData a = subagentRun(storage, "toolu_spawn_a");
        List<JournalEvent> events = storage.loadEvents("exp", a.id());
        assertThat(events).filteredOn(e -> e instanceof CustomEvent custom && custom.name().equals("prompt"))
                .singleElement()
                .satisfies(e -> assertThat(((CustomEvent) e).attributes())
                        .containsEntry("text", "List the files the change touches."));

        LLMCallEvent llm = llmCall(events);
        assertThat(llm.model()).isEqualTo("claude-synthetic-1");
        assertThat(llm.tokenUsage().inputTokens()).isEqualTo(11);
        assertThat(llm.tokenUsage().cacheCreationTokens()).isEqualTo(550);
        assertThat(llm.tokenUsage().cacheReadTokens()).isEqualTo(500);
        assertThat(llm.totalCostUsd()).isZero();
        assertThat(llm.metadata()).containsEntry("costAvailable", false)
                .containsEntry("costSource", "included_in_parent")
                .containsEntry("isError", false)
                .doesNotContainKey(BaseRunRecorder.META_SUBAGENTS);

        assertThat(toolCalls(events)).singleElement().satisfies(tool -> {
            assertThat(tool.id()).isEqualTo("toolu_a_bash");
            assertThat(tool.toolName()).isEqualTo("Bash");
            assertThat(tool.input()).containsEntry("command", "git diff --name-only");
            assertThat(tool.turnId()).isEqualTo("msg_a_1");
            assertThat(tool.turnIndex()).isZero();
            assertThat(tool.success()).isTrue();
        });

        List<DerivedEvent> derived = storage.loadDerivedEvents("exp", a.id());
        assertThat(derived).singleElement().satisfies(event -> {
            StepCostEvent step = (StepCostEvent) event;
            assertThat(step.stepId()).isEqualTo("toolu_a_bash");
            assertThat(step.runId()).isEqualTo(a.id());
            assertThat(step.attributedCostUsd()).isZero();
            assertThat(step.actualRunCostUsd()).isZero();
            assertThat(step.attributionMethod()).isEqualTo(AttributionMethod.EVEN_SPLIT);
            assertThat(step.inputTokens()).isEqualTo(5);
            assertThat(step.cacheCreationTokens()).isEqualTo(500);
        });

        // A failed tool call inside a sub-agent keeps its error text.
        RunData c = subagentRun(storage, "toolu_spawn_c");
        assertThat(toolCalls(storage.loadEvents("exp", c.id()))).singleElement().satisfies(tool -> {
            assertThat(tool.success()).isFalse();
            assertThat(tool.errorMessage()).isEqualTo("Tests run: 3, Failures: 1");
        });
        assertThat(llmCall(storage.loadEvents("exp", c.id())).metadata()).containsEntry("isError", true);

        // Sub-agent b's thinking is in b's run, and its own spawn is one of b's tool calls.
        RunData b = subagentRun(storage, "toolu_spawn_b");
        List<JournalEvent> bEvents = storage.loadEvents("exp", b.id());
        assertThat(bEvents).filteredOn(e -> e instanceof CustomEvent custom && custom.name().equals("thinking_block"))
                .singleElement()
                .satisfies(e -> assertThat(((CustomEvent) e).attributes())
                        .containsEntry("content", "The tests are the slow part."));
        assertThat(toolCalls(bEvents)).extracting(ToolCallEvent::id).containsExactly("toolu_spawn_c");
    }

    @Test
    @DisplayName("over all runs, tokens add up to the reported totals and cost to the reported cost, once")
    void totalsOverAllRunsAreCountedOnce(@TempDir Path dir) throws Exception {
        record(dir);
        JsonFileStorage storage = new JsonFileStorage(dir);

        int input = 0;
        int cacheCreation = 0;
        int cacheRead = 0;
        double cost = 0;
        for (RunData run : storage.listRuns("exp")) {
            LLMCallEvent llm = llmCall(storage.loadEvents("exp", run.id()));
            input += llm.tokenUsage().inputTokens();
            cacheCreation += llm.tokenUsage().cacheCreationTokens();
            cacheRead += llm.tokenUsage().cacheReadTokens();
            cost += llm.totalCostUsd();
        }

        assertThat(input).isEqualTo(71);
        assertThat(cacheCreation).isEqualTo(3250);
        assertThat(cacheRead).isEqualTo(3700);
        assertThat(cost).isEqualTo(0.5);
    }

    @Test
    @DisplayName("a call without sub-agents is recorded without any sub-agent key, and as one run")
    void aCallWithoutSubagentsIsUnchanged(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));
        TurnUsage turn = new TurnUsage("msg_1", "claude-synthetic-1", 100, 200, 0, 0, List.of("toolu_1"));
        PhaseCapture capture = new PhaseCapture("explore", "do the thing", 100, 200, 0, 0, 0, 1200L, 1000L, 0.10,
                "session-1", 1, false, "done", List.of(),
                List.of(new ToolUseRecord("toolu_1", "Bash", Map.of("command", "ls"))), "done",
                List.of(), List.of(turn), List.of());
        Run run = Journal.run("exp").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(capture);
            recorder.finish();
        }

        JsonFileStorage storage = new JsonFileStorage(dir);
        assertThat(storage.listRuns("exp")).hasSize(1);
        assertThat(llmCall(storage.loadEvents("exp", run.id())).metadata()).doesNotContainKeys(
                BaseRunRecorder.META_SUBAGENTS, BaseRunRecorder.META_SUBAGENTS_WITHOUT_TRACK,
                BaseRunRecorder.META_SUBAGENT_STATS, BaseRunRecorder.META_SUBAGENT_TRACKS_AVAILABLE,
                BaseRunRecorder.META_COST_INCLUDES_SUBAGENTS);
    }

    @Test
    @DisplayName("a spawn captured without wire lines is marked as not separated, not as missing")
    void aSpawnWithoutWireLinesIsMarked(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));
        PhaseCapture capture = SessionLogParser.parse(List.<ParsedMessage>of(
                ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(
                        new ToolUseBlock("toolu_spawn_x", "Agent", Map.of("prompt", "p")))))).iterator(),
                "execute", "the prompt");
        Run run = Journal.run("exp").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(capture);
            recorder.finish();
        }

        JsonFileStorage storage = new JsonFileStorage(dir);
        assertThat(storage.listRuns("exp")).hasSize(1);
        assertThat(llmCall(storage.loadEvents("exp", run.id())).metadata())
                .containsEntry(BaseRunRecorder.META_SUBAGENT_TRACKS_AVAILABLE, false)
                .doesNotContainKeys(BaseRunRecorder.META_SUBAGENTS, BaseRunRecorder.META_SUBAGENTS_WITHOUT_TRACK);
    }
}
