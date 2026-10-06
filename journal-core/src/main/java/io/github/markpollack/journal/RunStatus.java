package io.github.markpollack.journal;

/**
 * Where a {@link Run} is in its life: still running, or ended and how. Read it from
 * {@link Run#status()} while the run is open, or from the stored run record
 * ({@link io.github.markpollack.journal.storage.RunData}) afterwards. {@link #FINISHED},
 * {@link #FAILED} and {@link #CRASHED} are terminal: once a run has one, it accepts no more events
 * or summary values.
 *
 * <p>The library sets three of the five values itself. {@link RunBuilder#start()} gives a new run
 * {@link #RUNNING}; {@link Run#close()} gives {@link #FINISHED} unless the run has already ended;
 * {@link Run#fail(Throwable)} gives {@link #FAILED}. It never sets {@link #INIT} on a run, and it
 * sets {@link #CRASHED} only when a caller passes it to {@link Run#finish(RunStatus)}. If the
 * process dies before the run ends, nothing marks it: the stored record keeps {@link #RUNNING}.
 */
public enum RunStatus {
    /**
     * Not started. No run started by {@link RunBuilder#start()} has this status; it is the
     * default of {@link io.github.markpollack.journal.storage.RunData#builder()}.
     */
    INIT,

    /** Started and not yet ended; the status of every new run. */
    RUNNING,

    /**
     * Ended normally, set by {@link Run#close()} or {@code finish(FINISHED)}. It says the run
     * completed, not that the agent reached its goal; for that, read the {@code success} value of
     * the {@link Summary}.
     */
    FINISHED,

    /** Ended by {@link Run#fail(Throwable)}, which also records the error in the summary. */
    FAILED,

    /**
     * Ended abnormally. A caller that detects a crash, such as a lost agent process, passes it to
     * {@link Run#finish(RunStatus)}. {@link Run#close()} never chooses this value; Claude Code's
     * {@code RunRecorder} does, for a recorder closed before its {@code finish()} was called.
     */
    CRASHED;

    /**
     * Returns whether this status ends a run: {@link #FINISHED}, {@link #FAILED} or
     * {@link #CRASHED}.
     *
     * @return {@code true} for a terminal status, {@code false} for {@link #INIT} and
     *         {@link #RUNNING}
     */
    public boolean isTerminal() {
        return this == FINISHED || this == FAILED || this == CRASHED;
    }

    /**
     * Returns whether this status is {@link #FINISHED}.
     *
     * @return {@code true} only for {@link #FINISHED}
     */
    public boolean isSuccessful() {
        return this == FINISHED;
    }
}
