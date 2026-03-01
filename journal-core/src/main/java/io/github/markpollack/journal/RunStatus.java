package io.github.markpollack.journal;

/**
 * Run lifecycle status.
 *
 * <p>Follows W&B's proven lifecycle model:
 * {@code INIT → RUNNING → FINISHED/FAILED/CRASHED}
 */
public enum RunStatus {
    /** Run created, config can be set. */
    INIT,

    /** Active execution, events being logged. */
    RUNNING,

    /** Successful completion. */
    FINISHED,

    /** Exception during execution. */
    FAILED,

    /** Abnormal termination. */
    CRASHED;

    /** Returns true if this is a terminal status. */
    public boolean isTerminal() {
        return this == FINISHED || this == FAILED || this == CRASHED;
    }

    /** Returns true if this represents a successful completion. */
    public boolean isSuccessful() {
        return this == FINISHED;
    }
}
