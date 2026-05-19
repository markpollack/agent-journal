package io.github.markpollack.journal.eval;

/**
 * Classifies what type of agent behavior an {@link EvalSubject} represents.
 *
 * <p>The kind describes the nature of the recorded behavior, independent of
 * which data source it came from. A {@code TOOL_CALL} subject might originate
 * from a Journal event, a PhaseCapture record, or a StepTransition — the kind
 * is the same regardless of provenance.
 */
public enum EvalSubjectKind {

	LLM_CALL,
	TOOL_CALL,
	WORKFLOW_STEP,
	ROUTER_DECISION,
	RETRIEVAL_RESULT,
	FINAL_OUTPUT,
	FEEDBACK,
	STATE_CHANGE,
	CUSTOM

}
