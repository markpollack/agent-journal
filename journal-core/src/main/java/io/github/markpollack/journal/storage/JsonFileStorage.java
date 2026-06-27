package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JSON file-based implementation of {@link JournalStorage}.
 *
 * <p>Stores data in the following directory structure:
 * <pre>
 * {baseDir}/
 * ├── experiments/
 * │   └── {experiment-id}/
 * │       ├── experiment.json
 * │       └── runs/
 * │           └── {run-id}/
 * │               ├── run.json
 * │               ├── events.jsonl
 * │               └── artifacts/
 * │                   └── {artifact-name}
 * </pre>
 *
 * <p>Events are stored in JSON Lines format (one JSON object per line) for
 * efficient append-only writes.
 *
 * <p>Example:
 * <pre>{@code
 * JournalStorage storage = new JsonFileStorage(Path.of(".agent-journal"));
 *
 * storage.saveExperiment(experiment);
 * storage.saveRun(runData);
 * storage.appendEvent(experimentId, runId, llmCallEvent);
 * }</pre>
 */
public class JsonFileStorage implements JournalStorage {

    private static final String EXPERIMENTS_DIR = "experiments";
    private static final String RUNS_DIR = "runs";
    private static final String ARTIFACTS_DIR = "artifacts";
    private static final String EXPERIMENT_FILE = "experiment.json";
    private static final String RUN_FILE = "run.json";
    private static final String EVENTS_FILE = "events.jsonl";
    private static final String FEEDBACK_FILE = "feedback.jsonl";
    private static final String ANALYSIS_FILE = "analysis.jsonl";

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ObjectMapper eventMapper;

    /**
     * Creates a new JsonFileStorage with the given base directory.
     *
     * @param baseDir the base directory for storage
     */
    public JsonFileStorage(Path baseDir) {
        this.baseDir = baseDir;
        this.objectMapper = createObjectMapper();
        this.eventMapper = createEventMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    private ObjectMapper createEventMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // No indent for JSONL - one line per event
        mapper.disable(SerializationFeature.INDENT_OUTPUT);
        // Type info is handled via @JsonTypeInfo on JournalEvent interface
        return mapper;
    }

    // ========== Path Helpers ==========

    private Path experimentsDir() {
        return baseDir.resolve(EXPERIMENTS_DIR);
    }

    private Path experimentDir(String experimentId) {
        return experimentsDir().resolve(experimentId);
    }

    private Path experimentFile(String experimentId) {
        return experimentDir(experimentId).resolve(EXPERIMENT_FILE);
    }

    private Path runsDir(String experimentId) {
        return experimentDir(experimentId).resolve(RUNS_DIR);
    }

    private Path runDir(String experimentId, String runId) {
        return runsDir(experimentId).resolve(runId);
    }

    private Path runFile(String experimentId, String runId) {
        return runDir(experimentId, runId).resolve(RUN_FILE);
    }

    private Path eventsFile(String experimentId, String runId) {
        return runDir(experimentId, runId).resolve(EVENTS_FILE);
    }

    private Path feedbackFile(String experimentId, String runId) {
        return runDir(experimentId, runId).resolve(FEEDBACK_FILE);
    }

    private Path analysisFile(String experimentId, String runId) {
        return runDir(experimentId, runId).resolve(ANALYSIS_FILE);
    }

    private Path artifactsDir(String experimentId, String runId) {
        return runDir(experimentId, runId).resolve(ARTIFACTS_DIR);
    }

    private Path artifactFile(String experimentId, String runId, String name) {
        return artifactsDir(experimentId, runId).resolve(name);
    }

    // ========== Experiment Operations ==========

    @Override
    public void saveExperiment(Experiment experiment) {
        try {
            Path dir = experimentDir(experiment.id());
            Files.createDirectories(dir);
            objectMapper.writeValue(experimentFile(experiment.id()).toFile(), experiment);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save experiment: " + experiment.id(), e);
        }
    }

    @Override
    public Optional<Experiment> loadExperiment(String id) {
        Path file = experimentFile(id);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), Experiment.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load experiment: " + id, e);
        }
    }

    @Override
    public List<Experiment> listExperiments() {
        Path dir = experimentsDir();
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .map(this::loadExperiment)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list experiments", e);
        }
    }

    // ========== Run Operations ==========

    @Override
    public void saveRun(RunData runData) {
        try {
            Path dir = runDir(runData.experimentId(), runData.id());
            Files.createDirectories(dir);
            objectMapper.writeValue(runFile(runData.experimentId(), runData.id()).toFile(), runData);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save run: " + runData.id(), e);
        }
    }

    @Override
    public Optional<RunData> loadRun(String experimentId, String runId) {
        Path file = runFile(experimentId, runId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), RunData.class));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load run: " + runId, e);
        }
    }

    @Override
    public List<RunData> listRuns(String experimentId) {
        Path dir = runsDir(experimentId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .map(runId -> loadRun(experimentId, runId))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list runs for experiment: " + experimentId, e);
        }
    }

    // ========== Event Operations ==========

    @Override
    public void appendEvent(String experimentId, String runId, JournalEvent event) {
        try {
            Path file = eventsFile(experimentId, runId);
            Files.createDirectories(file.getParent());

            String json = eventMapper.writeValueAsString(event);
            try (BufferedWriter writer = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append event for run: " + runId, e);
        }
    }

    @Override
    public List<JournalEvent> loadEvents(String experimentId, String runId) {
        Path file = eventsFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<JournalEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    events.add(eventMapper.readValue(line, JournalEvent.class));
                }
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load events for run: " + runId, e);
        }
    }

    // ========== Feedback Operations ==========

    @Override
    public void appendFeedback(String experimentId, String runId, FeedbackEvent feedback) {
        try {
            Path file = feedbackFile(experimentId, runId);
            Files.createDirectories(file.getParent());

            String json = eventMapper.writeValueAsString(feedback);
            try (BufferedWriter writer = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append feedback for run: " + runId, e);
        }
    }

    @Override
    public List<FeedbackEvent> loadFeedback(String experimentId, String runId) {
        Path file = feedbackFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<FeedbackEvent> feedbackEvents = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    feedbackEvents.add(eventMapper.readValue(line, FeedbackEvent.class));
                }
            }
            return feedbackEvents;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load feedback for run: " + runId, e);
        }
    }

    // ========== Derived Analysis Operations ==========

    @Override
    public void appendDerivedEvent(String experimentId, String runId, DerivedEvent event) {
        try {
            Path file = analysisFile(experimentId, runId);
            Files.createDirectories(file.getParent());

            String json = eventMapper.writeValueAsString(event);
            try (BufferedWriter writer = Files.newBufferedWriter(file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(json);
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append derived event for run: " + runId, e);
        }
    }

    /**
     * File storage writes derived events to a durable {@code analysis.jsonl} sidecar, so they
     * survive process exit and the derived layer is regenerable from disk.
     */
    @Override
    public boolean persistsDerivedEvents() {
        return true;
    }

    @Override
    public List<DerivedEvent> loadDerivedEvents(String experimentId, String runId) {
        Path file = analysisFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<DerivedEvent> derived = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    derived.add(eventMapper.readValue(line, DerivedEvent.class));
                }
            }
            return derived;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load derived events for run: " + runId, e);
        }
    }

    // ========== Artifact Operations ==========

    @Override
    public void saveArtifact(String experimentId, String runId, String name, byte[] content) {
        try {
            Path file = artifactFile(experimentId, runId, name);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save artifact: " + name, e);
        }
    }

    @Override
    public Optional<byte[]> loadArtifact(String experimentId, String runId, String name) {
        Path file = artifactFile(experimentId, runId, name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load artifact: " + name, e);
        }
    }

    @Override
    public List<String> listArtifacts(String experimentId, String runId) {
        Path dir = artifactsDir(experimentId, runId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list artifacts for run: " + runId, e);
        }
    }

    /**
     * Registers a domain-specific event subtype for Jackson polymorphic deserialization.
     * Must be called before reading any JSONL files that contain this event type.
     */
    @Override
    public void registerEventSubtype(String typeName, Class<? extends JournalEvent> cls) {
        eventMapper.registerSubtypes(new NamedType(cls, typeName));
    }

    /**
     * Returns the base directory for this storage.
     */
    public Path baseDir() {
        return baseDir;
    }
}
