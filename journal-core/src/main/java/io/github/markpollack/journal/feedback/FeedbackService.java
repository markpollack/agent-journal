package io.github.markpollack.journal.feedback;

import io.github.markpollack.journal.event.FeedbackEvent;

import java.util.List;
import java.util.Map;

/**
 * Records and queries human feedback on experiment results.
 *
 * <p>Feedback is stored in a separate append-only feedback log alongside the
 * immutable execution event stream. The execution log remains unchanged after
 * the run completes; feedback can be added later as users or reviewers inspect
 * the results.
 *
 * <pre>
 * .agent-journal/experiments/{experimentId}/runs/{runId}/
 *   ├── events.jsonl      &larr; immutable execution log
 *   └── feedback.jsonl    &larr; append-only human feedback log
 * </pre>
 */
public interface FeedbackService {

	/**
	 * Record feedback for a specific run. Post-hoc — does NOT require an open Run.
	 * @param experimentId the experiment ID
	 * @param runId the run ID
	 * @param feedback the feedback event
	 */
	void recordFeedback(String experimentId, String runId, FeedbackEvent feedback);

	/**
	 * Load all feedback for a run.
	 * @param experimentId the experiment ID
	 * @param runId the run ID
	 * @return feedback events in chronological order
	 */
	List<FeedbackEvent> getFeedback(String experimentId, String runId);

	/**
	 * Load feedback for a specific item across all runs in an experiment.
	 * @param experimentId the experiment ID
	 * @param itemId the dataset item ID
	 * @return feedback events targeting this item
	 */
	List<FeedbackEvent> getFeedbackForItem(String experimentId, String itemId);

	/**
	 * Compute agreement between a human reviewer's feedback and each automated judge's scores,
	 * over targets both scored (the same {@code FeedbackTarget}, keyed by {@code subjectId} when
	 * present, else {@code itemId}).
	 *
	 * <p>For each shared target, both sides are reduced to a binary pass/fail via
	 * {@code normalized() >= threshold}; agreement for a judge is the fraction of shared targets
	 * where the human and judge pass/fail verdicts match. Only feedback whose
	 * {@code FeedbackScore.normalized()} is present is considered (categorical-only is skipped);
	 * when a reviewer scores the same target more than once, the latest wins. Judges with no
	 * shared target are omitted.
	 *
	 * @param experimentId the experiment ID
	 * @param runId the run ID
	 * @param humanReviewer the reviewer id treated as the human baseline
	 * @param threshold score threshold for binary pass/fail comparison
	 * @return map of judge reviewer id to agreement rate [0.0, 1.0]; empty if the human gave no
	 *         usable feedback
	 */
	Map<String, Double> computeJudgeAgreement(String experimentId, String runId,
			String humanReviewer, double threshold);

	/**
	 * Export reviewed items for golden dataset creation.
	 *
	 * <p>Produces one {@link ReviewedItem} per feedback event that has an item ID.
	 *
	 * @param experimentId the experiment ID
	 * @return reviewed items with feedback and run metadata
	 */
	List<ReviewedItem> exportReviewedItems(String experimentId);

}
