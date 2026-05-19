package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Structured feedback score — supports binary (thumbs up/down),
 * numerical (1-5 stars), and categorical (label-based).
 *
 * <p>Categorical feedback is intentionally not normalized. Consumers must
 * interpret categories in their domain context. {@code value} and {@code max}
 * are undefined for CATEGORICAL.
 *
 * @param kind score type
 * @param value numerical value (1.0/0.0 for binary, scale value for numerical)
 * @param max maximum possible value (1.0 for binary)
 * @param category category label (null unless CATEGORICAL)
 */
public record FeedbackScore(
		ScoreKind kind,
		double value,
		double max,
		String category
) {

	public enum ScoreKind {
		BINARY, NUMERICAL, CATEGORICAL
	}

	public static FeedbackScore binary(boolean positive) {
		return new FeedbackScore(ScoreKind.BINARY, positive ? 1.0 : 0.0, 1.0, null);
	}

	public static FeedbackScore numerical(double value, double max) {
		return new FeedbackScore(ScoreKind.NUMERICAL, value, max, null);
	}

	public static FeedbackScore categorical(String category) {
		return new FeedbackScore(ScoreKind.CATEGORICAL, 0.0, 0.0, category);
	}

	/**
	 * Returns a normalized score in [0.0, 1.0], or empty for categorical.
	 */
	public OptionalDouble normalized() {
		return switch (kind) {
			case BINARY -> OptionalDouble.of(value);
			case NUMERICAL -> OptionalDouble.of(max > 0 ? value / max : 0.0);
			case CATEGORICAL -> OptionalDouble.empty();
		};
	}

	public Map<String, Object> toMap() {
		var map = new LinkedHashMap<String, Object>();
		map.put("kind", kind.name());
		map.put("value", value);
		map.put("max", max);
		if (category != null) {
			map.put("category", category);
		}
		return map;
	}

}
