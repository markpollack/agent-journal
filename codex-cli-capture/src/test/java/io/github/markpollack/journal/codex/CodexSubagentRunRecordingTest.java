package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.trace.AttributionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Records the {@code codex-subagents-synthetic} fixture and reads it back from a fresh storage. */
class CodexSubagentRunRecordingTest {

    @TempDir
    Path dir;
    JsonFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new JsonFileStorage(dir);
        Journal.configure(storage);
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    private Run recordOnce(CodexRollouts rollouts) throws IOException {
        Run parent = Journal.run("exp").start();
        new CodexRunRecorder(parent).recordPhase(CodexSessionParser.parse(rollouts, "phase", "Root prompt"));
        parent.finish(RunStatus.FINISHED);
        return parent;
    }

    private static RunData child(List<RunData> runs, String threadId) {
        return runs.stream().filter(r -> threadId.equals(r.config().values().get("subagent.threadId")))
                .findFirst().orElseThrow();
    }

    @Test
    void childRunsAreWrittenUnderTheAcceptedKeys() throws IOException {
        Run parent = recordOnce(CodexSubagentCaptureTest.allRollouts());

        JsonFileStorage fresh = new JsonFileStorage(dir);
        List<RunData> runs = fresh.listRuns("exp");
        assertThat(runs).hasSize(5);
        List<RunData> children = runs.stream().filter(r -> !r.id().equals(parent.id())).toList();
        assertThat(children).allSatisfy(r -> {
            assertThat(r.parentRunId()).isEqualTo(parent.id());
            assertThat(r.tags().toMap()).containsEntry("track", "subagent");
            assertThat(r.config().values()).containsEntry("subagent.sessionId", CodexSubagentCaptureTest.ROOT)
                    .containsKeys("subagent.threadId", "subagent.depth", "subagent.forked");
        });

        RunData a = child(runs, CodexSubagentCaptureTest.CHILD_A);
        assertThat(a.status()).isEqualTo(RunStatus.FINISHED);
        assertThat(a.agentId()).isEqualTo("workers/checker");
        assertThat(a.config().values()).containsEntry("subagent.spawnToolUseId", "call_spawn_a")
                .containsEntry("subagent.agentPath", "workers/checker")
                .containsEntry("subagent.forked", false).containsEntry("subagent.depth", 1);
        assertThat(a.summary().values()).containsEntry("subagent.status", "completed")
                .containsEntry("subagent.statusSource", "parent_sub_agent_activity_last")
                .containsEntry("subagent.statusMeaning", "last_observed_turn")
                .containsEntry("subagent.replayedRecordsSkipped", 0);
        List<JournalEvent> aEvents = fresh.loadEvents("exp", a.id());
        LLMCallEvent aCall = aEvents.stream().filter(LLMCallEvent.class::isInstance)
                .map(LLMCallEvent.class::cast).findFirst().orElseThrow();
        assertThat(aCall.metadata()).containsEntry("costAvailable", false).containsEntry("costSource", "unreported");
        assertThat(aCall.tokenUsage().inputTokens()).isEqualTo(200);
        assertThat(aCall.timing().totalDurationMs()).isEqualTo(1500L);
        assertThat(aEvents).filteredOn(ToolCallEvent.class::isInstance).extracting(e -> ((ToolCallEvent) e).id())
                .containsExactly("call_a_exec");
        List<StepCostEvent> aSteps = fresh.loadDerivedEvents("exp", a.id()).stream()
                .filter(StepCostEvent.class::isInstance).map(StepCostEvent.class::cast).toList();
        assertThat(aSteps).hasSize(1);
        assertThat(aSteps.get(0).stepId()).isEqualTo("call_a_exec");
        assertThat(aSteps.get(0).attributionMethod()).isEqualTo(AttributionMethod.EVEN_SPLIT);
        assertThat(aSteps.get(0).attributedCostUsd()).isEqualTo(0.0);

        RunData b = child(runs, CodexSubagentCaptureTest.CHILD_B);
        assertThat(b.status()).isEqualTo(RunStatus.FAILED);
        assertThat(b.config().values()).containsEntry("subagent.forked", true);
        assertThat(b.summary().values()).containsEntry("subagent.replayedRecordsSkipped", 3)
                .containsEntry("subagent.statusSource", "parent_sub_agent_activity_last");
        LLMCallEvent bCall = fresh.loadEvents("exp", b.id()).stream().filter(LLMCallEvent.class::isInstance)
                .map(LLMCallEvent.class::cast).findFirst().orElseThrow();
        assertThat(bCall.timing().totalDurationMs()).isEqualTo(900L);
        assertThat(fresh.loadEvents("exp", b.id())).filteredOn(ToolCallEvent.class::isInstance)
                .extracting(e -> ((ToolCallEvent) e).id()).containsExactly("call_b_exec");

        RunData orphan = child(runs, CodexSubagentCaptureTest.ORPHAN);
        assertThat(orphan.status()).isEqualTo(RunStatus.FAILED);
        assertThat(orphan.summary().values()).containsEntry("subagent.statusSource", "child_last_turn");
        assertThat(orphan.config().values()).containsEntry("subagent.depth", -1).doesNotContainKey("subagent.spawnToolUseId");

        RunData c = child(runs, CodexSubagentCaptureTest.CHILD_C);
        assertThat(c.status()).isEqualTo(RunStatus.CRASHED);
        assertThat(c.config().values()).containsEntry("subagent.spawnToolUseId", "call_spawn_c");
        assertThat(c.summary().values()).containsEntry("subagent.status", "unknown")
                .containsEntry("subagent.statusSource", "none")
                .containsEntry("subagent.statusMeaning", "last_observed_turn");

        LLMCallEvent parentCall = fresh.loadEvents("exp", parent.id()).stream()
                .filter(LLMCallEvent.class::isInstance).map(LLMCallEvent.class::cast).findFirst().orElseThrow();
        assertThat(parentCall.tokenUsage().inputTokens()).isEqualTo(700);
        assertThat(parentCall.metadata()).containsEntry("subagentTracksAvailable", true)
                .doesNotContainKey("costIncludesSubagents");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> subagents = (List<Map<String, Object>>) parentCall.metadata().get("subagents");
        assertThat(subagents).hasSize(4);
        assertThat(subagents).extracting(m -> m.get("spawnToolUseId")).containsExactlyInAnyOrder("call_spawn_a", "call_spawn_b", "call_spawn_c", null);
        assertThat(subagents).extracting(m -> m.get("runId")).containsExactlyInAnyOrder(a.id(), b.id(), c.id(), orphan.id());
        assertThat(subagents).extracting(m -> m.get("status")).containsExactlyInAnyOrder("completed", "interrupted", "unknown", "interrupted");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> without = (List<Map<String, Object>>) parentCall.metadata().get("subagentsWithoutTrack");
        assertThat(without).containsExactly(Map.of("callId", "call_spawn_d", "reason", "spawn_failed"));
    }

    @Test
    void repeatRecordingAddsRunsWithTheSameKeysAndChangesNothingInTheFirst() throws IOException {
        CodexRollouts rollouts = CodexSubagentCaptureTest.allRollouts();
        Run first = recordOnce(rollouts);
        List<RunData> afterFirst = new JsonFileStorage(dir).listRuns("exp");
        List<List<JournalEvent>> firstEvents = afterFirst.stream()
                .map(r -> storage.loadEvents("exp", r.id())).toList();

        Run second = recordOnce(rollouts);

        JsonFileStorage fresh = new JsonFileStorage(dir);
        List<RunData> all = fresh.listRuns("exp");
        assertThat(all).hasSize(10);
        for (RunData r : afterFirst) {
            RunData reread = all.stream().filter(x -> x.id().equals(r.id())).findFirst().orElseThrow();
            assertThat(reread.status()).isEqualTo(r.status());
            assertThat(reread.config().values()).isEqualTo(r.config().values());
            assertThat(reread.summary().values()).isEqualTo(r.summary().values());
            assertThat(fresh.loadEvents("exp", r.id())).hasSameSizeAs(firstEvents.get(afterFirst.indexOf(r)));
        }
        List<RunData> secondChildren = all.stream().filter(r -> second.id().equals(r.parentRunId())).toList();
        assertThat(secondChildren).hasSize(4);
        assertThat(secondChildren).extracting(r -> r.config().values().get("subagent.threadId"))
                .containsExactlyInAnyOrder(CodexSubagentCaptureTest.CHILD_A, CodexSubagentCaptureTest.CHILD_B,
                        CodexSubagentCaptureTest.CHILD_C, CodexSubagentCaptureTest.ORPHAN);
        assertThat(secondChildren).extracting(r -> r.config().values().get("subagent.sessionId"))
                .containsOnly(CodexSubagentCaptureTest.ROOT);
        assertThat(all.stream().filter(r -> first.id().equals(r.parentRunId()))).hasSize(4);
    }

    @org.junit.jupiter.api.Test
    void aCaptureWithoutSpawnsWritesNoSubagentKey(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        io.github.markpollack.journal.Journal.configure(new io.github.markpollack.journal.storage.JsonFileStorage(dir));
        CodexPhaseCapture plain = new CodexPhaseCapture("execute", "p", "gpt-5", "0.160.1", "s1", 10, 5, 0, 0, 0,
                100L, false, "done", java.util.List.of());
        io.github.markpollack.journal.Run run = io.github.markpollack.journal.Journal.run("exp").start();
        new CodexRunRecorder(run).recordPhase(plain);
        run.close();

        io.github.markpollack.journal.storage.JsonFileStorage storage =
                new io.github.markpollack.journal.storage.JsonFileStorage(dir);
        org.assertj.core.api.Assertions.assertThat(storage.listRuns("exp")).hasSize(1);
        io.github.markpollack.journal.event.LLMCallEvent call = storage.loadEvents("exp", run.id()).stream()
                .filter(io.github.markpollack.journal.event.LLMCallEvent.class::isInstance)
                .map(io.github.markpollack.journal.event.LLMCallEvent.class::cast).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(call.metadata()).doesNotContainKeys(
                CodexRunRecorder.META_SUBAGENTS, CodexRunRecorder.META_SUBAGENTS_WITHOUT_TRACK,
                CodexRunRecorder.META_SUBAGENT_TRACKS_AVAILABLE);
        io.github.markpollack.journal.Journal.reset();
    }
}
