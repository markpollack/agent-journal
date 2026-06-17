package io.github.markpollack.journal.derived;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derived per-step cost — the Path-A (journal events) analog of the trace's {@code step_cost}
 * line (R2.4). The first {@link DerivedEvent}.
 *
 * <p>
 * Ground truth and allocation are kept distinct (the R2.3 decision): {@code actualRunCostUsd} is
 * the run's true total (identical across a run's steps); {@code attributedCostUsd} is this step's
 * fair share under {@code attributionMethod}. Cost is inferred after the run — that's why this is
 * a derived event in {@code analysis.jsonl}, not an execution event in {@code events.jsonl}.
 * Joined to the execution {@code ToolCallEvent} by {@code stepId} (both are the tool_use id).
 *
 * @param timestamp         when the attribution was computed (post-run)
 * @param runId             join key — the run
 * @param stepId            join key — the execution step (tool_use id); the turn id for tool-less turns
 * @param turnId            the model turn (assistant message id) this step belongs to
 * @param toolName          the tool invoked, or null for a tool-less turn step
 * @param inputTokens       the turn's input tokens
 * @param outputTokens      the turn's output tokens
 * @param attributedCostUsd this step's fair share of the run cost
 * @param actualRunCostUsd  the run's true total cost (ground truth)
 * @param attributionMethod how {@code attributedCostUsd} was derived
 * @param vendor            capture vendor (e.g. {@code claude-code}, {@code gemini-cli})
 */
public record StepCostEvent(
        Instant timestamp,
        String runId,
        String stepId,
        String turnId,
        String toolName,
        long inputTokens,
        long outputTokens,
        double attributedCostUsd,
        double actualRunCostUsd,
        AttributionMethod attributionMethod,
        String vendor
) implements DerivedEvent {

    public static final String TYPE = "step_cost";

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * Bridges a portable {@link JournalStep} (produced capture-side by {@code JournalSteps} /
     * {@code GeminiJournalSteps}) into a stored derived event. {@code timestamp} is the analysis
     * time — when the cost was attributed, not when the step ran.
     */
    public static StepCostEvent fromStep(JournalStep step, Instant timestamp) {
        return new StepCostEvent(timestamp, step.runId(), step.stepId(), step.turnId(), step.toolName(),
                step.inputTokens(), step.outputTokens(), step.attributedCostUsd(), step.actualRunCostUsd(),
                step.attributionMethod(), step.vendor());
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("@type", TYPE);
        map.put("timestamp", timestamp.toString());
        map.put("runId", runId);
        if (stepId != null) {
            map.put("stepId", stepId);
        }
        if (turnId != null) {
            map.put("turnId", turnId);
        }
        if (toolName != null) {
            map.put("toolName", toolName);
        }
        map.put("inputTokens", inputTokens);
        map.put("outputTokens", outputTokens);
        map.put("attributedCostUsd", attributedCostUsd);
        map.put("actualRunCostUsd", actualRunCostUsd);
        if (attributionMethod != null) {
            map.put("attributionMethod", attributionMethod.name());
        }
        if (vendor != null) {
            map.put("vendor", vendor);
        }
        return map;
    }
}
