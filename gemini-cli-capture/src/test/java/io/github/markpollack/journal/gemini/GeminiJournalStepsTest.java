package io.github.markpollack.journal.gemini;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Parallel to {@code JournalStepsTest}: the Gemini extractor produces the same portable
 * {@link JournalStep} shape, so the identical ACT pipeline runs on a Gemini trace.
 */
class GeminiJournalStepsTest {

    @Test
    void producesOneTurnLevelStepCarryingTheRunCost() {
        GeminiPhaseCapture capture = new GeminiPhaseCapture("run-1", "p", "gemini-2.5-pro",
                100, 50, 150, 1500, 0.003, false, "SUCCESS", "answer");

        List<JournalStep> steps = GeminiJournalSteps.fromPhaseCapture(capture, "run-1");

        assertThat(steps).hasSize(1);
        JournalStep step = steps.get(0);
        assertThat(step.stepId()).isEqualTo("run-1:turn");
        assertThat(step.toolName()).isNull();
        assertThat(step.vendor()).isEqualTo("gemini-cli");
        assertThat(step.attributionMethod()).isEqualTo(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL);
        // ground truth preserved; the single turn takes the whole run cost
        assertThat(step.attributedCostUsd()).isCloseTo(0.003, within(1e-12));
        assertThat(step.actualRunCostUsd()).isCloseTo(0.003, within(1e-12));
        assertThat(step.inputTokens()).isEqualTo(100);
        assertThat(step.outputTokens()).isEqualTo(50);
    }

    @Test
    void errorCaptureMarksStepError() {
        GeminiPhaseCapture capture = new GeminiPhaseCapture("run-2", "p", "gemini-2.5-pro",
                10, 0, 10, 200, 0.0, true, "ERROR", "");
        JournalStep step = GeminiJournalSteps.fromPhaseCapture(capture, "run-2").get(0);
        assertThat(step.isError()).isTrue();
    }
}
