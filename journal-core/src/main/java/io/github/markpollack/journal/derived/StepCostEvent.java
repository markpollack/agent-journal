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
 * @param thinkingTokens    the turn's extended-thinking tokens — a subset of {@code outputTokens},
 *                          never additive to a billed total
 * @param cacheCreationTokens the turn's tokens written to the prompt cache
 * @param cacheReadTokens   the turn's tokens read from the prompt cache
 * @param turnIndex         0-based ordinal of this step's turn, or -1 when unknown
 * @param durationMs        observed step duration in milliseconds, or -1 when unknown
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
        String vendor,
        long thinkingTokens,
        long cacheCreationTokens,
        long cacheReadTokens,
        int turnIndex,
        long durationMs
) implements DerivedEvent {

    public static final String TYPE = "step_cost";

    /**
     * Back-compat constructor for events written before the full per-turn token vector, turn
     * ordinal and step duration were carried (1.9.0). Reading an older {@code analysis.jsonl}
     * through this constructor yields "not captured" defaults rather than fabricated zeros
     * masquerading as measurements — {@code turnIndex} and {@code durationMs} are -1.
     */
    public StepCostEvent(Instant timestamp, String runId, String stepId, String turnId, String toolName,
            long inputTokens, long outputTokens, double attributedCostUsd, double actualRunCostUsd,
            AttributionMethod attributionMethod, String vendor) {
        this(timestamp, runId, stepId, turnId, toolName, inputTokens, outputTokens, attributedCostUsd,
                actualRunCostUsd, attributionMethod, vendor, 0L, 0L, 0L, -1, -1L);
    }

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
                step.attributionMethod(), step.vendor(), step.thinkingTokens(), step.cacheCreationTokens(),
                step.cacheReadTokens(), step.turnIndex(), step.durationMs());
    }

    /**
     * Deserialization entry point, present so a <strong>pre-1.9.0 {@code analysis.jsonl} reads back
     * honestly</strong>. Those records carry neither {@code turnIndex} nor {@code durationMs};
     * Jackson's defaults for the primitives would resolve them to {@code 0}, which reads as "the
     * first turn" and "took no time" rather than "never captured". Both become -1 when absent.
     * Token fields legitimately default to 0 — a token count that was not recorded and one that
     * was zero are the same claim about volume.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    static StepCostEvent fromJson(
            @com.fasterxml.jackson.annotation.JsonProperty("timestamp") Instant timestamp,
            @com.fasterxml.jackson.annotation.JsonProperty("runId") String runId,
            @com.fasterxml.jackson.annotation.JsonProperty("stepId") String stepId,
            @com.fasterxml.jackson.annotation.JsonProperty("turnId") String turnId,
            @com.fasterxml.jackson.annotation.JsonProperty("toolName") String toolName,
            @com.fasterxml.jackson.annotation.JsonProperty("inputTokens") long inputTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("outputTokens") long outputTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("attributedCostUsd") double attributedCostUsd,
            @com.fasterxml.jackson.annotation.JsonProperty("actualRunCostUsd") double actualRunCostUsd,
            @com.fasterxml.jackson.annotation.JsonProperty("attributionMethod") AttributionMethod attributionMethod,
            @com.fasterxml.jackson.annotation.JsonProperty("vendor") String vendor,
            @com.fasterxml.jackson.annotation.JsonProperty("thinkingTokens") long thinkingTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("cacheCreationTokens") long cacheCreationTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("cacheReadTokens") long cacheReadTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("turnIndex") Integer turnIndex,
            @com.fasterxml.jackson.annotation.JsonProperty("durationMs") Long durationMs) {
        return new StepCostEvent(timestamp, runId, stepId, turnId, toolName, inputTokens, outputTokens,
                attributedCostUsd, actualRunCostUsd, attributionMethod, vendor, thinkingTokens,
                cacheCreationTokens, cacheReadTokens, turnIndex != null ? turnIndex : -1,
                durationMs != null ? durationMs : -1L);
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
        map.put("thinkingTokens", thinkingTokens);
        map.put("cacheCreationTokens", cacheCreationTokens);
        map.put("cacheReadTokens", cacheReadTokens);
        map.put("turnIndex", turnIndex);
        map.put("durationMs", durationMs);
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
