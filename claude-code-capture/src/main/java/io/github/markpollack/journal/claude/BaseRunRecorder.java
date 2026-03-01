package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StateChangeEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.event.ToolCallEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Base tracking recorder with shared phase recording logic.
 *
 * Subclasses provide domain-specific methods:
 * - agent/RunRecorder: recordIteration(), recordSemanticDiff()
 * - spring-upgrade-agent/UpgradeRunRecorder: recordBuildResult(), recordStepCompletion()
 *
 * Both share identical phase recording (state transitions, LLM calls, tool uses,
 * thinking blocks, prompt capture).
 */
public abstract class BaseRunRecorder {

    protected Run currentRun;
    protected String previousPhase = "init";

    /**
     * Records a single phase with full event data:
     * state transition, prompt capture, LLM call, tool calls, thinking blocks.
     */
    public void recordPhase(PhaseCapture phase) {
        // State transition
        currentRun.logEvent(StateChangeEvent.of(previousPhase, phase.phaseName(), "phase transition"));
        previousPhase = phase.phaseName();

        // Prompt capture — record the exact prompt sent for reproducibility
        if (phase.promptText() != null && !phase.promptText().isEmpty()) {
            currentRun.logEvent(CustomEvent.of("prompt", Map.of(
                    "phase", phase.phaseName(),
                    "text", phase.promptText())));
        }

        // LLM call with full token/cost/timing data
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("phaseName", phase.phaseName());
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }
        metadata.put("numTurns", phase.numTurns());
        metadata.put("isError", phase.isError());

        currentRun.logEvent(LLMCallEvent.builder()
                .model(currentRun.config().getOrDefault("model", "unknown"))
                .tokenUsage(TokenUsage.of(phase.inputTokens(), phase.outputTokens(), phase.thinkingTokens()))
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs(), phase.apiDurationMs()))
                .metadata(metadata)
                .build());

        // Tool call events
        if (phase.hasToolUses()) {
            for (ToolUseRecord toolUse : phase.toolUses()) {
                currentRun.logEvent(ToolCallEvent.success(
                        toolUse.name(), toolUse.input(), null, 0));
            }
        }

        // Thinking block events
        if (phase.hasThinking()) {
            for (String thinking : phase.thinkingBlocks()) {
                currentRun.logEvent(CustomEvent.of("thinking_block", Map.of(
                        "phase", phase.phaseName(),
                        "content", thinking)));
            }
        }
    }

    public void failRun(Throwable error) {
        if (currentRun != null) {
            currentRun.fail(error);
        }
    }

    public void failRun() {
        if (currentRun != null) {
            currentRun.finish(RunStatus.FAILED);
        }
    }

    public Run getCurrentRun() {
        return currentRun;
    }
}
