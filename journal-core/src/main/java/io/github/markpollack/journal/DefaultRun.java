package io.github.markpollack.journal;

import io.github.markpollack.journal.call.CallTracker;
import io.github.markpollack.journal.call.DefaultCallTracker;
import io.github.markpollack.journal.event.MetricEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.metric.InMemoryMetricRegistry;
import io.github.markpollack.journal.metric.MetricRegistry;
import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.storage.JournalStorage;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link Run}.
 *
 * <p>This implementation stores all data in memory. For persistent storage,
 * use with {@link io.github.markpollack.journal.storage.JournalStorage} (Step 1.6).
 *
 * <p>Thread-safe: all operations are safe for concurrent access.
 */
public final class DefaultRun implements Run {

    private final String id;
    private final String name;
    private final Experiment experiment;
    private final Config config;
    private final Tags tags;
    private final String agentId;
    private final String previousRunId;
    private final String parentRunId;
    private final Instant startTime;
    private final JournalStorage storage;

    private final InMemoryMetricRegistry metricRegistry;
    private final DefaultCallTracker callTracker;
    private final List<JournalEvent> events;
    private final List<Artifact> artifacts;

    private volatile RunStatus status;
    private volatile Summary summary;
    private volatile Throwable failureCause;
    private volatile Instant endTime;

    /**
     * Creates a new run from the builder configuration.
     *
     * @param builder the run builder
     * @param experiment the experiment this run belongs to
     * @param storage the storage backend for persistence
     */
    DefaultRun(RunBuilder builder, Experiment experiment, JournalStorage storage) {
        this.id = UUID.randomUUID().toString();
        this.name = builder.name() != null ? builder.name() : generateName();
        this.experiment = experiment;
        this.config = builder.config();
        this.tags = builder.tags();
        this.agentId = builder.agentId();
        this.previousRunId = builder.previousRunId();
        this.parentRunId = builder.parentRunId();
        this.startTime = Instant.now();
        this.storage = storage;

        this.metricRegistry = new InMemoryMetricRegistry();
        this.callTracker = new DefaultCallTracker();
        this.events = new CopyOnWriteArrayList<>();
        this.artifacts = new CopyOnWriteArrayList<>();

        this.status = RunStatus.RUNNING;
        this.summary = Summary.empty();

        // Persist initial run state
        persistRun();
    }

    /**
     * Creates a new run without storage (for testing).
     *
     * @param builder the run builder
     * @param experiment the experiment this run belongs to
     * @deprecated Use the constructor with storage parameter
     */
    @Deprecated
    DefaultRun(RunBuilder builder, Experiment experiment) {
        this(builder, experiment, new io.github.markpollack.journal.storage.InMemoryStorage());
    }

    private String generateName() {
        // Generate a short readable name like "run-abc123"
        return "run-" + id.substring(0, 8);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Experiment experiment() {
        return experiment;
    }

    @Override
    public RunStatus status() {
        return status;
    }

    @Override
    public Config config() {
        return config;
    }

    @Override
    public Summary summary() {
        return summary;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public String agentId() {
        return agentId;
    }

    @Override
    public String previousRunId() {
        return previousRunId;
    }

    @Override
    public String parentRunId() {
        return parentRunId;
    }

    @Override
    public void logEvent(JournalEvent event) {
        ensureRunning();
        events.add(event);
        storage.appendEvent(experiment.id(), id, event);
    }

    @Override
    public void logMetric(String name, double value) {
        logMetric(name, value, Tags.empty());
    }

    @Override
    public void logMetric(String name, double value, Tags tags) {
        ensureRunning();
        // Record as both a metric event and in the gauge
        MetricEvent event = MetricEvent.of(name, value, tags);
        events.add(event);
        storage.appendEvent(experiment.id(), id, event);
        metricRegistry.gauge(name, tags).set(value);
    }

    @Override
    public void logArtifact(String name, String content) {
        logArtifact(name, content.getBytes(), Map.of("type", "text"));
    }

    @Override
    public void logArtifact(String name, byte[] content, Map<String, Object> metadata) {
        ensureRunning();
        artifacts.add(new Artifact(name, content, metadata, Instant.now()));
        // Note: metadata is stored in-memory but not persisted to file storage yet
        storage.saveArtifact(experiment.id(), id, name, content);
    }

    @Override
    public void setSummary(String key, Object value) {
        ensureRunning();
        this.summary = this.summary.with(key, value);
    }

    @Override
    public MetricRegistry metrics() {
        return metricRegistry;
    }

    @Override
    public CallTracker calls() {
        return callTracker;
    }

    @Override
    public void fail(Throwable error) {
        if (status.isTerminal()) {
            return; // Already finished
        }
        this.failureCause = error;
        this.status = RunStatus.FAILED;
        this.endTime = Instant.now();

        // Record failure in summary
        this.summary = this.summary
                .with("success", false)
                .with("error", error.getMessage())
                .with("errorType", error.getClass().getName());

        persistRun();
    }

    @Override
    public void finish(RunStatus status) {
        if (this.status.isTerminal()) {
            return; // Already finished
        }
        this.status = status;
        this.endTime = Instant.now();

        // Record success status in summary if finishing successfully
        if (status == RunStatus.FINISHED && !summary.values().containsKey("success")) {
            this.summary = this.summary.with("success", true);
        }

        persistRun();
    }

    @Override
    public void close() {
        if (!status.isTerminal()) {
            // If no explicit status set and no failure, mark as finished
            if (failureCause == null) {
                finish(RunStatus.FINISHED);
            } else {
                // Failure was already recorded via fail()
            }
        }
    }

    /**
     * Persists the current run state to storage.
     */
    private void persistRun() {
        RunData runData = RunData.fromRun(
                id,
                experiment.id(),
                name,
                status,
                config,
                summary,
                tags,
                agentId,
                previousRunId,
                parentRunId,
                startTime,
                endTime,
                failureCause != null ? failureCause.getMessage() : null,
                failureCause != null ? failureCause.getClass().getName() : null
        );
        storage.saveRun(runData);
    }

    /**
     * Returns the run start time.
     */
    public Instant startTime() {
        return startTime;
    }

    /**
     * Returns the run end time, or null if not finished.
     */
    public Instant endTime() {
        return endTime;
    }

    /**
     * Returns the failure cause, or null if no failure.
     */
    public Throwable failureCause() {
        return failureCause;
    }

    /**
     * Returns an unmodifiable view of all logged events.
     */
    public List<JournalEvent> events() {
        return Collections.unmodifiableList(events);
    }

    /**
     * Returns an unmodifiable view of all logged artifacts.
     */
    public List<Artifact> artifacts() {
        return Collections.unmodifiableList(artifacts);
    }

    /**
     * Returns the in-memory metric registry for snapshot access.
     */
    public InMemoryMetricRegistry inMemoryMetrics() {
        return metricRegistry;
    }

    private void ensureRunning() {
        if (status.isTerminal()) {
            throw new IllegalStateException("Run is already " + status + ", cannot log more data");
        }
    }

    /**
     * Internal artifact record.
     */
    public record Artifact(
            String name,
            byte[] content,
            Map<String, Object> metadata,
            Instant timestamp
    ) {}

    @Override
    public String toString() {
        return "Run{id='" + id + "', name='" + name + "', status=" + status + "}";
    }
}
