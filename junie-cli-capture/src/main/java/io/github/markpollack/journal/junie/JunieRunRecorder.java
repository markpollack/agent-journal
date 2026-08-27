package io.github.markpollack.journal.junie;

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

/** Records a Junie capture into journal-core execution and derived streams. */
public final class JunieRunRecorder {

    private final Run run;

    public JunieRunRecorder(Run run) {
        this.run = run;
    }

    public void recordPhase(JuniePhaseCapture phase) {
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            run.logEvent(CustomEvent.of("prompt", Map.of(
                    "phase", phase.phaseName(),
                    "text", phase.promptText())));
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        metadata.put("numLlmCalls", phase.numLlmCalls());
        metadata.put("isError", phase.isError());
        // Junie prices every LLM call and its per-call costs reconcile to the session total, so
        // unlike the Grok/Codex/Antigravity adapters this is a reported cost, not an absence.
        metadata.put("costAvailable", phase.hasModelCosts());
        metadata.put("costSource", phase.hasModelCosts() ? "reported" : "unreported");
        metadata.put("costReconciles", phase.reconcilesToModelCosts());
        // The stop reason and the ceiling it ran against, written together and unconditionally
        // (1.9.0 rule). Junie enforces no ceiling, so maxTurns is always -1 = "not reported".
        metadata.put("stopReason", phase.stopReason().name());
        metadata.put("maxTurns", phase.maxTurns());
        if (phase.taskId() != null) {
            metadata.put("taskId", phase.taskId());
        }
        if (phase.taskState() != null) {
            metadata.put("taskState", phase.taskState());
        }
        if (phase.errorCode() != null) {
            metadata.put("errorCode", phase.errorCode());
        }
        if (phase.contextWindowSize() > 0) {
            metadata.put("contextWindowUsed", phase.contextWindowUsed());
            metadata.put("contextWindowSize", phase.contextWindowSize());
        }
        // Carried through so a later subagent model has the evidence it needs. Every event in
        // every captured trace reports the main agent; nothing here builds a subagent model.
        metadata.put("agentKind", "MainAgent");

        run.logEvent(LLMCallEvent.builder()
                .provider("jetbrains")
                .model(phase.model() != null ? phase.model() : "unknown")
                .tokenUsage(phase.tokenUsage())
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs()))
                .finishReason(phase.errorCode())
                .metadata(metadata)
                .build());

        for (JunieToolUseRecord tool : phase.toolUses()) {
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

        for (String thinking : phase.thinkingBlocks()) {
            run.logEvent(CustomEvent.of("thinking_block", Map.of(
                    "phase", phase.phaseName(),
                    "content", thinking)));
        }

        Instant analyzedAt = Instant.now();
        for (JournalStep step : JunieJournalSteps.fromPhaseCapture(phase, run.id())) {
            run.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
    }
}
