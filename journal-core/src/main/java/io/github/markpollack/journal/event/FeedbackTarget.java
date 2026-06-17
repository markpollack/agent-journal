package io.github.markpollack.journal.event;

/**
 * Identifies the target of a feedback event. Feedback can target an item
 * (dataset-level), a specific subject within the item (tool call, LLM response,
 * retrieval result), or just the run itself.
 *
 * <p>{@code subjectKind} is a {@code String}, not {@code EvalSubjectKind}, to
 * avoid coupling the core event model to the extraction layer. By convention,
 * use {@code EvalSubjectKind.name()} values (e.g., "TOOL_CALL", "LLM_CALL")
 * when targeting EvalSubjects.
 *
 * @param itemId dataset item identifier (null for run-level feedback)
 * @param subjectId stable step/subject id — the EvalSubject id, which now derives from the
 *                  vendor tool_use id (tool calls) or response id (LLM calls) rather than a
 *                  reload-order-dependent list position (R2.3); null for item-level targets
 * @param subjectKind kind string, e.g. "TOOL_CALL", "LLM_CALL" (null for item-level)
 */
public record FeedbackTarget(
		String itemId,
		String subjectId,
		String subjectKind
) {

	/** Item-level feedback (most common). */
	public static FeedbackTarget item(String itemId) {
		return new FeedbackTarget(itemId, null, null);
	}

	/** Sub-item feedback targeting a specific subject. */
	public static FeedbackTarget subject(String itemId, String subjectId, String subjectKind) {
		return new FeedbackTarget(itemId, subjectId, subjectKind);
	}

	/** Run-level feedback. The runId is supplied by FeedbackService. */
	public static FeedbackTarget run() {
		return new FeedbackTarget(null, null, null);
	}

}
