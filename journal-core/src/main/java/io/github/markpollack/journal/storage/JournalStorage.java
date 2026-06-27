package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;

import java.util.List;
import java.util.Optional;

/**
 * Storage backend interface for persisting tracking data.
 *
 * <p>Implementations handle the actual persistence mechanism (memory, JSON files,
 * database, etc.). All operations are synchronous; async variants may be added later.
 *
 * <p>Directory structure for file-based storage:
 * <pre>
 * .agent-journal/
 * ├── experiments/
 * │   └── {experiment-id}/
 * │       ├── experiment.json
 * │       └── runs/
 * │           └── {run-id}/
 * │               ├── run.json
 * │               ├── events.jsonl
 * │               └── artifacts/
 * </pre>
 *
 * <p>Example:
 * <pre>{@code
 * JournalStorage storage = new JsonFileStorage(Path.of(".agent-journal"));
 *
 * storage.saveExperiment(experiment);
 * storage.saveRun(runData);
 * storage.appendEvent(experimentId, runId, event);
 * }</pre>
 */
public interface JournalStorage {

    // ========== Experiment Operations ==========

    /**
     * Saves an experiment.
     *
     * @param experiment the experiment to save
     */
    void saveExperiment(Experiment experiment);

    /**
     * Loads an experiment by ID.
     *
     * @param id the experiment ID
     * @return the experiment, or empty if not found
     */
    Optional<Experiment> loadExperiment(String id);

    /**
     * Lists all experiments.
     *
     * @return list of all experiments
     */
    List<Experiment> listExperiments();

    /**
     * Checks if an experiment exists.
     *
     * @param id the experiment ID
     * @return true if the experiment exists
     */
    default boolean experimentExists(String id) {
        return loadExperiment(id).isPresent();
    }

    // ========== Run Operations ==========

    /**
     * Saves run data.
     *
     * @param runData the run data to save
     */
    void saveRun(RunData runData);

    /**
     * Loads run data by experiment and run ID.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the run data, or empty if not found
     */
    Optional<RunData> loadRun(String experimentId, String runId);

    /**
     * Lists all runs for an experiment.
     *
     * @param experimentId the experiment ID
     * @return list of run data for the experiment
     */
    List<RunData> listRuns(String experimentId);

    /**
     * Checks if a run exists.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return true if the run exists
     */
    default boolean runExists(String experimentId, String runId) {
        return loadRun(experimentId, runId).isPresent();
    }

    // ========== Event Type Registration ==========

    /**
     * Registers a domain-specific event subtype for Jackson polymorphic deserialization.
     *
     * <p>Call this at startup for any {@link JournalEvent} implementation defined outside
     * of journal-core (e.g., {@code WorkflowStepEvent} from workflow-journal):
     * <pre>{@code
     * Journal.registerEventType("workflow_step", WorkflowStepEvent.class);
     * }</pre>
     *
     * <p>The default implementation is a no-op (e.g., in-memory storage needs no registration).
     *
     * @param typeName the {@code @type} discriminator value written to JSON
     * @param cls      the concrete class to deserialize to
     */
    default void registerEventSubtype(String typeName, Class<? extends JournalEvent> cls) {
        // no-op for storage backends that don't use Jackson
    }

    // ========== Event Operations ==========

    /**
     * Appends an event to a run's event log.
     * Events are stored in append-only fashion (JSONL format for file storage).
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param event the event to append
     */
    void appendEvent(String experimentId, String runId, JournalEvent event);

    /**
     * Loads all events for a run.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return list of events in chronological order
     */
    List<JournalEvent> loadEvents(String experimentId, String runId);

    // ========== Feedback Operations ==========

    /**
     * Appends a feedback event to a run's feedback log.
     * Feedback is stored separately from execution events (feedback.jsonl sidecar).
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param feedback the feedback event to append
     */
    default void appendFeedback(String experimentId, String runId, FeedbackEvent feedback) {
        throw new UnsupportedOperationException("Feedback storage not supported by this implementation");
    }

    /**
     * Loads all feedback events for a run.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return list of feedback events in chronological order
     */
    default List<FeedbackEvent> loadFeedback(String experimentId, String runId) {
        return List.of();
    }

    // ========== Derived Analysis Operations ==========

    /**
     * Appends a derived analysis event to a run's analysis log.
     * Derived events (inferred post-run: cost attribution, scores, …) are stored separately
     * from immutable execution events — in an {@code analysis.jsonl} sidecar — so the two
     * record kinds never look equivalent. Joined to execution events by {@code stepId}/{@code runId}.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param event the derived event to append
     */
    default void appendDerivedEvent(String experimentId, String runId, DerivedEvent event) {
        throw new UnsupportedOperationException("Derived event storage not supported by this implementation");
    }

    /**
     * Loads all derived analysis events for a run.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return list of derived events in append order
     */
    default List<DerivedEvent> loadDerivedEvents(String experimentId, String runId) {
        return List.of();
    }

    /**
     * Whether derived analysis events are persisted <em>durably</em> — i.e. survive process
     * exit so {@code analysis.jsonl} can be reloaded and the derived layer regenerated later.
     *
     * <p>This is the seam the fail-loud capture contract reads (DESIGN §4): a recorder that
     * emits {@link DerivedEvent}s onto a non-durable backend is silently losing the most
     * measurement-critical signal, so the production recorder warns or throws at
     * {@code run.finish()} when this is {@code false}.
     *
     * <p>Defaults to {@code false}: the bare interface's {@link #appendDerivedEvent} throws, and
     * {@link InMemoryStorage} holds derived events only in memory (lost on exit). File-backed
     * storage that writes {@code analysis.jsonl} overrides this to {@code true}.
     *
     * @return true if {@link #appendDerivedEvent} writes to durable storage
     */
    default boolean persistsDerivedEvents() {
        return false;
    }

    // ========== Artifact Operations ==========

    /**
     * Saves an artifact.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param name the artifact name
     * @param content the artifact content
     */
    void saveArtifact(String experimentId, String runId, String name, byte[] content);

    /**
     * Loads an artifact.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @param name the artifact name
     * @return the artifact content, or empty if not found
     */
    Optional<byte[]> loadArtifact(String experimentId, String runId, String name);

    /**
     * Lists artifact names for a run.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return list of artifact names
     */
    List<String> listArtifacts(String experimentId, String runId);
}
