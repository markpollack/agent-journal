package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How a {@link RunRecorder} ends its run. Only {@link RunRecorder#finish()} records a completed
 * run; a recorder closed without it records {@code CRASHED}, because {@code close()} cannot tell
 * a normal exit from an exception leaving the block. A plain {@link Run} keeps its own rule and
 * still closes as {@code FINISHED}. Each outcome is read back through a fresh storage instance.
 */
@DisplayName("RunRecorder lifecycle")
class RunRecorderLifecycleTest {

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    private static PhaseCapture capture() {
        TurnUsage toolTurn = new TurnUsage("msg_1", "claude-opus-4-8", 100, 200, 0, 0, List.of("toolu_1"));
        TurnUsage finalTurn = new TurnUsage("msg_2", "claude-opus-4-8", 100, 50, 0, 0, List.of());
        return new PhaseCapture("explore", "do the thing", 200, 250, 0, 0, 0, 1200L, 1000L, 0.10,
                "session-1", 2, false, "done", List.of(),
                List.of(new ToolUseRecord("toolu_1", "Bash", Map.of("command", "ls"))), "done",
                List.of(), List.of(toolTurn, finalTurn), List.of());
    }

    private static RunData reload(Path dir, String runId) {
        return new JsonFileStorage(dir).loadRun("exp", runId).orElseThrow();
    }

    @Test
    @DisplayName("finish() then close() records FINISHED and success=true")
    void finishRecordsFinished(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));

        String runId;
        try (RunRecorder recorder = new RunRecorder(Journal.run("exp").start())) {
            runId = recorder.run().id();
            recorder.recordPhase(capture());
            recorder.finish();
        }

        RunData saved = reload(dir, runId);
        assertThat(saved.status()).isEqualTo(RunStatus.FINISHED);
        assertThat(saved.summary().values()).containsEntry("success", true);
        assertThat(saved.endTime()).isNotNull();
    }

    @Test
    @DisplayName("close() without finish() records CRASHED and success=false")
    void closeWithoutFinishRecordsCrashed(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));

        String runId;
        try (RunRecorder recorder = new RunRecorder(Journal.run("exp").start())) {
            runId = recorder.run().id();
            recorder.recordPhase(capture());
        }

        RunData saved = reload(dir, runId);
        assertThat(saved.status()).isEqualTo(RunStatus.CRASHED);
        assertThat(saved.summary().values()).containsEntry("success", false);
        assertThat(saved.endTime()).isNotNull();
    }

    @Test
    @DisplayName("an exception leaving the block is not recorded as a success")
    void exceptionLeavingBlockRecordsCrashed(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").start();

        assertThatThrownBy(() -> {
            try (RunRecorder recorder = new RunRecorder(run)) {
                recorder.recordPhase(capture());
                throw new IllegalArgumentException("the phase after this one could not be parsed");
            }
        }).isInstanceOf(IllegalArgumentException.class);

        RunData saved = reload(dir, run.id());
        assertThat(saved.status()).isEqualTo(RunStatus.CRASHED);
        assertThat(saved.summary().values()).containsEntry("success", false);
    }

    @Test
    @DisplayName("failRun(cause) then close() keeps FAILED and the cause")
    void failRunIsKept(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));

        String runId;
        try (RunRecorder recorder = new RunRecorder(Journal.run("exp").start())) {
            runId = recorder.run().id();
            recorder.recordPhase(capture());
            recorder.failRun(new IllegalStateException("agent process exited 137"));
        }

        RunData saved = reload(dir, runId);
        assertThat(saved.status()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.errorMessage()).isEqualTo("agent process exited 137");
    }

    @Test
    @DisplayName("a caller's own success value survives a close() without finish()")
    void callerSuccessValueIsKept(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));

        Run run = Journal.run("exp").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            run.setSummary("success", true);
        }

        RunData saved = reload(dir, run.id());
        assertThat(saved.status()).isEqualTo(RunStatus.CRASHED);
        assertThat(saved.summary().values()).containsEntry("success", true);
    }

    @Test
    @DisplayName("a plain Run closed without finish() still records FINISHED")
    void plainRunStillClosesFinished(@TempDir Path dir) {
        Journal.configure(new JsonFileStorage(dir));

        String runId;
        try (Run run = Journal.run("exp").start()) {
            runId = run.id();
        }

        RunData saved = reload(dir, runId);
        assertThat(saved.status()).isEqualTo(RunStatus.FINISHED);
        assertThat(saved.summary().values()).containsEntry("success", true);
    }

    @Test
    @DisplayName("finish() on storage that loses derived events ends the run as FAILED, then throws")
    void finishEndsTheRunBeforeThrowing() {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run);
        recorder.recordPhase(capture());

        assertThatThrownBy(recorder::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not persist them durably");

        RunData saved = storage.loadRun("exp", run.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.endTime()).isNotNull();
        assertThat(saved.summary().values()).containsEntry("success", false);
        assertThat(saved.errorMessage()).contains("does not persist them durably");
        assertThat(saved.errorType()).isEqualTo(IllegalStateException.class.getName());

        // The error was reported once; closing the ended run is quiet.
        assertThatCode(recorder::close).doesNotThrowAnyException();
        assertThatCode(recorder::finish).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("close() without finish() on such storage ends the run as CRASHED, then throws")
    void closeEndsTheRunBeforeThrowing() {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run);
        recorder.recordPhase(capture());

        assertThatThrownBy(recorder::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not persist them durably");

        RunData saved = storage.loadRun("exp", run.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(RunStatus.CRASHED);
        assertThat(saved.endTime()).isNotNull();
        assertThatCode(recorder::close).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("finish() does not check the storage of a run that already failed")
    void finishSkipsTheCheckOnAFailedRun() {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run);
        recorder.recordPhase(capture());
        recorder.failRun(new IllegalStateException("agent process exited 137"));

        assertThatCode(recorder::finish).doesNotThrowAnyException();

        RunData saved = storage.loadRun("exp", run.id()).orElseThrow();
        assertThat(saved.status()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.errorMessage()).isEqualTo("agent process exited 137");
    }
}
