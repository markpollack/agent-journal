package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import io.github.markpollack.journal.event.StopReason;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link JunieRunRecorder}.
 */
class JunieRunRecorderTest {

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

    @Test
    void phaseWithoutANameButWithAPromptIsRecorded() {
        Run run = Journal.run("exp").start();
        JuniePhaseCapture phase = new JuniePhaseCapture(null, "the prompt", "m", "t1", "task", 10, 5, 0, 0,
                0.01, 100L, 1, false, "DONE", null, false, "done", null, List.of("a thought"), 0L, 0L,
                StopReason.UNKNOWN, -1, List.of(), List.of());

        new JunieRunRecorder(run).recordPhase(phase);

        List<JournalEvent> events = storage.loadEvents("exp", run.id());
        assertThat(events).filteredOn(e -> e instanceof CustomEvent c && c.type().equals("prompt"))
                .singleElement()
                .satisfies(e -> assertThat(((CustomEvent) e).attributes()).containsEntry("text", "the prompt")
                        .doesNotContainKey("phase"));
    }

    @Test
    void aReportedTotalCostWithoutPerCallCostsCountsAsAvailable() {
        Run run = Journal.run("exp").start();
        JuniePhaseCapture phase = new JuniePhaseCapture("execute", null, "m", "t1", "task", 10, 5, 0, 0,
                0.0755, 100L, 1, false, "DONE", null, false, "done", null, List.of(), 0L, 0L,
                StopReason.UNKNOWN, -1, List.of(), List.of());

        new JunieRunRecorder(run).recordPhase(phase);

        LLMCallEvent call = storage.loadEvents("exp", run.id()).stream()
                .filter(LLMCallEvent.class::isInstance).map(LLMCallEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(call.metadata()).containsEntry("costAvailable", true)
                .containsEntry("costSource", "reported")
                .containsEntry("costReconciles", false);
    }
}
