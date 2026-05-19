package io.github.markpollack.journal.eval;

import java.util.Map;
import java.util.Optional;

/**
 * A source-neutral unit of recorded agent behavior that can be judged.
 *
 * <p>Can be derived from any execution data source (Journal events, PhaseCapture,
 * StepTransitions, feedback events, etc.) but presents a uniform shape to evaluation
 * machinery.
 *
 * @param id unique identifier (deterministic within a source)
 * @param kind what type of behavior this represents
 * @param source provenance: "journal", "phase_capture", "step_transition"
 * @param runId which run this came from
 * @param itemId which dataset item (nullable)
 * @param goal what was being attempted
 * @param input input to the behavior (tool params, prompt, etc.)
 * @param output output of the behavior (result, response, etc.)
 * @param metadata kind-specific attributes
 */
public record EvalSubject(
		String id,
		EvalSubjectKind kind,
		String source,
		String runId,
		String itemId,
		String goal,
		Object input,
		Object output,
		Map<String, Object> metadata
) {

	public Optional<Double> costUsd() {
		Object value = metadata.get("costUsd");
		return value instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
	}

	public Optional<Long> durationMs() {
		Object value = metadata.get("durationMs");
		return value instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
	}

	public Optional<Boolean> success() {
		Object value = metadata.get("success");
		return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
	}

	public boolean failed() {
		return success().map(s -> !s).orElse(false);
	}

	public Optional<String> itemIdOpt() {
		return Optional.ofNullable(itemId);
	}

}
