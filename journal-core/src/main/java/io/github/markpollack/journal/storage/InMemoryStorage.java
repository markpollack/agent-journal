package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link JournalStorage} that keeps every record in memory, for tests and short-lived work.
 * Pass one to {@link io.github.markpollack.journal.Journal#configure(JournalStorage)} and read the
 * results back through the load methods; nothing outlives the JVM. It is also the storage
 * {@link io.github.markpollack.journal.Journal} creates on first use when none was configured. Use
 * {@link JsonFileStorage} when runs must be kept.
 *
 * <p>It keeps experiments, run records, events, derived events, feedback and artifacts. It keeps
 * the objects it is given, not copies, except for artifact content, which it copies on save and
 * on load. The load and list methods return new lists, so changing them does not change what is
 * stored. It does not check that a run's experiment was saved first.
 *
 * <p>Derived events are kept, but {@link #persistsDerivedEvents()} returns {@code false} because
 * they do not outlive the JVM. Claude Code's {@code RunRecorder} therefore throws when a run it
 * recorded ends on this storage, unless it was made lenient. Event types defined outside
 * journal-core need no registration here, because events are kept as objects; a registration
 * made while this storage is configured is still kept for the file storages of this process.
 *
 * <p>All methods are safe to call from several threads.
 *
 * <p>Example:
 * <pre>{@code
 * InMemoryStorage storage = new InMemoryStorage();
 * Journal.configure(storage);
 * String runId;
 * try (Run run = Journal.run("my-experiment").start()) {
 *     runId = run.id();
 *     run.logEvent(LLMCallEvent.of("claude-opus-4.5", 1200, 450, 0.023));
 * }
 * List<JournalEvent> events = storage.loadEvents("my-experiment", runId);
 * storage.clear(); // between tests
 * }</pre>
 */
public class InMemoryStorage implements JournalStorage {

    private final Map<String, Experiment> experiments = new ConcurrentHashMap<>();
    private final Map<String, Map<String, RunData>> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<JournalEvent>>> events = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<FeedbackEvent>>> feedback = new ConcurrentHashMap<>();
    private final Map<String, Map<String, List<DerivedEvent>>> derivedEvents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Map<String, byte[]>>> artifacts = new ConcurrentHashMap<>();

    @Override
    public void saveExperiment(Experiment experiment) {
        experiments.put(experiment.id(), experiment);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This storage keeps events as objects and does not need the registration, but it keeps it
     * for every {@link JsonFileStorage} in this process, so a type registered before
     * {@link io.github.markpollack.journal.Journal#configure} chooses file storage is not lost.
     */
    @Override
    public void registerEventSubtype(String typeName, Class<? extends JournalEvent> cls) {
        EventTypeRegistry.register(typeName, cls);
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

    /**
     * {@inheritDoc}
     *
     * <p>Keeps the feedback event in memory; unlike the interface default, it does not throw.
     */
    @Override
    public void appendFeedback(String experimentId, String runId, FeedbackEvent event) {
        feedback.computeIfAbsent(experimentId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>())
                .add(event);
    }

    @Override
    public List<FeedbackEvent> loadFeedback(String experimentId, String runId) {
        Map<String, List<FeedbackEvent>> experimentFeedback = feedback.get(experimentId);
        if (experimentFeedback == null) {
            return List.of();
        }
        List<FeedbackEvent> runFeedback = experimentFeedback.get(runId);
        if (runFeedback == null) {
            return List.of();
        }
        return new ArrayList<>(runFeedback);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Keeps the derived event in memory; unlike the interface default, it does not throw. The
     * event is lost when the JVM exits.
     */
    @Override
    public void appendDerivedEvent(String experimentId, String runId, DerivedEvent event) {
        derivedEvents.computeIfAbsent(experimentId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>())
                .add(event);
    }

    @Override
    public List<DerivedEvent> loadDerivedEvents(String experimentId, String runId) {
        Map<String, List<DerivedEvent>> experimentDerived = derivedEvents.get(experimentId);
        if (experimentDerived == null) {
            return List.of();
        }
        List<DerivedEvent> runDerived = experimentDerived.get(runId);
        if (runDerived == null) {
            return List.of();
        }
        return new ArrayList<>(runDerived);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Keeps a copy of {@code content}, so later changes to the array do not change the stored
     * artifact. {@code content} must not be {@code null}.
     */
    @Override
    public void saveArtifact(String experimentId, String runId, String name, byte[] content) {
        artifacts.computeIfAbsent(experimentId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(runId, k -> new ConcurrentHashMap<>())
                .put(name, content.clone()); // Clone to prevent external modification
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns a new copy of the content on each call.
     */
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
     * Removes every stored record: experiments, runs, events, derived events, feedback and
     * artifacts. Call it between tests that share this storage.
     */
    public void clear() {
        experiments.clear();
        runs.clear();
        events.clear();
        feedback.clear();
        derivedEvents.clear();
        artifacts.clear();
    }

    /**
     * Returns the number of stored experiments.
     *
     * @return the number of experiments
     */
    public int experimentCount() {
        return experiments.size();
    }

    /**
     * Returns the number of stored run records, over all experiments.
     *
     * @return the number of runs
     */
    public int runCount() {
        return runs.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * Returns the number of stored events, over all runs. Derived events and feedback are not
     * counted.
     *
     * @return the number of events
     */
    public int eventCount() {
        return events.values().stream()
                .flatMap(m -> m.values().stream())
                .mapToInt(List::size)
                .sum();
    }
}
