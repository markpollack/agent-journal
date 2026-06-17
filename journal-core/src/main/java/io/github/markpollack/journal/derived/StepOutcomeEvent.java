package io.github.markpollack.journal.derived;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A generic per-step outcome / goal-distance channel — the second {@link DerivedEvent} (R2.6).
 *
 * <p>
 * The ACT runtime Lyapunov needs a per-step {@code goalDistance_t} plus outcome signals
 * ({@code exit_code}, {@code test_result}, {@code coverage_delta}, {@code files_touched}, …),
 * but those are <strong>domain-specific</strong>. So journal provides only the <em>channel</em>,
 * keyed to a step; the <strong>experiment supplies the values</strong>. No domain semantics are
 * baked in here — {@code goalDistance} is the one generic typed slot the analysis layer reads
 * directly, and {@code metrics} is a free-form bag for everything else.
 *
 * <p>
 * Like all derived events this is inferred/measured after the step and lives in
 * {@code analysis.jsonl}, joined to the execution step by {@code stepId}.
 *
 * @param timestamp    when the outcome was recorded
 * @param runId        join key — the run
 * @param stepId       join key — the execution step (tool_use id); null for run-level
 * @param turnId       the model turn this step belongs to (optional)
 * @param goalDistance generic distance-to-goal the experiment supplies (null if not provided)
 * @param metrics      free-form experiment-supplied outcome signals (never empty-null; no domain semantics)
 */
public record StepOutcomeEvent(
        Instant timestamp,
        String runId,
        String stepId,
        String turnId,
        Double goalDistance,
        Map<String, Object> metrics
) implements DerivedEvent {

    public static final String TYPE = "step_outcome";

    public StepOutcomeEvent {
        metrics = metrics != null ? Map.copyOf(metrics) : Map.of();
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** Minimal channel: outcome metrics for a step (timestamp now, no goal distance). */
    public static StepOutcomeEvent of(String runId, String stepId, Map<String, Object> metrics) {
        return new StepOutcomeEvent(Instant.now(), runId, stepId, null, null, metrics);
    }

    /** Channel with an explicit goal-distance signal for the ACT runtime. */
    public static StepOutcomeEvent withGoalDistance(String runId, String stepId, double goalDistance,
            Map<String, Object> metrics) {
        return new StepOutcomeEvent(Instant.now(), runId, stepId, null, goalDistance, metrics);
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
        if (goalDistance != null) {
            map.put("goalDistance", goalDistance);
        }
        if (!metrics.isEmpty()) {
            map.put("metrics", metrics);
        }
        return map;
    }
}
