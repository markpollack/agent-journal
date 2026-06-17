package io.github.markpollack.journal;

import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.trace.AttributionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Run#logDerivedEvent} routes derived (analysis) events to the {@code analysis.jsonl}
 * sidecar via the run's own storage — physically separate from the immutable execution stream.
 */
@DisplayName("Run.logDerivedEvent")
class RunDerivedEventTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        Journal.configure(storage);
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    private static StepCostEvent stepCost(String runId, String stepId) {
        return new StepCostEvent(Instant.parse("2026-06-17T12:00:00Z"), runId, stepId, "msg_1", "Bash",
                10, 200, 0.04, 0.04, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code");
    }

    @Test
    @DisplayName("writes to analysis sidecar, never the execution event stream")
    void writesToAnalysisSidecar() {
        String runId;
        try (Run run = Journal.run("exp-1").start()) {
            runId = run.id();
            run.logEvent(ToolCallEvent.success("toolu_1", "Bash", Map.of("command", "ls"), "out", 5));
            run.logDerivedEvent(stepCost(runId, "toolu_1"));
        }

        List<DerivedEvent> derived = storage.loadDerivedEvents("exp-1", runId);
        assertThat(derived).hasSize(1);
        assertThat(derived.get(0)).isInstanceOf(StepCostEvent.class);
        assertThat(derived.get(0).stepId()).isEqualTo("toolu_1");

        // Execution stream carries only the tool call — the derived cost is not mixed in.
        List<JournalEvent> events = storage.loadEvents("exp-1", runId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(ToolCallEvent.class);
    }

    @Test
    @DisplayName("rejects derived events on a finished run, like logEvent")
    void rejectsAfterFinish() {
        Run run = Journal.run("exp-1").start();
        String runId = run.id();
        run.close();

        assertThatThrownBy(() -> run.logDerivedEvent(stepCost(runId, "toolu_1")))
                .isInstanceOf(IllegalStateException.class);
    }
}
