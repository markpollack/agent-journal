package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The backend that keeps a journal's records: experiments, run records, events, derived events,
 * feedback and artifacts. Choose an implementation and pass it to
 * {@link io.github.markpollack.journal.Journal#configure(JournalStorage)}:
 * {@link JsonFileStorage} keeps records as files that outlive the JVM, and
 * {@link InMemoryStorage} keeps them in memory, for tests. Most code does not call these methods
 * directly: a {@link io.github.markpollack.journal.Run} writes through them, and analysis code
 * such as {@code EvalSubjectSources} reads through them.
 *
 * <p>Records are found by experiment ID and run ID. Events, derived events and feedback are only
 * appended, and load back in the order they were appended. An experiment or a run record
 * ({@link RunData}) is replaced each time it is saved. All methods are synchronous, and load and
 * list methods return an empty result, never {@code null}, when nothing is stored.
 *
 * <p>To write a new backend, implement the abstract methods for experiments, runs, events and
 * artifacts. The other methods have defaults for a backend that lacks those features: feedback
 * and derived events cannot be appended (the append methods throw
 * {@link UnsupportedOperationException}) and load as empty, {@link #persistsDerivedEvents()}
 * returns {@code false}, {@link #rawDirectory(String, String)} returns empty, and
 * {@link #registerEventSubtype(String, Class)} does nothing.
 *
 * <p>This interface does not require thread safety. {@link InMemoryStorage} is safe for
 * concurrent use; {@link JsonFileStorage} expects one writer per run.
 */
public interface JournalStorage {

    // ========== Experiment Operations ==========

    /**
     * Saves an experiment, replacing any stored experiment with the same ID.
     *
     * @param experiment the experiment to save
     */
    void saveExperiment(Experiment experiment);

    /**
     * Returns the stored experiment with the given ID.
     *
     * @param id the experiment ID
     * @return the experiment, or empty if none is stored
     */
    Optional<Experiment> loadExperiment(String id);

    /**
     * Returns every stored experiment, in no set order.
     *
     * @return the experiments, empty if there are none
     */
    List<Experiment> listExperiments();

    /**
     * Returns whether an experiment with the given ID is stored. The default calls
     * {@link #loadExperiment(String)}.
     *
     * @param id the experiment ID
     * @return {@code true} if the experiment is stored
     */
    default boolean experimentExists(String id) {
        return loadExperiment(id).isPresent();
    }

    // ========== Run Operations ==========

    /**
     * Saves a run record, replacing any stored record of the same run. A run saves its record when
     * it starts and again when it ends.
     *
     * @param runData the run record to save
     */
    void saveRun(RunData runData);

    /**
     * Returns the stored record of a run.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the run record, or empty if none is stored
     */
    Optional<RunData> loadRun(String experimentId, String runId);

    /**
     * Returns the records of every stored run of an experiment, in no set order.
     *
     * @param experimentId the experiment ID
     * @return the run records, empty if there are none
     */
    List<RunData> listRuns(String experimentId);

    /**
     * Returns whether a record of the run is stored. The default calls
     * {@link #loadRun(String, String)}.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return {@code true} if the run record is stored
     */
    default boolean runExists(String experimentId, String runId) {
        return loadRun(experimentId, runId).isPresent();
    }

    // ========== Event Type Registration ==========

    /**
     * Registers an event type defined outside journal-core, so that stored events of that type can
     * be loaded back. Register it before loading any run that contains it. Most callers use
     * {@link io.github.markpollack.journal.Journal#registerEventType(String, Class)}, which
     * registers on the configured storage.
     *
     * <p>The default does nothing, which suits a backend that keeps event objects as they are.
     * The two built-in storages keep the registration for the whole process, so it applies to
     * every {@link JsonFileStorage}, including ones created later.
     *
     * @param typeName the {@code @type} value written for events of this type
     * @param cls the class to load such events as
     */
    default void registerEventSubtype(String typeName, Class<? extends JournalEvent> cls) {
        // no-op for storage backends that don't use Jackson
    }

    // ========== Event Operations ==========

    /**
     * Appends an event to a run's events. An appended event is never changed or removed.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param event the event to append
     */
    void appendEvent(String experimentId, String runId, JournalEvent event);

    /**
     * Returns a run's events.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the events in the order they were appended, empty if there are none
     */
    List<JournalEvent> loadEvents(String experimentId, String runId);

    // ========== Feedback Operations ==========

    /**
     * Appends a feedback event, a verdict on the run or on part of it, to a run. Feedback is kept
     * apart from the run's events. The default throws, for a backend that does not keep feedback.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param feedback the feedback event to append
     * @throws UnsupportedOperationException if this storage does not keep feedback
     */
    default void appendFeedback(String experimentId, String runId, FeedbackEvent feedback) {
        throw new UnsupportedOperationException("Feedback storage not supported by this implementation");
    }

    /**
     * Returns a run's feedback events. The default returns an empty list.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the feedback events in the order they were appended, empty if there are none
     */
    default List<FeedbackEvent> loadFeedback(String experimentId, String runId) {
        return List.of();
    }

    // ========== Derived Analysis Operations ==========

    /**
     * Appends a derived event to a run: a conclusion computed after the fact, such as a step's
     * share of the cost. Derived events are kept apart from the run's events and point back to
     * them by step ID and run ID. The default throws, for a backend that does not keep derived
     * events.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param event the derived event to append
     * @throws UnsupportedOperationException if this storage does not keep derived events
     */
    default void appendDerivedEvent(String experimentId, String runId, DerivedEvent event) {
        throw new UnsupportedOperationException("Derived event storage not supported by this implementation");
    }

    /**
     * Returns a run's derived events. The default returns an empty list.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the derived events in the order they were appended, empty if there are none
     */
    default List<DerivedEvent> loadDerivedEvents(String experimentId, String runId) {
        return List.of();
    }

    /**
     * Returns whether derived events outlive the JVM, so they can be loaded again after the
     * process exits. Claude Code's {@code RunRecorder} checks this when a run ends, and by default
     * throws if it recorded derived events that would be lost.
     *
     * <p>The default returns {@code false}. {@link InMemoryStorage} keeps the default;
     * {@link JsonFileStorage} returns {@code true}. A backend whose
     * {@link #appendDerivedEvent(String, String, DerivedEvent)} writes to lasting storage should
     * return {@code true}.
     *
     * @return {@code true} if derived events are kept after the JVM exits
     */
    default boolean persistsDerivedEvents() {
        return false;
    }

    // ========== Artifact Operations ==========

    /**
     * Saves named content for a run, replacing an artifact of the same name.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param name the artifact name
     * @param content the content
     */
    void saveArtifact(String experimentId, String runId, String name, byte[] content);

    /**
     * Returns a run's artifact.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param name the artifact name
     * @return the content, or empty if the run has no artifact of that name
     */
    Optional<byte[]> loadArtifact(String experimentId, String runId, String name);

    /**
     * Returns the names of a run's artifacts, in no set order.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the artifact names, empty if there are none
     */
    List<String> listArtifacts(String experimentId, String runId);

    // ========== Raw Provider Artifacts (contract only — see rawDirectory) ==========

    /**
     * Returns the directory where copies of the agent's own session files for a run belong, if this
     * storage has one. Keeping those files lets later analysis recover data that the parsers
     * dropped.
     *
     * <p>This method only names the directory: it does not create it, and journal-core never
     * writes to it or reads from it. A caller that archives session files, such as
     * agent-experiment, writes them there, so they can be found from the run. The default returns
     * empty, for storage without a file system.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the directory, which may not exist yet, or empty if this storage has none
     */
    default Optional<Path> rawDirectory(String experimentId, String runId) {
        return Optional.empty();
    }
}
