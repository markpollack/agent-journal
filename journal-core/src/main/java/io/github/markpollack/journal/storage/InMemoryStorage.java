package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.event.JournalEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of {@link JournalStorage}.
 *
 * <p>Stores all data in thread-safe collections. Useful for:
 * <ul>
 *   <li>Testing</li>
 *   <li>Short-lived runs that don't need persistence</li>
 *   <li>Development and debugging</li>
 * </ul>
 *
 * <p>Thread-safe: all operations are safe for concurrent access.
 *
 * <p>Example:
 * <pre>{@code
 * InMemoryStorage storage = new InMemoryStorage();
 *
 * storage.saveExperiment(experiment);
 * storage.saveRun(runData);
 *
 * // Later...
 * Optional<RunData> loaded = storage.loadRun("exp-id", "run-id");
 * }</pre>
 */
public class InMemoryStorage implements JournalStorage {

    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RunData>> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<JournalEvent>>> events = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, byte[]>>> artifacts = new ConcurrentHashMap<>();

    @Override
    public void saveExperiment(Experiment experiment) {
        experiments.put(experiment.id(), experiment);
    }

    @Override
    public Optional<Experiment> loadExperiment(String id) {
        return Optional.ofNullable(experiments.get(id));
    }

    @Override
    public List<Experiment> listExperiments() {
        return new ArrayList<>(experiments.values());
    }

    @Override
    public void saveRun(RunData runData) {
        runs.computeIfAbsent(runData.experimentId(), k -> new ConcurrentHashMap<>())
                .put(runData.id(), runData);
    }

    @Override
    public Optional<RunData> loadRun(String experimentId, String runId) {
        Map<String, RunData> experimentRuns = runs.get(experimentId);
        if (experimentRuns == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(experimentRuns.get(runId));
    }

    @Override
    public List<RunData> listRuns(String experimentId) {
        Map<String, RunData> experimentRuns = runs.get(experimentId);
        if (experimentRuns == null) {
            return List.of();
        }
        return new ArrayList<>(experimentRuns.values());
    }

    @Override
    public void appendEvent(String experimentId, String runId, JournalEvent event) {
        events.computeIfAbsent(experimentId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>())
                .add(event);
    }

    @Override
    public List<JournalEvent> loadEvents(String experimentId, String runId) {
        Map<String, List<JournalEvent>> experimentEvents = events.get(experimentId);
        if (experimentEvents == null) {
            return List.of();
        }
        List<JournalEvent> runEvents = experimentEvents.get(runId);
        if (runEvents == null) {
            return List.of();
        }
        return new ArrayList<>(runEvents);
    }

    @Override
    public void saveArtifact(String experimentId, String runId, String name, byte[] content) {
        artifacts.computeIfAbsent(experimentId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .put(name, content.clone()); // Clone to prevent external modification
    }

    @Override
    public Optional<byte[]> loadArtifact(String experimentId, String runId, String name) {
        Map<String, Map<String, byte[]>> experimentArtifacts = artifacts.get(experimentId);
        if (experimentArtifacts == null) {
            return Optional.empty();
        }
        Map<String, byte[]> runArtifacts = experimentArtifacts.get(runId);
        if (runArtifacts == null) {
            return Optional.empty();
        }
        byte[] content = runArtifacts.get(name);
        return content != null ? Optional.of(content.clone()) : Optional.empty();
    }

    @Override
    public List<String> listArtifacts(String experimentId, String runId) {
        Map<String, Map<String, byte[]>> experimentArtifacts = artifacts.get(experimentId);
        if (experimentArtifacts == null) {
            return List.of();
        }
        Map<String, byte[]> runArtifacts = experimentArtifacts.get(runId);
        if (runArtifacts == null) {
            return List.of();
        }
        return new ArrayList<>(runArtifacts.keySet());
    }

    /**
     * Clears all stored data.
     * Useful for test cleanup.
     */
    public void clear() {
        experiments.clear();
        runs.clear();
        events.clear();
        artifacts.clear();
    }

    /**
     * Returns the number of stored experiments.
     */
    public int experimentCount() {
        return experiments.size();
    }

    /**
     * Returns the total number of stored runs across all experiments.
     */
    public int runCount() {
        return runs.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Returns the total number of stored events across all runs.
     */
    public int eventCount() {
        return events.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(List::size)
                .sum();
    }
}
