package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;

import java.nio.file.Path;
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
 * │               ├── artifacts/
 * │               └── raw/
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

    // ========== Raw Provider Artifacts (contract only — see rawDirectory) ==========

    /**
     * The directory a run's <strong>verbatim provider artifacts</strong> live in, when this
     * backend has one.
     *
     * <p>
     * <strong>This is a contract, not a copier.</strong> The journal reserves and locates
     * {@code raw/} within the run directory so raw provider artifacts — the Claude Code
     * {@code .jsonl} session log and its equivalents — are <em>findable from the run record</em>,
     * keyed by {@code runId}. Producing the copies is deliberately <em>not</em> the journal's
     * job: copying the provider session file belongs to {@code agent-experiment}, which knows
     * what it launched and when. The journal's half is this — a stable, discoverable location a
     * copier can write into and an analysis can read back from, so a run record is never a dead
     * end.
     *
     * <p>
     * <strong>Why it matters.</strong> Nearly every capture gap found in the 2026-08-24
     * measurement audit — per-step tokens, stop reason, per-tool duration — was plausibly
     * already present in the raw provider log and was discarded at parse time. Keeping raw
     * turns "we cannot answer that" into "re-derive it", at the price of storage rather than a
     * re-run. Capture is one-shot; analysis is free to redo.
     *
     * <p>
     * Contents are expected to be <strong>content-addressed</strong> (named by a digest of the
     * bytes), so the same provider artifact copied twice is stored once and any copy is
     * verifiable against its own name. The journal neither parses nor validates what lands
     * here: raw is immutable evidence, not a schema.
     *
     * <p>
     * The default implementation returns empty — a backend with no filesystem (in-memory) has
     * nowhere to put raw, and says so rather than inventing a path.
     *
     * @param experimentId the experiment ID
     * @param runId the run ID
     * @return the run's raw-artifact directory, or empty when this backend has none. The
     *         directory is not created by this call and need not already exist.
     */
    default Optional<Path> rawDirectory(String experimentId, String runId) {
        return Optional.empty();
    }
}
