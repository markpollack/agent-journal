package io.github.markpollack.journal.eval;

/**
 * What kind of agent behaviour an {@link EvalSubject} is, whatever it was recorded from: a tool
 * call is {@link #TOOL_CALL} whether it was read from a journal event or from a phase capture.
 * Filter by kind with {@link EvalSubjectQuery#kind(EvalSubjectKind)}, and use the constant's
 * {@code name()} as the subject kind of a
 * {@link io.github.markpollack.journal.event.FeedbackTarget}.
 *
 * <p>{@link EvalSubjectSources} makes {@link #LLM_CALL}, {@link #TOOL_CALL},
 * {@link #STATE_CHANGE} and {@link #CUSTOM} subjects. Nothing in this library makes the other
 * kinds; they are there for sources outside it.
 */
public enum EvalSubjectKind {

	/** A call to a language model, or one agent call summed over its turns. */
	LLM_CALL,
	/** A tool call made by the agent. */
	TOOL_CALL,
	/** A step of a workflow that runs the agent. Not made by this library. */
	WORKFLOW_STEP,
	/** A choice made by a router, such as which path or agent to use. Not made by this library. */
	ROUTER_DECISION,
	/** What a retrieval returned, such as documents found for a query. Not made by this library. */
	RETRIEVAL_RESULT,
	/** The final output of a run or task. Not made by this library. */
	FINAL_OUTPUT,
	/** A verdict on earlier behaviour. Not made by this library. */
	FEEDBACK,
	/** A change from one state to another, such as from one phase to the next. */
	STATE_CHANGE,
	/** A custom event, named by whoever logged it. */
	CUSTOM

}
