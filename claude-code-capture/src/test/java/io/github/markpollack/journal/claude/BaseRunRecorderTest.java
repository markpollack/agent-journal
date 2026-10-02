package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code BaseRunRecorder.recordPhase} emits per-step cost ({@link StepCostEvent}) into the
 * analysis sidecar (R2.10 emission), joined to the execution {@link ToolCallEvent} by the
 * shared tool_use id, while the run total stays exactly conserved across the attributed steps.
 */
@DisplayName("BaseRunRecorder cost emission")
class BaseRunRecorderTest {

    /** Minimal concrete recorder — the base provides all phase recording. */
    private static final class TestRecorder extends BaseRunRecorder {
        TestRecorder(Run run) {
            this.currentRun = run;
        }
    }

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

    private static PhaseCapture capture() {
        // Two turns: one issuing a Bash tool call (200 out tokens), one tool-less final turn (50).
        TurnUsage toolTurn = new TurnUsage("msg_1", "claude-opus-4-8", 100, 200, 0, 0, List.of("toolu_1"));
        TurnUsage finalTurn = new TurnUsage("msg_2", "claude-opus-4-8", 100, 50, 0, 0, List.of());
        return new PhaseCapture("explore", "do the thing", 200, 250, 0, 0, 0, 1200L, 1000L, 0.10,
                "session-1", 2, false, "done",
                List.of(),
                List.of(new ToolUseRecord("toolu_1", "Bash", Map.of("command", "ls"))),
                "done",
                List.of(),
                List.of(toolTurn, finalTurn),
                List.of());
    }

    @Test
    @DisplayName("emits a StepCostEvent per step whose shares sum to the run total")
    void emitsStepCostPerStep() {
        String runId;
        try (Run run = Journal.run("exp-1").start()) {
            runId = run.id();
            new TestRecorder(run).recordPhase(capture());
        }

        List<DerivedEvent> derived = storage.loadDerivedEvents("exp-1", runId);
        assertThat(derived).hasSize(2);
        assertThat(derived).allSatisfy(d -> assertThat(d).isInstanceOf(StepCostEvent.class));

        // One step per turn: the Bash tool step (stable tool_use id) and the tool-less final turn.
        assertThat(derived).extracting(DerivedEvent::stepId)
                .containsExactlyInAnyOrder("toolu_1", "msg_2");

        double attributedSum = derived.stream()
                .map(StepCostEvent.class::cast)
                .mapToDouble(StepCostEvent::attributedCostUsd)
                .sum();
        assertThat(attributedSum).isEqualTo(0.10);
        assertThat(derived).extracting(d -> ((StepCostEvent) d).actualRunCostUsd())
                .containsOnly(0.10);
    }

    @Test
    @DisplayName("derived cost joins the execution tool call by stable id and stays out of the event stream")
    void joinsExecutionByStableId() {
        String runId;
        try (Run run = Journal.run("exp-1").start()) {
            runId = run.id();
            new TestRecorder(run).recordPhase(capture());
        }

        List<JournalEvent> events = storage.loadEvents("exp-1", runId);
        List<String> toolIds = events.stream()
                .filter(ToolCallEvent.class::isInstance)
                .map(e -> ((ToolCallEvent) e).id())
                .toList();
        assertThat(toolIds).containsExactly("toolu_1");
        assertThat(events).filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).toolName(),
                        event -> ((ToolCallEvent) event).kind())
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Bash", ToolKind.EXECUTE));

        // No derived cost leaked into the execution stream.
        assertThat(events).noneSatisfy(e -> assertThat(e).isInstanceOf(StepCostEvent.class));

        // The derived step keys to the same id the execution tool call carries.
        StepCostEvent toolStep = storage.loadDerivedEvents("exp-1", runId).stream()
                .map(StepCostEvent.class::cast)
                .filter(s -> "toolu_1".equals(s.stepId()))
                .findFirst().orElseThrow();
        assertThat(toolStep.toolName()).isEqualTo("Bash");
    }

    @Test
    @DisplayName("failRun() without an error records success=false")
    void failRunWithoutAnErrorRecordsSuccessFalse() {
        Run run = Journal.run("exp-1").start();

        new TestRecorder(run).failRun();

        assertThat(storage.loadRun("exp-1", run.id()).orElseThrow().summary().values())
                .containsEntry("success", false);
    }

    @Test
    @DisplayName("a model config value that is not a string is recorded as its text")
    void modelConfigValueThatIsNotAStringIsRecordedAsText() {
        Run run = Journal.run("exp-1").config("model", 42).start();

        new TestRecorder(run).recordPhase(capture());

        assertThat(storage.loadEvents("exp-1", run.id()))
                .filteredOn(e -> e instanceof io.github.markpollack.journal.event.LLMCallEvent)
                .singleElement()
                .extracting(e -> ((io.github.markpollack.journal.event.LLMCallEvent) e).model())
                .isEqualTo("42");
    }

    @Test
    @DisplayName("a phase without a name but with a prompt and thinking is recorded")
    void phaseWithoutANameButWithAPromptIsRecorded() {
        Run run = Journal.run("exp-1").start();
        PhaseCapture phase = new PhaseCapture(null, "the prompt", 10, 5, 0, 0, 0, 100L, 90L, 0.01,
                "s1", 1, false, "done", List.of("a thought"), List.of(), "done", List.of());

        new TestRecorder(run).recordPhase(phase);

        assertThat(storage.loadEvents("exp-1", run.id()))
                .filteredOn(e -> e instanceof io.github.markpollack.journal.event.CustomEvent)
                .allSatisfy(e -> assertThat(((io.github.markpollack.journal.event.CustomEvent) e).attributes())
                        .doesNotContainKey("phase"))
                .extracting(JournalEvent::type)
                .containsExactly("prompt", "thinking_block");
    }

    @Test
    @DisplayName("the LLM call carries the message ID of the phase's last turn as its response ID")
    void llmCallCarriesTheLastTurnMessageIdAsResponseId() {
        Run run = Journal.run("exp-1").start();

        new TestRecorder(run).recordPhase(capture());

        assertThat(storage.loadEvents("exp-1", run.id()))
                .filteredOn(e -> e instanceof io.github.markpollack.journal.event.LLMCallEvent)
                .singleElement()
                .extracting(e -> ((io.github.markpollack.journal.event.LLMCallEvent) e).responseId())
                .isEqualTo("msg_2");
    }
}
