package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
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
 * .tuvium/
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
 * JournalStorage storage = new JsonFileStorage(Path.of(".tuvium"));
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
