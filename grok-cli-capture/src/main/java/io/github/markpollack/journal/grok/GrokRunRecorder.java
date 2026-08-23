package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records a Grok capture into the shared execution and derived journal streams. */
public final class GrokRunRecorder {

    private final Run run;

    public GrokRunRecorder(Run run) {
        this.run = run;
    }

    public void recordPhase(GrokPhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt", Map.of(
                    "phase", phase.phaseName(),
                    "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("numTurns", phase.numTurns());
        metadata.put("isError", phase.isError());
        metadata.put("costAvailable", true);
        metadata.put("costSource", "end.total_cost_usd");
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }

        run.logEvent(LLMCallEvent.builder()
                .provider("xai")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .finishReason(phase.stopReason())
                .metadata(metadata)
                .build());

        for (GrokToolUseRecord tool : phase.toolUses()) {
            ToolCallEvent event = ToolCallEvent.builder()
                    .id(tool.id())
                    .toolName(tool.name())
                    .kind(tool.kind())
                    .input(tool.input())
                    .output(tool.output())
                    .success(!tool.isError())
                    .errorMessage(tool.errorMessage())
                    .build();
            run.logEvent(event);
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : GrokJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }

    public Run run() {
        return run;
    }
}
