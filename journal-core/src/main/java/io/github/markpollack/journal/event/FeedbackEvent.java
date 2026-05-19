package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records user/human feedback on an agent output or sub-component.
 * Links feedback to a specific run and target for agreement analysis.
 *
 * <p>The {@code reviewer} field should be treated as an opaque identifier,
 * not necessarily a human-readable name or email address.
 *
 * @param timestamp when the feedback was recorded
 * @param target what is being reviewed (item, subject, or run)
 * @param score structured score (binary, numerical, or categorical)
 * @param comment free-text explanation (null if none)
 * @param reviewer opaque reviewer identifier
 * @param labels domain-specific labels
 */
public record FeedbackEvent(
		Instant timestamp,
		FeedbackTarget target,
		FeedbackScore score,
		String comment,
		String reviewer,
		Map<String, Object> labels
) implements JournalEvent {

	public FeedbackEvent {
		timestamp = timestamp != null ? timestamp : Instant.now();
		target = target != null ? target : FeedbackTarget.run();
		labels = labels != null ? Map.copyOf(labels) : Map.of();
	}

	@Override
	public String type() {
		return "feedback";
	}

	@Override
	public Map<String, Object> toMap() {
		var map = new LinkedHashMap<String, Object>();
		map.put("type", type());
		map.put("timestamp", timestamp.toString());
		map.put("target", target);
		if (score != null) {
			map.put("score", score.toMap());
		}
		if (comment != null) {
			map.put("comment", comment);
		}
		if (reviewer != null) {
			map.put("reviewer", reviewer);
		}
		if (!labels.isEmpty()) {
			map.put("labels", labels);
		}
		return map;
	}

	// --- Factory methods ---

	public static FeedbackEvent thumbsUp(String itemId, String reviewer) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.binary(true), null, reviewer, Map.of());
	}

	public static FeedbackEvent thumbsDown(String itemId, String reviewer, String reason) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.binary(false), reason, reviewer, Map.of());
	}

	public static FeedbackEvent rated(String itemId, int score, int max,
			String reviewer, String comment) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.numerical(score, max), comment, reviewer, Map.of());
	}

	public static FeedbackEvent labeled(String itemId, String reviewer,
			Map<String, Object> labels) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.binary(true), null, reviewer, labels);
	}

}
