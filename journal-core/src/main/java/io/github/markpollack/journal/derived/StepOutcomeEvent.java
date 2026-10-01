package io.github.markpollack.journal.derived;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outcome values that the caller measured for one step of a run, or for the whole run, as a
 * {@link DerivedEvent}: for example the exit code of a command, a test result or the change in
 * coverage. The library never creates these itself and gives the values no meaning; the code
 * that judges the work decides what to record. Make one with
 * {@link #of(String, String, Map)} or {@link #withGoalDistance(String, String, double, Map)},
 * log it with {@link io.github.markpollack.journal.Run#logDerivedEvent(DerivedEvent)}, and match
 * it to the step's {@link StepCostEvent} or tool call event by {@link #stepId()}.
 *
 * <p>{@link #goalDistance()} is the one typed value: a number the caller defines for how far the
 * work still is from its goal. Everything else goes in {@link #metrics()}, a map of names to
 * values. Its keys and values must not be {@code null}, and on file storage the values should be
 * plain JSON values (strings, numbers, booleans, lists and maps); they come back as JSON types,
 * for example whole numbers as {@code Integer}.
 *
 * @param timestamp when the outcome was recorded; must not be {@code null}
 * @param runId the ID of the run
 * @param stepId the ID of the step, or {@code null} for an outcome of the whole run
 * @param turnId the ID of the model turn the step belongs to, or {@code null}
 * @param goalDistance how far the work is from its goal, in the caller's own measure, or
 *        {@code null} if not given
 * @param metrics the other outcome values by name; an immutable copy, empty if none were given
 */
public record StepOutcomeEvent(
        Instant timestamp,
        String runId,
        String stepId,
        String turnId,
        Double goalDistance,
        Map<String, Object> metrics
) implements DerivedEvent {

    /** The type name of this event, {@value}, used as its {@code @type} name in JSON. */
    public static final String TYPE = "step_outcome";

    /**
     * Creates an outcome event, copying the metrics.
     *
     * @param timestamp when the outcome was recorded; must not be {@code null}
     * @param runId the run ID
     * @param stepId the step ID, or {@code null} for the whole run
     * @param turnId the turn ID, or {@code null}
     * @param goalDistance the distance to the goal, or {@code null}
     * @param metrics the outcome values, or {@code null} for none
     * @throws NullPointerException if {@code metrics} holds a {@code null} key or value
     */
    public StepOutcomeEvent {
        metrics = metrics != null ? Map.copyOf(metrics) : Map.of();
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * Creates an outcome event with the current time, no turn ID and no goal distance.
     *
     * @param runId the run ID
     * @param stepId the step ID, or {@code null} for the whole run
     * @param metrics the outcome values, such as {@code Map.of("exit_code", 0)}, or {@code null}
     *        for none
     * @return the outcome event
     * @throws NullPointerException if {@code metrics} holds a {@code null} key or value
     */
    public static StepOutcomeEvent of(String runId, String stepId, Map<String, Object> metrics) {
        return new StepOutcomeEvent(Instant.now(), runId, stepId, null, null, metrics);
    }

    /**
     * Creates an outcome event with a goal distance, the current time and no turn ID.
     *
     * @param runId the run ID
     * @param stepId the step ID, or {@code null} for the whole run
     * @param goalDistance how far the work is from its goal, in the caller's own measure
     * @param metrics the other outcome values, or {@code null} for none
     * @return the outcome event
     * @throws NullPointerException if {@code metrics} holds a {@code null} key or value
     */
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
