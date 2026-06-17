package io.github.markpollack.journal.gemini;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeminiRunRecorder} projects a Gemini capture into the same journal-core streams as the
 * Claude path: an {@link LLMCallEvent} in the execution log and a turn-level {@link StepCostEvent}
 * in the analysis log. This is the Gemini half of the cross-runtime cost-emission claim.
 */
@DisplayName("GeminiRunRecorder")
class GeminiRunRecorderTest {

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

    private static GeminiPhaseCapture capture() {
        return new GeminiPhaseCapture("query", "summarize the repo", "gemini-2.5-pro",
                1200, 300, 1500, 800L, 0.0123, false, "SUCCESS", "Here is the summary.");
    }

    @Test
    @DisplayName("emits one turn-level StepCostEvent taking the full run cost")
    void emitsTurnLevelStepCost() {
        String runId;
        try (Run run = Journal.run("exp-gemini").start()) {
            runId = run.id();
            new GeminiRunRecorder(run).recordPhase(capture());
        }

        List<DerivedEvent> derived = storage.loadDerivedEvents("exp-gemini", runId);
        assertThat(derived).hasSize(1);
        StepCostEvent step = (StepCostEvent) derived.get(0);
        assertThat(step.stepId()).isEqualTo(runId + ":turn");
        assertThat(step.attributedCostUsd()).isEqualTo(0.0123);
        assertThat(step.actualRunCostUsd()).isEqualTo(0.0123);
        assertThat(step.vendor()).isEqualTo(GeminiJournalSteps.VENDOR_GEMINI_CLI);
    }

    @Test
    @DisplayName("writes the turn's LLM call into the execution stream")
    void writesLlmCallEvent() {
        String runId;
        try (Run run = Journal.run("exp-gemini").start()) {
            runId = run.id();
            new GeminiRunRecorder(run).recordPhase(capture());
        }

        List<JournalEvent> events = storage.loadEvents("exp-gemini", runId);
        LLMCallEvent llm = events.stream()
                .filter(LLMCallEvent.class::isInstance)
                .map(LLMCallEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(llm.model()).isEqualTo("gemini-2.5-pro");

        // The derived cost is not mixed into the execution stream.
        assertThat(events).noneSatisfy(e -> assertThat(e).isInstanceOf(StepCostEvent.class));
    }
}
