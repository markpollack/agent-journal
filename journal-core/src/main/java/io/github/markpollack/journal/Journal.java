package io.github.markpollack.journal;

import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.JournalStorage;

/**
 * Main entry point for the tracking API.
 *
 * <p>Journal provides static factory methods for creating runs and managing
 * experiments. It is the primary interface users interact with.
 *
 * <h2>Basic Usage</h2>
 * <pre>{@code
 * try (Run run = Journal.run("implement-oauth")
 *         .config("model", "claude-opus-4.5")
 *         .tag("type", "feature")
 *         .start()) {
 *
 *     run.logEvent(LLMCallEvent.of("claude-opus-4.5", 1200, 450, 0.023));
 *     run.logMetric("tokens.total", 1650);
 *     run.setSummary("filesChanged", 5);
 *
 * } // Auto-finish with SUCCESS (or FAILED on exception)
 * }</pre>
 *
 * <h2>Configuration</h2>
 * <p>Configure storage before creating runs:
 * <pre>{@code
 * // File-based storage
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 *
 * // In-memory (default, for testing)
 * Journal.configure(new InMemoryStorage());
 * }</pre>
 *
 * <h2>Experiments</h2>
 * <p>Get or create experiments:
 * <pre>{@code
 * Experiment exp = Journal.experiment("implement-oauth");
 * }</pre>
 *
 * @see Run
 * @see RunBuilder
 * @see JournalContext
 */
public final class Journal {

    private Journal() {} // Utility class

    /**
     * Starts building a new run for the given experiment.
     *
     * <p>This is the primary method for creating runs. The returned builder
     * can be used to configure the run before starting it.
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
     * @param experimentId the experiment identifier (e.g., "implement-oauth")
     * @return a builder for configuring the run
     */
    public static RunBuilder run(String experimentId) {
        return RunBuilder.forExperiment(experimentId);
    }

    /**
     * Gets or creates an experiment by ID.
     *
     * <p>If the experiment doesn't exist, a new one is created with
     * default settings. The experiment is cached and persisted to storage.
     *
     * @param experimentId the experiment identifier
     * @return the experiment
     */
    public static Experiment experiment(String experimentId) {
        return ExperimentRegistry.getOrCreate(experimentId);
    }

    /**
     * Gets or creates an experiment with custom settings.
     *
     * <p>If the experiment already exists, the builder is ignored and
     * the existing experiment is returned.
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
     * @param experimentId the experiment identifier
     * @param builder the builder with custom settings
     * @return the experiment
     */
    public static Experiment experiment(String experimentId, Experiment.Builder builder) {
        return ExperimentRegistry.getOrCreate(experimentId, builder);
    }

    /**
     * Configures the storage backend.
     *
     * <p>This should be called once at application startup before creating
     * any runs. If not called, an in-memory storage is used by default.
     *
     * <p>Example:
     * <pre>{@code
     * // For production: file-based storage
     * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
     *
     * // For testing: in-memory storage
     * Journal.configure(new InMemoryStorage());
     * }</pre>
     *
     * @param storage the storage implementation
     */
    public static void configure(JournalStorage storage) {
        JournalContext.setStorage(storage);
    }

    /**
     * Returns the current storage backend.
     *
     * @return the configured storage
     */
    public static JournalStorage storage() {
        return JournalContext.getStorage();
    }

    /**
     * Registers a domain-specific event type for JSON deserialization.
     *
     * <p>Call this at startup for any {@link JournalEvent} implementation defined outside
     * journal-core before reading events from file storage:
     * <pre>{@code
     * Journal.configure(new JsonFileStorage(path));
     * Journal.registerEventType("workflow_step", WorkflowStepEvent.class);
     * }</pre>
     *
     * @param typeName the {@code @type} discriminator value written to JSON
     * @param cls      the concrete class to deserialize to
     */
    public static void registerEventType(String typeName, Class<? extends JournalEvent> cls) {
        JournalContext.getStorage().registerEventSubtype(typeName, cls);
    }

    /**
     * Resets the tracking context to defaults.
     *
     * <p>This clears all configuration and cached data. Primarily useful
     * for testing to ensure clean state between test cases.
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
