package io.github.markpollack.journal.gemini;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Records a Gemini CLI {@link GeminiPhaseCapture} into a {@link Run} — the Gemini analog of
 * {@code claude-code-capture}'s {@code BaseRunRecorder}, projecting into the <em>same</em>
 * journal-core streams so a Gemini run produces the identical shape as a Claude run:
 * <ul>
 *   <li>execution events ({@code events.jsonl}) — the prompt and an {@link LLMCallEvent} carrying
 *       the turn's tokens / cost / timing;</li>
 *   <li>derived events ({@code analysis.jsonl}) — the per-step {@link StepCostEvent}, the
 *       inferred cost attribution, kept out of the execution stream.</li>
 * </ul>
 *
 * <p>
 * Concrete (not abstract like the Claude base) because Gemini capture is single-grain: the typed
 * SDK exposes no tool calls, so one query result is one turn-level step (see
 * {@link GeminiPhaseCapture} and {@link GeminiJournalSteps}). The cost is SDK-provided
 * (pricing-derived); {@code actualRunCostUsd} stays the ground-truth total.
 */
public class GeminiRunRecorder {

    private final Run run;

    public GeminiRunRecorder(Run run) {
        this.run = run;
    }

    /**
     * Records one Gemini query capture: writes the execution events and the derived per-step cost.
     */
    public void recordPhase(GeminiPhaseCapture phase) {
        // Prompt capture — record the exact prompt sent for reproducibility.
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt", Map.of(
                    "phase", phase.phaseName(),
                    "text", phase.promptText())));
        }

        // LLM call with token/cost/timing data. Gemini's typed model has no thinking tokens and no
        // separate API duration, so those slots are 0.
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("status", phase.status());
        metadata.put("isError", phase.isError());

        run.logEvent(LLMCallEvent.builder()
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(TokenUsage.of(phase.promptTokens(), phase.completionTokens(), 0))
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs(), 0))
                .metadata(metadata)
                .build());

        // Derived per-step cost → analysis.jsonl (mirrors the Claude path). One turn-level step
        // takes 100% of the run cost; the synthesized stepId ({runId}:turn) is the join key.
        Instant analyzedAt = Instant.now();
        for (JournalStep step : GeminiJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }

    public Run run() {
        return run;
    }
}
