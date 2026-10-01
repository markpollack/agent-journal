package io.github.markpollack.journal;

import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.JournalStorage;

/**
 * The static entry point for recording agent runs: choose where records go, then start runs.
 * Call {@link #configure(JournalStorage)} once at startup, for example with a
 * {@link io.github.markpollack.journal.storage.JsonFileStorage}, then call {@link #run(String)}
 * for a {@link RunBuilder} and start a {@link Run}. Runs are grouped into an {@link Experiment},
 * which is created the first time it is used.
 *
 * <p>The storage is shared by the whole process. If none is configured, the first use creates an
 * in-memory storage, and nothing is kept after the JVM exits.
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 *
 * try (Run run = Journal.run("implement-oauth")
 *         .config("model", "claude-opus-4.5")
 *         .tag("type", "feature")
 *         .start()) {
 *
 *     run.logEvent(LLMCallEvent.of("claude-opus-4.5", 1200, 450, 0.023));
 *     run.logMetric("tokens.total", 1650);
 *     run.setSummary("filesChanged", 5);
 *
 * } // close() ends the run as FINISHED; call run.fail(e) inside the block to record a failure
 * }</pre>
 *
 * <h2>Tests</h2>
 * <p>Use in-memory storage and reset between tests:
 * <pre>{@code
 * Journal.configure(new InMemoryStorage());
 * // ...
 * Journal.reset();
 * }</pre>
 *
 * <p>All methods are safe to call from several threads.
 *
 * @see Run
 * @see RunBuilder
 * @see JournalContext
 */
public final class Journal {

    private Journal() {} // Utility class

    /**
     * Returns a builder for a new run in the given experiment. Set the run's name, config and tags
     * on the builder, then call {@link RunBuilder#start()}, which creates the experiment if needed
     * and saves the new run.
     *
     * <p>Example:
     * <pre>{@code
     * try (Run run = Journal.run("my-experiment")
     *         .name("attempt-1")
     *         .config("model", "claude-opus-4.5")
     *         .start()) {
     *     // ... do work
     * }
     * }</pre>
     *
     * @param experimentId the ID of the experiment the run belongs to, such as
     *        {@code "implement-oauth"}
     * @return a new run builder
     * @throws NullPointerException if {@code experimentId} is {@code null}
     */
    public static RunBuilder run(String experimentId) {
        return RunBuilder.forExperiment(experimentId);
    }

    /**
     * Returns the experiment with the given ID, creating it with default settings if it does not
     * exist. It looks in a process-wide cache first, then in the configured storage, and saves a
     * new experiment to storage. The cache is kept until {@link #reset()}.
     *
     * @param experimentId the experiment ID
     * @return the experiment, never {@code null}
     * @throws NullPointerException if {@code experimentId} is {@code null}
     */
    public static Experiment experiment(String experimentId) {
        return ExperimentRegistry.getOrCreate(experimentId);
    }

    /**
     * Returns the experiment with the given ID, creating it from {@code builder} if it does not
     * exist. If the experiment is already cached or stored, {@code builder} is ignored.
     *
     * <p>Example:
     * <pre>{@code
     * Experiment exp = Journal.experiment("implement-oauth",
     *     Experiment.create("implement-oauth")
     *         .name("OAuth Implementation")
     *         .description("Adding OAuth2 authentication support")
     * );
     * }</pre>
     *
     * @param experimentId the experiment ID
     * @param builder the settings for a new experiment, or {@code null} for default settings
     * @return the experiment, never {@code null}
     * @throws NullPointerException if {@code experimentId} is {@code null}
     */
    public static Experiment experiment(String experimentId, Experiment.Builder builder) {
        return ExperimentRegistry.getOrCreate(experimentId, builder);
    }

    /**
     * Sets the storage that runs in this process write to. Call it once at startup, before
     * starting runs. A run keeps the storage it started with, so runs already started are not
     * moved.
     *
     * <p>Event types registered with {@link #registerEventType(String, Class)} belong to one
     * storage, so register them after this call.
     *
     * <p>Example:
     * <pre>{@code
     * // Records kept on disk
     * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
     *
     * // Records kept in memory, for tests
     * Journal.configure(new InMemoryStorage());
     * }</pre>
     *
     * @param storage the storage to use
     * @throws NullPointerException if {@code storage} is {@code null}
     */
    public static void configure(JournalStorage storage) {
        JournalContext.setStorage(storage);
    }

    /**
     * Returns the storage runs write to, creating an in-memory storage if none was configured.
     *
     * @return the current storage, never {@code null}
     */
    public static JournalStorage storage() {
        return JournalContext.getStorage();
    }

    /**
     * Registers an event type defined outside journal-core, so that file storage can read events
     * of that type back. The type is registered on the storage configured at the time of the call,
     * so call this after {@link #configure(JournalStorage)}:
     * <pre>{@code
     * Journal.configure(new JsonFileStorage(path));
     * Journal.registerEventType("workflow_step", WorkflowStepEvent.class);
     * }</pre>
     *
     * @param typeName the {@code @type} value that events of this type are written with
     * @param cls the {@link JournalEvent} class to read those events into
     */
    public static void registerEventType(String typeName, Class<? extends JournalEvent> cls) {
        JournalContext.getStorage().registerEventSubtype(typeName, cls);
    }

    /**
     * Returns to the initial state: forgets the configured storage and clears the experiment
     * cache. The next use creates a new in-memory storage; event types registered on the old
     * storage are not carried over. Meant for tests:
     *
     * <pre>{@code
     * @AfterEach
     * void tearDown() {
     *     Journal.reset();
     * }
     * }</pre>
     */
    public static void reset() {
        JournalContext.reset();
        ExperimentRegistry.clearCache();
    }
}
