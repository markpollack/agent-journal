package io.github.markpollack.journal;

import io.github.markpollack.journal.storage.JournalStorage;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing experiments.
 *
 * <p>ExperimentRegistry provides thread-safe access to experiments, caching
 * them in memory and persisting to storage when configured. Experiments are
 * identified by their unique ID.
 *
 * <p>The registry follows a "get or create" pattern:
 * <pre>{@code
 * // First call creates the experiment
 * Experiment exp = ExperimentRegistry.getOrCreate("my-experiment");
 *
 * // Subsequent calls return the same experiment
 * Experiment same = ExperimentRegistry.getOrCreate("my-experiment");
 * assert exp == same;
 * }</pre>
 *
 * <p>For full control over experiment properties, use the builder:
 * <pre>{@code
 * Experiment exp = ExperimentRegistry.getOrCreate("my-experiment",
 *     Experiment.create("my-experiment")
 *         .name("My Experiment")
 *         .description("A test experiment")
 * );
 * }</pre>
 */
public final class ExperimentRegistry {

    private static final Map<String, Experiment> cache = new ConcurrentHashMap<>();

    private ExperimentRegistry() {} // Utility class

    /**
     * Gets an existing experiment or creates a new one with default settings.
     *
     * <p>If the experiment doesn't exist in cache, this method will:
     * <ol>
     *   <li>Try to load from storage</li>
     *   <li>If not in storage, create a new experiment</li>
     *   <li>Persist to storage and cache</li>
     * </ol>
     *
     * @param experimentId the unique experiment identifier
     * @return the experiment
     * @throws NullPointerException if experimentId is null
     */
    public static Experiment getOrCreate(String experimentId) {
        Objects.requireNonNull(experimentId, "experimentId cannot be null");
        return cache.computeIfAbsent(experimentId, id -> loadOrCreate(id, null));
    }

    /**
     * Gets an existing experiment or creates one from the provided builder.
     *
     * <p>If the experiment already exists (in cache or storage), the builder
     * is ignored and the existing experiment is returned.
     *
     * @param experimentId the unique experiment identifier
     * @param builder the builder to use if experiment doesn't exist (can be null)
     * @return the experiment
     * @throws NullPointerException if experimentId is null
     */
    public static Experiment getOrCreate(String experimentId, Experiment.Builder builder) {
        Objects.requireNonNull(experimentId, "experimentId cannot be null");
        return cache.computeIfAbsent(experimentId, id -> loadOrCreate(id, builder));
    }

    /**
     * Returns an existing experiment if it exists.
     *
     * @param experimentId the experiment identifier
     * @return the experiment if found
     */
    public static Optional<Experiment> get(String experimentId) {
        Objects.requireNonNull(experimentId, "experimentId cannot be null");

        // Check cache first
        Experiment cached = cache.get(experimentId);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Try loading from storage
        JournalStorage storage = JournalContext.getStorage();
        Optional<Experiment> fromStorage = storage.loadExperiment(experimentId);
        fromStorage.ifPresent(exp -> cache.putIfAbsent(experimentId, exp));

        return fromStorage;
    }

    /**
     * Clears the in-memory cache.
     *
     * <p>This is primarily useful for testing. Experiments will be
     * reloaded from storage on next access.
     */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * Returns the number of cached experiments.
     *
     * @return the cache size
     */
    public static int cacheSize() {
        return cache.size();
    }

    private static Experiment loadOrCreate(String experimentId, Experiment.Builder builder) {
        JournalStorage storage = JournalContext.getStorage();

        // Try to load from storage first
        Optional<Experiment> existing = storage.loadExperiment(experimentId);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new experiment
        Experiment experiment;
        if (builder != null) {
            experiment = builder.build();
        } else {
            experiment = Experiment.create(experimentId).build();
        }

        // Persist to storage
        storage.saveExperiment(experiment);

        return experiment;
    }
}
