package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GrokRunRecorder}.
 */
class GrokRunRecorderTest {

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
        GrokPhaseCapture phase = new GrokPhaseCapture(null, "the prompt", "grok-4", 10, 5, 0, 0, 0, 0.01, true,
                "s1", 1, false, "end_turn", "done", null, List.of(), List.of());

        new GrokRunRecorder(run).recordPhase(phase);

        List<JournalEvent> events = storage.loadEvents("exp", run.id());
        assertThat(events).filteredOn(e -> e instanceof CustomEvent c && c.type().equals("prompt"))
                .singleElement()
                .satisfies(e -> assertThat(((CustomEvent) e).attributes()).containsEntry("text", "the prompt")
                        .doesNotContainKey("phase"));
    }

    @Test
    void toolCallDurationIsRecordedAsNotMeasured() {
        Run run = Journal.run("exp").start();
        GrokPhaseCapture phase = new GrokPhaseCapture("execute", null, "grok-4", 10, 5, 0, 0, 0, 0.01, true,
                "s1", 1, false, "end_turn", "done", null,
                List.of(new GrokToolUseRecord("call_1", "run_command", ToolKind.EXECUTE, Map.of("command", "ls"),
                        "out", "completed", false, null)), List.of());

        new GrokRunRecorder(run).recordPhase(phase);

        assertThat(storage.loadEvents("exp", run.id())).filteredOn(ToolCallEvent.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((ToolCallEvent) e).durationMs()).isEqualTo(-1));
    }

    @Test
    void costAndTokensGrokDidNotReportAreMarkedInTheCallMetadata() {
        Run run = Journal.run("exp").start();
        GrokPhaseCapture phase = new GrokPhaseCapture("execute", null, "grok-4", 10, 5, GrokPhaseCapture.NOT_REPORTED,
                0, 0, 0.0, false, "s1", 1, false, "end_turn", "done", null, List.of(), List.of());

        new GrokRunRecorder(run).recordPhase(phase);

        assertThat(storage.loadEvents("exp", run.id())).filteredOn(e -> e instanceof LLMCallEvent)
                .singleElement().satisfies(e -> {
                    LLMCallEvent call = (LLMCallEvent) e;
                    assertThat(call.metadata()).containsEntry("costAvailable", false)
                            .doesNotContainKey("costSource")
                            .containsEntry("tokensNotReported", List.of("thinking"));
                });
    }

    @Test
    void reportedCostIsMarkedAvailableWithItsSource() {
        Run run = Journal.run("exp").start();
        GrokPhaseCapture phase = new GrokPhaseCapture("execute", null, "grok-4", 10, 5, 0, 0, 0, 0.01, true,
                "s1", 1, false, "end_turn", "done", null, List.of(), List.of());

        new GrokRunRecorder(run).recordPhase(phase);

        assertThat(storage.loadEvents("exp", run.id())).filteredOn(e -> e instanceof LLMCallEvent)
                .singleElement().satisfies(e -> assertThat(((LLMCallEvent) e).metadata())
                        .containsEntry("costAvailable", true).containsEntry("costSource", "end.total_cost_usd")
                        .doesNotContainKey("tokensNotReported"));
    }

    @Test
    void keptLinesAreLoggedAsVendorEventsWithTheirPayload() {
        Run run = Journal.run("exp").start();
        GrokPhaseCapture phase = new GrokPhaseCapture("execute", null, "grok-4", 10, 5, 0, 0, 0, 0.01, true,
                "s1", 1, false, "end_turn", "done", null, List.of(),
                List.of(new GrokVendorEvent("brand_new_update", 4, Map.of("n", 1)),
                        new GrokVendorEvent(GrokVendorEvent.NON_JSON_LINE, 2, Map.of("text", "Not signed in"))));

        new GrokRunRecorder(run).recordPhase(phase);

        assertThat(storage.loadEvents("exp", run.id())).filteredOn(e -> e instanceof CustomEvent c && c.type().equals("vendor_event"))
                .hasSize(2).satisfiesExactly(
                        e -> assertThat(((CustomEvent) e).attributes()).containsEntry("vendor", "grok")
                                .containsEntry("kind", "brand_new_update").containsEntry("line", 4L)
                                .containsEntry("payload", Map.of("n", 1)),
                        e -> assertThat(((CustomEvent) e).attributes()).containsEntry("kind", "non_json_line")
                                .containsEntry("payload", Map.of("text", "Not signed in")));
    }
}
