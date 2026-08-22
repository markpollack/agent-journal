package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records an Antigravity capture into journal-core execution and derived streams. */
public final class AntigravityRunRecorder {

    private final Run run;

    public AntigravityRunRecorder(Run run) {
        this.run = run;
    }

    public void recordPhase(AntigravityPhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt", Map.of(
                    "phase", phase.phaseName(),
                    "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("numTurns", phase.numTurns());
        metadata.put("status", phase.status());
        metadata.put("isError", phase.isError());
        metadata.put("costAvailable", false);
        metadata.put("costSource", "unreported");
        if (phase.conversationId() != null) {
            metadata.put("conversationId", phase.conversationId());
        }

        run.logEvent(LLMCallEvent.builder()
                .provider("google")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(0.0))
                .timing(TimingInfo.of(phase.durationMs()))
                .finishReason(phase.status())
                .metadata(metadata)
                .build());

        for (AntigravityToolUseRecord tool : phase.toolUses()) {
            run.logEvent(ToolCallEvent.builder()
                    .id(tool.id())
                    .toolName(tool.name())
                    .input(tool.input())
                    .output(tool.output())
                    .durationMs(tool.durationMs())
                    .success(!tool.isError())
                    .errorMessage(tool.errorMessage())
                    .build());
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : AntigravityJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }
}
