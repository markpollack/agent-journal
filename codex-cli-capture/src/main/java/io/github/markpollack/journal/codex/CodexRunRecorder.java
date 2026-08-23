package io.github.markpollack.journal.codex;

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

/** Records a harvested Codex rollout into journal-core streams. */
public final class CodexRunRecorder {

    private final Run run;

    public CodexRunRecorder(Run run) {
        this.run = run;
    }

    public void recordPhase(CodexPhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt", Map.of(
                    "phase", phase.phaseName(),
                    "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("isError", phase.isError());
        metadata.put("costAvailable", false);
        metadata.put("costSource", "unreported");
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }
        if (phase.cliVersion() != null) {
            metadata.put("cliVersion", phase.cliVersion());
        }

        run.logEvent(LLMCallEvent.builder()
                .provider("openai")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(0.0))
                .timing(TimingInfo.of(phase.durationMs()))
                .metadata(metadata)
                .build());

        for (CodexToolUseRecord tool : phase.toolUses()) {
            run.logEvent(ToolCallEvent.builder()
                    .id(tool.id())
                    .toolName(tool.name())
                    .kind(tool.kind())
                    .input(tool.input())
                    .output(tool.output())
                    .success(!tool.isError())
                    .errorMessage(tool.errorMessage())
                    .build());
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : CodexJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }
}
