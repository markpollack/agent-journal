package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.RunBuilder;

import java.util.Objects;
import java.util.Optional;

/**
 * Finds whether a captured execution has already been recorded in an experiment, so that
 * processing the same source twice does not write a second set of runs. A recorder that records
 * an execution once writes the execution's source key into the parent run's config under
 * {@link #CONFIG_SOURCE_KEY}, with the kind of source under {@link #CONFIG_SOURCE_KIND}; before
 * recording, it asks {@link #find} for an earlier run with the same kind and key.
 *
 * <p>The check reads the run records in {@link Journal#storage()}, so it survives a restart: a
 * run written by an earlier process is found. It covers one experiment in one storage; the same
 * source recorded into another experiment or another storage root is not found. It is not a
 * lock: two processes recording the same source at the same time can both miss the other's run.
 * A sub-agent's own run never carries a source key; its parent's run does, and the parent's
 * recording writes the sub-agent runs, so one check covers the set.
 *
 * <p>What the key is depends on the capture module: Claude Code's recorder uses the session ID
 * and the ID of the final assistant message, Codex's the root thread ID. A key is the same only
 * for the same execution, so a different execution of the same session (a later prompt in a
 * Claude Code session, which has another final message) records normally. Every call reads every
 * run record of the experiment, so a run record that cannot be read makes the call fail with that
 * error rather than record. Codex sessions that
 * are resumed keep their thread ID, so a resumed Codex thread is found as already recorded; resume
 * is not supported for Codex sub-agent capture.
 */
public final class SourceRecordings {

    /** The config key, on a parent run, of the execution's source key. */
    public static final String CONFIG_SOURCE_KEY = "capture.sourceKey";

    /** The config key, on a parent run, of the kind of source, such as {@code "claude-code"}. */
    public static final String CONFIG_SOURCE_KIND = "capture.sourceKind";

    private SourceRecordings() {
    }

    /**
     * Returns the run that recorded the given source, if one exists in the experiment.
     *
     * @param experimentId the experiment
     * @param sourceKind the kind of source, as the recorder writes it
     * @param sourceKey the execution's key, as the recorder writes it
     * @return a run whose config carries that kind and key, if any (with several, which one is not defined)
     */
    public static Optional<RunData> find(String experimentId, String sourceKind, String sourceKey) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceKey, "sourceKey");
        return Journal.storage().listRuns(experimentId).stream()
                .filter(run -> run.config() != null
                        && sourceKind.equals(run.config().values().get(CONFIG_SOURCE_KIND))
                        && sourceKey.equals(run.config().values().get(CONFIG_SOURCE_KEY)))
                .findFirst();
    }

    /**
     * Returns a builder for a run that records the source, with the kind and key in its config.
     *
     * @param experimentId the experiment
     * @param sourceKind the kind of source
     * @param sourceKey the execution's key
     * @return the builder; call {@code start()} after any other configuration
     */
    public static RunBuilder newRecording(String experimentId, String sourceKind, String sourceKey) {
        return Journal.run(experimentId)
                .config(CONFIG_SOURCE_KIND, Objects.requireNonNull(sourceKind, "sourceKind"))
                .config(CONFIG_SOURCE_KEY, Objects.requireNonNull(sourceKey, "sourceKey"));
    }

    /**
     * Throws if an earlier recording of the source may be incomplete: it has not ended, or it
     * ended {@code FAILED} with an error recorded, which is how a recorder ends a run whose
     * recording threw. Such a recording is neither resumed nor repeated; the caller removes the
     * run and records again. A run that ended {@code FAILED} without an error is a complete
     * recording of an execution that the agent reported as failed, and counts as recorded.
     *
     * @param earlier the earlier run
     * @throws IllegalStateException if the run has not ended, or ended with an error
     */
    public static void requireComplete(RunData earlier) {
        if (!earlier.status().isTerminal()) {
            throw new IllegalStateException("An earlier recording of this source, run " + earlier.id()
                    + ", has status " + earlier.status() + " and has not ended; recording it again would"
                    + " duplicate its runs. End or remove that run first.");
        }
        if (earlier.status() == io.github.markpollack.journal.RunStatus.FAILED && earlier.errorType() != null) {
            throw new IllegalStateException("An earlier recording of this source, run " + earlier.id()
                    + ", ended with " + earlier.errorType() + ": " + earlier.errorMessage()
                    + "; it may be incomplete and is not resumed. Remove that run first.");
        }
    }

    /** The result of recording an execution at most once. */
    public record Outcome(String runId, boolean recordedNow) {
    }
}
