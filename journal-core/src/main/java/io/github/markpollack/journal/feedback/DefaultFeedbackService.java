package io.github.markpollack.journal.feedback;

import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.FeedbackTarget;
import io.github.markpollack.journal.storage.JournalStorage;
import io.github.markpollack.journal.storage.RunData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Default implementation of {@link FeedbackService} backed by {@link JournalStorage}.
 *
 * <p>Delegates feedback persistence to the storage backend's
 * {@code appendFeedback}/{@code loadFeedback} methods, which use a separate
 * {@code feedback.jsonl} sidecar file alongside the execution event log.
 */
public class DefaultFeedbackService implements FeedbackService {

	private final JournalStorage storage;

	public DefaultFeedbackService(JournalStorage storage) {
		this.storage = storage;
	}

	@Override
	public void recordFeedback(String experimentId, String runId, FeedbackEvent feedback) {
		storage.appendFeedback(experimentId, runId, feedback);
	}

	@Override
	public List<FeedbackEvent> getFeedback(String experimentId, String runId) {
		return storage.loadFeedback(experimentId, runId);
	}

	@Override
	public List<FeedbackEvent> getFeedbackForItem(String experimentId, String itemId) {
		List<FeedbackEvent> result = new ArrayList<>();
		for (RunData run : storage.listRuns(experimentId)) {
			for (FeedbackEvent fb : storage.loadFeedback(experimentId, run.id())) {
				if (fb.target() != null && itemId.equals(fb.target().itemId())) {
					result.add(fb);
				}
			}
		}
		return result;
	}

	@Override
	public Map<String, Double> computeJudgeAgreement(String experimentId, String runId,
			String humanReviewer, double threshold) {
		// Reduce each reviewer to its latest normalized score per target (append order → last wins).
		Map<String, Map<String, Double>> scoresByReviewer = new LinkedHashMap<>();
		for (FeedbackEvent fb : storage.loadFeedback(experimentId, runId)) {
			if (fb.reviewer() == null || fb.score() == null) {
				continue;
			}
			OptionalDouble normalized = fb.score().normalized();
			if (normalized.isEmpty()) {
				continue; // categorical-only feedback is not comparable
			}
			scoresByReviewer.computeIfAbsent(fb.reviewer(), k -> new LinkedHashMap<>())
					.put(targetKey(fb.target()), normalized.getAsDouble());
		}

		Map<String, Double> agreement = new LinkedHashMap<>();
		Map<String, Double> humanScores = scoresByReviewer.get(humanReviewer);
		if (humanScores == null || humanScores.isEmpty()) {
			return agreement; // no human baseline → nothing to agree with
		}

		for (Map.Entry<String, Map<String, Double>> entry : scoresByReviewer.entrySet()) {
			String reviewer = entry.getKey();
			if (reviewer.equals(humanReviewer)) {
				continue;
			}
			int shared = 0;
			int agree = 0;
			for (Map.Entry<String, Double> judged : entry.getValue().entrySet()) {
				Double humanScore = humanScores.get(judged.getKey());
				if (humanScore == null) {
					continue; // only targets both scored
				}
				shared++;
				boolean humanPass = humanScore >= threshold;
				boolean judgePass = judged.getValue() >= threshold;
				if (humanPass == judgePass) {
					agree++;
				}
			}
			if (shared > 0) {
				agreement.put(reviewer, (double) agree / shared);
			}
		}
		return agreement;
	}

	/**
	 * Stable identity for a feedback target: the {@code subjectId} (step grain, R2.3) when present,
	 * else the {@code itemId}, else run-level. Human and judge feedback on the "same target" must
	 * key identically here.
	 */
	private static String targetKey(FeedbackTarget target) {
		if (target == null) {
			return "run";
		}
		if (target.subjectId() != null) {
			return "subject:" + target.subjectId();
		}
		if (target.itemId() != null) {
			return "item:" + target.itemId();
		}
		return "run";
	}

	@Override
	public List<ReviewedItem> exportReviewedItems(String experimentId) {
		List<ReviewedItem> items = new ArrayList<>();
		for (RunData run : storage.listRuns(experimentId)) {
			Map<String, Object> metadata = buildRunMetadata(run);
			for (FeedbackEvent fb : storage.loadFeedback(experimentId, run.id())) {
				String itemId = fb.target() != null ? fb.target().itemId() : null;
				if (itemId != null) {
					items.add(new ReviewedItem(itemId, run.id(), fb, metadata));
				}
			}
		}
		return items;
	}

	private Map<String, Object> buildRunMetadata(RunData run) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("runId", run.id());
		metadata.put("experimentId", run.experimentId());
		if (run.name() != null) {
			metadata.put("runName", run.name());
		}
		if (run.status() != null) {
			metadata.put("status", run.status().name());
		}
		if (run.config() != null && !run.config().isEmpty()) {
			metadata.putAll(run.config().values());
		}
		if (run.summary() != null && !run.summary().isEmpty()) {
			metadata.putAll(run.summary().values());
		}
		return metadata;
	}

}
