package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StateChangeEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
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
     * Count of derived {@link StepCostEvent}s emitted across all {@link #recordPhase} calls. The
     * production recorder reads this to fail loud when derived events were produced but the storage
     * backend can't durably persist them (DESIGN §4 fail-loud contract).
     */
    protected int derivedEventsEmitted = 0;

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

        // LLM call with full token/cost/timing data. The per-turn breakdown rides as additive
        // metadata (JournalSteps.META_TURNS) — raw wire data, not derived — so the immutable log
        // alone regenerates per-step cost offline via JournalSteps.fromEvents (DESIGN §2.2 closure).
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("phaseName", phase.phaseName());
        if (phase.sessionId() != null) {
            metadata.put("sessionId", phase.sessionId());
        }
        metadata.put("numTurns", phase.numTurns());
        metadata.put("isError", phase.isError());
        if (phase.hasTurns()) {
            metadata.put(JournalSteps.META_TURNS, JournalSteps.turnsToMetadata(phase.turns()));
        }

        // Headline token vector = the cost-bearing per-type aggregate (Σ per-turn by type, incl.
        // cache), NOT the final-snapshot scalars (CM). The snapshot under-counts on long runs and
        // dropped cache entirely; aggregateUsage() reconciles to totalCostUsd. Same field, corrected
        // value — additive on §4. (No-turns captures fall back to the snapshot vector, now with cache.)
        currentRun.logEvent(LLMCallEvent.builder()
                .model(currentRun.config().getOrDefault("model", "unknown"))
                .tokenUsage(phase.aggregateUsage())
                .cost(CostBreakdown.of(phase.totalCostUsd()))
                .timing(TimingInfo.of(phase.durationMs(), phase.apiDurationMs()))
                .metadata(metadata)
                .build());

        // Tool call events — carry the vendor tool_use id as the stable step identity
        // (R2.3) so feedback/eval can target a step without depending on reload order. The
        // tool_result error bit is recorded as success/failure so isError round-trips through the
        // execution stream (so fromEvents matches fromPhaseCapture on the error flag).
        if (phase.hasToolUses()) {
            Map<String, ToolResultRecord> resultsById = new LinkedHashMap<>();
            if (phase.toolResults() != null) {
                for (ToolResultRecord tr : phase.toolResults()) {
                    resultsById.put(tr.toolUseId(), tr);
                }
            }
            for (ToolUseRecord toolUse : phase.toolUses()) {
                ToolResultRecord result = resultsById.get(toolUse.id());
                boolean isError = result != null && result.isError();
                currentRun.logEvent(ToolCallEvent.builder()
                        .id(toolUse.id())
                        .toolName(toolUse.name())
                        .kind(toolUse.kind())
                        .input(toolUse.input())
                        .success(!isError)
                        .errorMessage(isError ? result.content() : null)
                        .build());
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

        // Derived per-step cost → analysis.jsonl (R2.10 emission). The run reports only a
        // total cost; JournalSteps attributes a fair share per step (joined to the execution
        // ToolCallEvent above by the shared tool_use id). This is inferred interpretation, so it
        // is a DerivedEvent in the analysis log — never mixed into the execution event stream.
        Instant analyzedAt = Instant.now();
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, currentRun.id());
        for (JournalStep step : steps) {
            currentRun.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
        }
        derivedEventsEmitted += steps.size();
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
