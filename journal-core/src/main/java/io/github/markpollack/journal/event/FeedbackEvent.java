package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a verdict on part of a run: a {@link FeedbackScore} that a reviewer gives to a
 * {@link FeedbackTarget} (a dataset item, one step such as a tool call, or the whole run), with
 * an optional comment and labels. The reviewer may be a person or an automated judge. Record one
 * with {@link io.github.markpollack.journal.feedback.FeedbackService#recordFeedback}, built with a
 * factory method such as {@link #thumbsUp(String, String)} or with the constructor; file storage
 * appends it to the run's {@code feedback.jsonl}, not to {@code events.jsonl}. Read it back with
 * {@link io.github.markpollack.journal.feedback.FeedbackService#getFeedback(String, String)};
 * {@link io.github.markpollack.journal.feedback.FeedbackService#computeJudgeAgreement} compares
 * the judges' scores with a human reviewer's.
 *
 * <p>It also implements {@link JournalEvent}, with {@code @type} {@code "feedback"}, so it can be
 * logged as an ordinary event, but the feedback service does not read feedback from the event
 * log.
 *
 * <p>The constructor fills in defaults: a {@code null} timestamp becomes the current time, a
 * {@code null} target becomes {@link FeedbackTarget#run()}, and {@code null} labels become an
 * empty map. Labels are copied into an unmodifiable map, which rejects {@code null} keys and
 * values. The score may be {@code null}; judge agreement ignores feedback without a score or a
 * reviewer. Treat the reviewer as an opaque ID, not as a display name or email address.
 *
 * @param timestamp when the feedback was given
 * @param target what the feedback is about
 * @param score the verdict, or {@code null} if there is none
 * @param comment a free-text explanation, or {@code null} if there is none
 * @param reviewer the ID of the person or judge who gave the feedback
 * @param labels extra labels chosen by the caller, keyed by name
 */
public record FeedbackEvent(
		Instant timestamp,
		FeedbackTarget target,
		FeedbackScore score,
		String comment,
		String reviewer,
		Map<String, Object> labels
) implements JournalEvent {

	/**
	 * Creates a feedback event, filling in defaults for a {@code null} timestamp, target or
	 * labels, as described in the class description.
	 *
	 * @param timestamp when the feedback was given
	 * @param target what the feedback is about
	 * @param score the verdict
	 * @param comment a free-text explanation
	 * @param reviewer the reviewer's ID
	 * @param labels extra labels
	 * @throws NullPointerException if {@code labels} holds a {@code null} key or value
	 */
	public FeedbackEvent {
		timestamp = timestamp != null ? timestamp : Instant.now();
		target = target != null ? target : FeedbackTarget.run();
		labels = labels != null ? Map.copyOf(labels) : Map.of();
	}

	@Override
	public String type() {
		return "feedback";
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The keys are {@code type}, {@code timestamp} and {@code target} (the
	 * {@link FeedbackTarget} object itself, not a map), then {@code score} (a map),
	 * {@code comment}, {@code reviewer} and {@code labels} when they are set.
	 */
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

	/**
	 * Creates a positive binary verdict on a dataset item, with the current time and no comment
	 * or labels.
	 *
	 * @param itemId the item's ID
	 * @param reviewer the reviewer's ID
	 * @return the new feedback
	 */
	public static FeedbackEvent thumbsUp(String itemId, String reviewer) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.binary(true), null, reviewer, Map.of());
	}

	/**
	 * Creates a negative binary verdict on a dataset item, with the current time and the reason
	 * as its comment.
	 *
	 * @param itemId the item's ID
	 * @param reviewer the reviewer's ID
	 * @param reason why the item is wrong, or {@code null}
	 * @return the new feedback
	 */
	public static FeedbackEvent thumbsDown(String itemId, String reviewer, String reason) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.binary(false), reason, reviewer, Map.of());
	}

	/**
	 * Creates a numerical verdict on a dataset item, such as 4 out of 5, with the current time.
	 *
	 * @param itemId the item's ID
	 * @param score the score
	 * @param max the highest possible score
	 * @param reviewer the reviewer's ID
	 * @param comment a free-text explanation, or {@code null}
	 * @return the new feedback
	 */
	public static FeedbackEvent rated(String itemId, int score, int max,
			String reviewer, String comment) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.numerical(score, max), comment, reviewer, Map.of());
	}

	/**
	 * Creates feedback that attaches labels to a dataset item, with the current time and no
	 * comment.
	 *
	 * @param itemId the item's ID
	 * @param reviewer the reviewer's ID
	 * @param labels the labels, keyed by name
	 * @return the new feedback
	 * @throws NullPointerException if {@code labels} holds a {@code null} key or value
	 */
	public static FeedbackEvent labeled(String itemId, String reviewer,
			Map<String, Object> labels) {
		return new FeedbackEvent(Instant.now(), FeedbackTarget.item(itemId),
				FeedbackScore.binary(true), null, reviewer, labels);
	}

}
