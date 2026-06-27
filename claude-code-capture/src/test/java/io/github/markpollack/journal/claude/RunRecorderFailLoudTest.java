package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production {@link RunRecorder} fail-loud contract (DESIGN §4): a recorder that produced derived
 * {@link StepCostEvent}s but is backed by a storage that can't durably persist them must announce it —
 * throw by default, WARN under {@link RunRecorder#lenient()} — so the measurement-critical derived
 * layer is never silently dropped. On durable {@link JsonFileStorage} it finishes clean and the
 * provenance ({@code attributionMethod}) is serialized into {@code analysis.jsonl}.
 */
@DisplayName("RunRecorder fail-loud + provenance")
class RunRecorderFailLoudTest {

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

    @Test
    @DisplayName("throws at finish on InMemoryStorage when derived events were produced")
    void throwsOnNonDurableStorage() {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);

        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run);
        recorder.recordPhase(capture());

        assertThatThrownBy(recorder::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not persist them durably");
    }

    @Test
    @DisplayName("throws from close() too (try-with-resources surfaces the misconfiguration)")
    void throwsFromClose() {
        Journal.configure(new InMemoryStorage());
        assertThatThrownBy(() -> {
            try (RunRecorder recorder = new RunRecorder(Journal.run("exp").start())) {
                recorder.recordPhase(capture());
            }
        }).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("lenient() downgrades to a WARN — finishes successfully, no throw")
    void lenientWarnsInsteadOfThrowing() {
        Journal.configure(new InMemoryStorage());

        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).lenient();
        recorder.recordPhase(capture());

        recorder.finish(); // must not throw
        assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
    }

    @Test
    @DisplayName("does not throw when no derived events were produced")
    void noThrowWhenNoDerivedEvents() {
        Journal.configure(new InMemoryStorage());
        Run run = Journal.run("exp").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            // recordPhase never called → derivedEventsEmitted == 0
        }
        assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
    }

    @Test
    @DisplayName("finishes clean on JsonFileStorage; provenance is serialized into analysis.jsonl")
    void durableStorageFinishesCleanWithProvenance(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));

        String runId;
        try (RunRecorder recorder = new RunRecorder(Journal.run("exp").start())) {
            runId = recorder.run().id();
            recorder.recordPhase(capture());
        } // no throw

        // Derived events durably present, every one carrying the attribution method.
        JsonFileStorage reopened = new JsonFileStorage(dir);
        List<DerivedEvent> derived = reopened.loadDerivedEvents("exp", runId);
        assertThat(derived).hasSize(2);
        assertThat(derived).allSatisfy(d -> assertThat(((StepCostEvent) d).attributionMethod())
                .isEqualTo(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL));

        // Provenance is actually on the wire (serialized), not just on the in-memory object.
        Path analysis = dir.resolve("experiments/exp/runs/" + runId + "/analysis.jsonl");
        assertThat(analysis).exists();
        for (String line : Files.readAllLines(analysis)) {
            if (line.isBlank() || line.contains("\"@type\":\"header\"")) {
                continue; // A5 schema-version header line
            }
            assertThat(line).contains("\"attributionMethod\":\"OUTPUT_TOKEN_PROPORTIONAL\"");
        }
    }
}
