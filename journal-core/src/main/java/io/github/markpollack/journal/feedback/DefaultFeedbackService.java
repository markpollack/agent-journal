package io.github.markpollack.journal.feedback;

import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.storage.JournalStorage;
import io.github.markpollack.journal.storage.RunData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
			double threshold) {
		throw new UnsupportedOperationException(
				"Judge agreement requires judge scores — not yet available in journal-core");
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
