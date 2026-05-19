package io.github.markpollack.journal.feedback;

import io.github.markpollack.journal.event.FeedbackEvent;

import java.util.Map;

/**
 * An item paired with its human feedback — the building block for golden datasets.
 * This is a projection (read model), not a persistent entity.
 *
 * @param itemId the dataset item identifier
 * @param runId the run this feedback was recorded against
 * @param feedback the feedback event
 * @param itemMetadata run-level metadata (config + summary values)
 */
public record ReviewedItem(
		String itemId,
		String runId,
		FeedbackEvent feedback,
		Map<String, Object> itemMetadata
) {

	public ReviewedItem {
		itemMetadata = itemMetadata != null ? Map.copyOf(itemMetadata) : Map.of();
	}

}
