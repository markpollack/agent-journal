package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.storage.SourceRecordings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Processing the same Claude Code stream twice records it once; see {@link RunRecorder#recordOnce}. */
@DisplayName("Claude Code: record an execution once")
class RecordOnceTest {

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    private static Map<String, String> snapshot(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(dir.relativize(p).toString(), Files.readString(p));
            }
        }
        return files;
    }

    private static double totalCost(JsonFileStorage storage) {
        double cost = 0;
        for (RunData run : storage.listRuns("exp")) {
            for (var event : storage.loadEvents("exp", run.id())) {
                if (event instanceof LLMCallEvent llm) {
                    cost += llm.totalCostUsd();
                }
            }
        }
        return cost;
    }

    @Test
    @DisplayName("the same stream recorded twice adds no run and changes no total, across a fresh storage")
    void sameExecutionRecordsOnce(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        PhaseCapture phase = SubagentCaptureTest.parseFixture();
        assertThat(RunRecorder.sourceKeyOf(phase)).isEqualTo("sess-synthetic-0001:msg_main_3");

        SourceRecordings.Outcome first = RunRecorder.recordOnce("exp", phase, b -> b.config("model", "m"));
        assertThat(first.recordedNow()).isTrue();
        JsonFileStorage after1 = new JsonFileStorage(dir);
        assertThat(after1.listRuns("exp")).hasSize(5);
        double cost1 = totalCost(after1);
        assertThat(cost1).isEqualTo(0.5);
        Map<String, String> files1 = snapshot(dir);

        Journal.reset();
        Journal.configure(new JsonFileStorage(dir));
        SourceRecordings.Outcome second = RunRecorder.recordOnce("exp", phase, b -> b.config("model", "m"));

        assertThat(second.recordedNow()).isFalse();
        assertThat(second.runId()).isEqualTo(first.runId());
        JsonFileStorage after2 = new JsonFileStorage(dir);
        assertThat(after2.listRuns("exp")).hasSize(5);
        assertThat(totalCost(after2)).isEqualTo(cost1);
        assertThat(snapshot(dir)).isEqualTo(files1);
    }

    @Test
    @DisplayName("a later prompt of the same session is another execution and records normally")
    void anotherPromptOfTheSessionRecords(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        PhaseCapture phase = SubagentCaptureTest.parseFixture();
        RunRecorder.recordOnce("exp", phase, null);

        // Same session, another final message: a different key.
        TurnUsage next = new TurnUsage("msg_main_9", "claude-synthetic-1", 10, 5, 0, 0, List.of());
        PhaseCapture later = new PhaseCapture("execute", "p", 10, 5, 0, 0, 0, 100L, 90L, 0.01, phase.sessionId(), 1,
                false, "ok", List.of(), List.of(), "ok", List.of(), List.of(next), List.of());
        assertThat(RunRecorder.sourceKeyOf(later)).isEqualTo("sess-synthetic-0001:msg_main_9");

        SourceRecordings.Outcome outcome = RunRecorder.recordOnce("exp", later, null);

        assertThat(outcome.recordedNow()).isTrue();
        assertThat(new JsonFileStorage(dir).listRuns("exp")).hasSize(6);
    }

    @Test
    @DisplayName("an earlier recording that has not ended is not resumed and not duplicated: it fails")
    void incompleteEarlierRecordingFails(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        PhaseCapture phase = SubagentCaptureTest.parseFixture();
        Run interrupted = SourceRecordings.newRecording("exp", RunRecorder.SOURCE_KIND,
                RunRecorder.sourceKeyOf(phase)).start();

        assertThatThrownBy(() -> RunRecorder.recordOnce("exp", phase, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(interrupted.id());
        assertThat(new JsonFileStorage(dir).listRuns("exp")).hasSize(1);
    }

    @Test
    @DisplayName("a recording the guard itself aborted is not taken as the record: the next attempt fails")
    void abortedRecordingFails() throws Exception {
        // In-memory storage keeps no derived events, so finish() throws after the phase was logged.
        Journal.configure(new io.github.markpollack.journal.storage.InMemoryStorage());
        PhaseCapture phase = SubagentCaptureTest.parseFixture();

        assertThatThrownBy(() -> RunRecorder.recordOnce("exp", phase, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not persist them durably");
        String aborted = SourceRecordings.find("exp", RunRecorder.SOURCE_KIND, RunRecorder.sourceKeyOf(phase))
                .orElseThrow().id();

        assertThatThrownBy(() -> RunRecorder.recordOnce("exp", phase, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(aborted)
                .hasMessageContaining("Remove that run first");
        assertThat(Journal.storage().listRuns("exp").stream()
                .filter(r -> r.config().values().containsKey(SourceRecordings.CONFIG_SOURCE_KEY))).hasSize(1);
    }

    @Test
    @DisplayName("a capture without wire message ids cannot be recorded once")
    void noKeyIsRejected(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));
        PhaseCapture programmatic = new PhaseCapture("p", null, 1, 1, 0, 0, 0, 1L, 1L, 0.0, "sess", 1, false, "x",
                List.of(), List.of(), "x", List.of(), List.of(), List.of());

        assertThat(RunRecorder.sourceKeyOf(programmatic)).isNull();
        assertThatThrownBy(() -> RunRecorder.recordOnce("exp", programmatic, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
