package io.github.markpollack.journal.derived;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.JournalStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.trace.AttributionMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R2.10: derived events persist to the analysis.jsonl sidecar — physically separate from the
 * immutable events.jsonl — and join back to execution events by stepId.
 */
class DerivedEventStorageTest {

    private static final String EXP = "exp-1";
    private static final String RUN = "run-1";

    private static StepCostEvent stepCost(String stepId) {
        return new StepCostEvent(Instant.parse("2026-06-16T12:00:00Z"), RUN, stepId, "msg_1", "Bash",
                10, 742, 0.031, 0.094841, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code");
    }

    private static void seed(JournalStorage storage) {
        storage.saveExperiment(Experiment.create(EXP).build());
        storage.saveRun(RunData.builder().id(RUN).experimentId(EXP).build());
    }

    @Test
    void inMemoryRoundTripsAndStartsEmpty() {
        InMemoryStorage storage = new InMemoryStorage();
        seed(storage);

        assertThat(storage.loadDerivedEvents(EXP, RUN)).isEmpty();

        storage.appendDerivedEvent(EXP, RUN, stepCost("toolu_1"));
        List<DerivedEvent> loaded = storage.loadDerivedEvents(EXP, RUN);
        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0)).isInstanceOf(StepCostEvent.class);
        assertThat(loaded.get(0).stepId()).isEqualTo("toolu_1");
    }

    @Test
    void jsonFileWritesAnalysisSidecarSeparateFromEventsAndJoinsByStepId(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        seed(storage);

        // execution event (events.jsonl) carrying the stable tool_use id
        storage.appendEvent(EXP, RUN, ToolCallEvent.success("toolu_1", "Bash", Map.of("command", "ls"), "out", 5));
        // derived event (analysis.jsonl) keyed to the same step
        storage.appendDerivedEvent(EXP, RUN, stepCost("toolu_1"));

        // Round-trips out of the sidecar, polymorphically
        List<DerivedEvent> derived = storage.loadDerivedEvents(EXP, RUN);
        assertThat(derived).hasSize(1);
        StepCostEvent sc = (StepCostEvent) derived.get(0);
        assertThat(sc.attributedCostUsd()).isEqualTo(0.031);

        // Separation: execution stream has only the tool call; analysis stream only the cost
        List<JournalEvent> events = storage.loadEvents(EXP, RUN);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ToolCallEvent.class);

        // Physically separate files
        Path runDir = dir.resolve("experiments").resolve(EXP).resolve("runs").resolve(RUN);
        assertThat(Files.exists(runDir.resolve("events.jsonl"))).isTrue();
        assertThat(Files.exists(runDir.resolve("analysis.jsonl"))).isTrue();
        assertThat(Files.readString(runDir.resolve("analysis.jsonl"))).contains("\"@type\":\"step_cost\"");
        assertThat(Files.readString(runDir.resolve("events.jsonl"))).doesNotContain("step_cost");

        // Join: the derived cost attaches to the execution step by a shared stable id
        String execId = ((ToolCallEvent) events.get(0)).id();
        assertThat(sc.stepId()).isEqualTo(execId).isEqualTo("toolu_1");
    }

    @Test
    void defaultStorageReturnsEmptyDerivedEvents() {
        // A storage that doesn't override the derived ops gets the safe default.
        JournalStorage minimal = new InMemoryStorage();
        assertThat(minimal.loadDerivedEvents("x", "y")).isEmpty();
    }
}
