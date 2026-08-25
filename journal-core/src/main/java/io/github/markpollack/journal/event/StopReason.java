package io.github.markpollack.journal.event;

/**
 * Why a run (or a single model turn) stopped — the vendor-neutral normalization of the
 * provider's own stop signal.
 *
 * <p>
 * <strong>Why this exists.</strong> {@code numTurns} alone is uninterpretable: a run that
 * reports 55 turns either finished its work or was cut off at its turn ceiling, and those are
 * opposite outcomes. An absorbing-state analysis that cannot tell them apart is modelling two
 * different processes as one. So the stop reason is recorded on the production record path
 * <em>together with</em> the ceiling it was measured against (the recorder writes
 * {@code stopReason} and {@code maxTurns} as a pair — see {@code BaseRunRecorder}); either one
 * alone answers nothing.
 *
 * <p>
 * This enum is deliberately vendor-neutral and lives in {@code journal-core}: each
 * {@code <cli>-capture} adapter maps its own provider vocabulary onto these values (for Claude
 * Code, {@code ClaudeStopReasons}). Unrecognized provider values normalize to {@link #UNKNOWN}
 * rather than being dropped or guessed — a stop reason that was never reported must not be
 * indistinguishable from one that was.
 *
 * <p>
 * Unrelated to {@code TraceWriter}'s {@code truncated} flag, which is about trace <em>content</em>
 * size, not about why execution ended.
 */
public enum StopReason {

    /**
     * The model finished on its own: it had nothing further to do. Claude Code's
     * {@code end_turn} / {@code stop_sequence}, and a {@code success} run result.
     * The absorbing state a trajectory is supposed to reach.
     */
    NATURAL_DONE,

    /**
     * The turn ended because the model emitted tool calls and is waiting on their results.
     * A <em>turn-level</em> reason only — a run never terminates here. Recorded so a turn's
     * continuation is distinguishable from a turn that genuinely concluded.
     */
    TOOL_USE,

    /**
     * Execution was cut off at the configured turn ceiling ({@code maxTurns}) — the run did
     * not finish. Claude Code's {@code error_max_turns} result subtype. Runs stopped here are
     * right-censored: their step counts are lower bounds, not measurements.
     */
    MAX_TURNS,

    /**
     * The model hit its output-token ceiling mid-response. Claude Code's {@code max_tokens}.
     */
    MAX_TOKENS,

    /**
     * The model declined to continue (content refusal). Claude Code's {@code refusal}.
     */
    REFUSAL,

    /**
     * Execution ended in an error other than a ceiling — Claude Code's
     * {@code error_during_execution}, or any result flagged {@code is_error}.
     */
    ERROR,

    /**
     * Execution was cancelled or interrupted by the caller/operator rather than ending on
     * its own.
     */
    CANCELLED,

    /**
     * No stop reason was reported by the provider, or the reported value is not one this
     * version recognizes. Explicitly recorded rather than omitted, so "the provider said
     * nothing" is readable from the data instead of looking like a capture bug.
     */
    UNKNOWN;

    /**
     * Whether this reason means execution stopped short of its own conclusion — the runs an
     * analysis must treat as right-censored rather than completed.
     *
     * @return true for {@link #MAX_TURNS}, {@link #MAX_TOKENS}, {@link #ERROR} and
     *         {@link #CANCELLED}
     */
    public boolean isTruncatedRun() {
        return this == MAX_TURNS || this == MAX_TOKENS || this == ERROR || this == CANCELLED;
    }

    /**
     * Parses a previously recorded {@code StopReason} name back into the enum, tolerating null
     * and unrecognized values by returning {@link #UNKNOWN}. Used when reading the value back
     * out of a persisted event.
     *
     * @param name a recorded enum name, or null
     * @return the matching constant, or {@link #UNKNOWN}
     */
    public static StopReason fromName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        for (StopReason r : values()) {
            if (r.name().equalsIgnoreCase(name)) {
                return r;
            }
        }
        return UNKNOWN;
    }
}
