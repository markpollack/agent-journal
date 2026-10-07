package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
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

/**
 * Processing the same Codex execution twice records it once. The guard reads the run records in
 * storage, so a second process, here a fresh {@link JsonFileStorage} on the same directory, makes
 * the same decision.
 */
@DisplayName("Codex: record an execution once")
class CodexRecordOnceTest {

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

    private static long[] aggregate(JsonFileStorage storage) {
        long input = 0;
        long output = 0;
        double cost = 0;
        for (RunData run : storage.listRuns("exp")) {
            for (var event : storage.loadEvents("exp", run.id())) {
                if (event instanceof LLMCallEvent llm) {
                    input += llm.tokenUsage().inputTokens();
                    output += llm.tokenUsage().outputTokens();
                    cost += llm.totalCostUsd();
                }
            }
        }
        return new long[] {input, output, (long) (cost * 1_000_000)};
    }

    @Test
    @DisplayName("the same rollouts recorded twice add no run and change no total; a fresh storage sees the first")
    void sameExecutionRecordsOnce(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        CodexPhaseCapture phase = CodexSessionParser.parse(CodexSubagentCaptureTest.allRollouts(), "phase", "p");

        SourceRecordings.Outcome first = CodexRunRecorder.recordOnce("exp", phase, b -> b.config("model", "m"));
        assertThat(first.recordedNow()).isTrue();
        JsonFileStorage after1 = new JsonFileStorage(dir);
        int runs1 = after1.listRuns("exp").size();
        long[] totals1 = aggregate(after1);
        Map<String, String> files1 = snapshot(dir);
        assertThat(runs1).isEqualTo(5);
        assertThat(after1.loadRun("exp", first.runId()).orElseThrow().config().values())
                .containsEntry(SourceRecordings.CONFIG_SOURCE_KIND, "codex")
                .containsEntry(SourceRecordings.CONFIG_SOURCE_KEY, phase.sessionId());

        // As after a restart: a new storage instance over the same directory.
        Journal.reset();
        Journal.configure(new JsonFileStorage(dir));
        SourceRecordings.Outcome second = CodexRunRecorder.recordOnce("exp", phase, b -> b.config("model", "m"));

        assertThat(second.recordedNow()).isFalse();
        assertThat(second.runId()).isEqualTo(first.runId());
        JsonFileStorage after2 = new JsonFileStorage(dir);
        assertThat(after2.listRuns("exp")).hasSize(runs1);
        assertThat(aggregate(after2)).containsExactly(totals1);
        assertThat(snapshot(dir)).isEqualTo(files1);
    }

    @Test
    @DisplayName("a different execution records normally beside the first")
    void distinctExecutionRecords(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        CodexPhaseCapture phase = CodexSessionParser.parse(CodexSubagentCaptureTest.allRollouts(), "phase", "p");
        CodexRunRecorder.recordOnce("exp", phase, null);

        // The same shapes with every thread id renamed: another execution.
        List<CodexRollout> renamed = CodexSubagentCaptureTest.allRollouts().rollouts().stream()
                .map(r -> new CodexRollout(rename(r.threadId()), rename(r.parentThreadId()), r.sourcePath(),
                        r.lines().stream().map(CodexRecordOnceTest::rename).toList()))
                .toList();
        CodexPhaseCapture other = CodexSessionParser.parse(new CodexRollouts(renamed), "phase", "p");
        assertThat(other.sessionId()).isNotEqualTo(phase.sessionId());

        SourceRecordings.Outcome outcome = CodexRunRecorder.recordOnce("exp", other, null);

        assertThat(outcome.recordedNow()).isTrue();
        assertThat(new JsonFileStorage(dir).listRuns("exp")).hasSize(10);
    }

    private static String rename(String s) {
        return s == null ? null : s.replace("-000", "-900");
    }

    @Test
    @DisplayName("an earlier recording that has not ended is not resumed and not duplicated: it fails")
    void incompleteEarlierRecordingFails(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        CodexPhaseCapture phase = CodexSessionParser.parse(CodexSubagentCaptureTest.allRollouts(), "phase", "p");
        Run interrupted = SourceRecordings.newRecording("exp", CodexRunRecorder.SOURCE_KIND, phase.sessionId()).start();
        assertThat(interrupted.status()).isEqualTo(RunStatus.RUNNING);

        assertThatThrownBy(() -> CodexRunRecorder.recordOnce("exp", phase, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(interrupted.id())
                .hasMessageContaining("has not ended");
        assertThat(new JsonFileStorage(dir).listRuns("exp")).hasSize(1);
    }

    @Test
    @DisplayName("a capture without a thread id cannot be recorded once")
    void noSessionIdIsRejected(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));
        CodexPhaseCapture phase = new CodexPhaseCapture("p", null, "gpt-5", "0.160.1", null, 10, 5, 0, 0, 0, 100L,
                false, "done", List.of());

        assertThatThrownBy(() -> CodexRunRecorder.recordOnce("exp", phase, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
