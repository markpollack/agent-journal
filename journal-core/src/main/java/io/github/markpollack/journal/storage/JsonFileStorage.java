package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A {@link JournalStorage} that keeps journals as JSON files in a local directory, so runs
 * outlive the JVM and can be read back, compared, or queried with tools such as DuckDB. Pass one
 * to {@link io.github.markpollack.journal.Journal#configure(JournalStorage)} for real runs; use
 * {@link InMemoryStorage} for tests. Each run gets a directory with a {@code run.json} record and
 * JSON Lines files (one JSON object per line) for its events, derived events and feedback.
 *
 * <p>The layout under the base directory:
 * <pre>
 * {baseDir}/
 * └── experiments/
 *     └── {experimentId}/
 *         ├── experiment.json
 *         └── runs/
 *             └── {runId}/
 *                 ├── run.json          run record, rewritten on each save
 *                 ├── events.jsonl      what happened, appended
 *                 ├── analysis.jsonl    derived events, appended
 *                 ├── feedback.jsonl    feedback, appended
 *                 ├── artifacts/{name}
 *                 └── raw/              reserved, not written by this class
 * </pre>
 *
 * <p>{@code events.jsonl} and {@code analysis.jsonl} start with a header line that carries
 * {@link #SCHEMA_VERSION}; {@code feedback.jsonl} has no header. The load methods skip header
 * lines and read the whole file into memory. Event types defined outside journal-core must be
 * registered with {@link #registerEventSubtype(String, Class)} before they can be loaded.
 *
 * <p>{@code raw/} is where copies of the agent's own session files belong, so they can be found
 * from the run; agent-experiment fills it, this class only locates it. See
 * {@link #rawDirectory(String, String)}.
 *
 * <p>Limits: experiment IDs, run IDs and artifact names are used as path parts without checks,
 * so pass only trusted values. The class does no locking: use one writer per run, and do not
 * share a run's files between processes. File errors are thrown as
 * {@link UncheckedIOException}.
 *
 * <p>Example:
 * <pre>{@code
 * Journal.configure(new JsonFileStorage(Path.of(".agent-journal")));
 * }</pre>
 */
public class JsonFileStorage implements JournalStorage {

    /**
     * The schema version written in the header line of {@code events.jsonl} and
     * {@code analysis.jsonl}, so a reader can tell which format a file uses. It changes only when
     * a field is renamed, removed or given a new meaning; new fields and new enum values do not
     * change it. Trace files have their own, separate schema version.
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * The {@code @type} value of the header line at the start of {@code events.jsonl} and
     * {@code analysis.jsonl}. The load methods skip lines of this type.
     */
    public static final String HEADER_TYPE = "header";

    private static final String EXPERIMENTS_DIR = "experiments";
    private static final String RUNS_DIR = "runs";
    private static final String ARTIFACTS_DIR = "artifacts";
    private static final String RAW_DIR = "raw";
    private static final String EXPERIMENT_FILE = "experiment.json";
    private static final String RUN_FILE = "run.json";
    private static final String EVENTS_FILE = "events.jsonl";
    private static final String FEEDBACK_FILE = "feedback.jsonl";
    private static final String ANALYSIS_FILE = "analysis.jsonl";

    private final Path baseDir;
    private final ObjectMapper objectMapper;
    private final ObjectMapper eventMapper;

    /**
     * Creates a storage that writes under the given directory. Nothing is written until the first
     * save; directories are created as needed.
     *
     * @param baseDir the directory to write under
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

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@code {baseDir}/experiments/{experimentId}/runs/{runId}/raw}. This class does
     * not create the directory; if it is missing, nothing was archived for the run.
     */
    @Override
    public Optional<Path> rawDirectory(String experimentId, String runId) {
        return Optional.of(runDir(experimentId, runId).resolve(RAW_DIR));
    }

    private Path artifactFile(String experimentId, String runId, String name) {
        return artifactsDir(experimentId, runId).resolve(name);
    }

    // ========== Path-A header (A5) ==========

    /**
     * Writes the schema-version header as the <em>first</em> line of a Path-A stream file the first
     * time it is created (A5): {@code {"@type":"header","schemaVersion":N,"stream":"…","runId":"…"}}.
     * Readers ({@link #loadEvents}/{@link #loadDerivedEvents}) and the trace loader skip any
     * {@code @type:"header"} line, so this is additive and tolerant. Not synchronized: a run is
     * written by one recorder, and on the rare concurrent first-write the duplicate header is simply
     * skipped on read (so correctness holds; at worst a cosmetic extra header line).
     */
    private void writeHeaderIfNew(Path file, String stream, String runId) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("@type", HEADER_TYPE);
        header.put("schemaVersion", SCHEMA_VERSION);
        header.put("stream", stream);
        if (runId != null) {
            header.put("runId", runId);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(eventMapper.writeValueAsString(header));
            writer.newLine();
        }
    }

    private static boolean isHeader(JsonNode node) {
        return node != null && HEADER_TYPE.equals(node.path("@type").asText(null));
    }

    // ========== Experiment Operations ==========

    /**
     * {@inheritDoc}
     *
     * <p>Writes {@code experiment.json}, replacing any earlier version.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code experiment.json}.
     *
     * @throws UncheckedIOException if the file exists but cannot be read
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Lists the experiment directories that contain an {@code experiment.json}.
     *
     * @throws UncheckedIOException if the directory cannot be listed or a file cannot be read
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Writes {@code run.json}, replacing any earlier version.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code run.json}.
     *
     * @throws UncheckedIOException if the file exists but cannot be read
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Lists the run directories of the experiment that contain a {@code run.json}.
     *
     * @throws UncheckedIOException if the directory cannot be listed or a file cannot be read
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Appends one line to {@code events.jsonl}, first writing the header line if the file
     * is new.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
    @Override
    public void appendEvent(String experimentId, String runId, JournalEvent event) {
        try {
            Path file = eventsFile(experimentId, runId);
            writeHeaderIfNew(file, "events", runId);

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

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code events.jsonl}, skipping the header line.
     *
     * @throws UncheckedIOException if the file exists but cannot be read or parsed
     */
    @Override
    public List<JournalEvent> loadEvents(String experimentId, String runId) {
        Path file = eventsFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<JournalEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = eventMapper.readTree(line);
                if (isHeader(node)) {
                    continue; // A5 schema-version header line — not an execution event
                }
                events.add(eventMapper.treeToValue(node, JournalEvent.class));
            }
            return events;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load events for run: " + runId, e);
        }
    }

    // ========== Feedback Operations ==========

    /**
     * {@inheritDoc}
     *
     * <p>Appends one line to {@code feedback.jsonl}. This file has no header line.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code feedback.jsonl}.
     *
     * @throws UncheckedIOException if the file exists but cannot be read or parsed
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Appends one line to {@code analysis.jsonl}, first writing the header line if the file
     * is new.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
    @Override
    public void appendDerivedEvent(String experimentId, String runId, DerivedEvent event) {
        try {
            Path file = analysisFile(experimentId, runId);
            writeHeaderIfNew(file, "analysis", runId);

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
     * Returns {@code true}: derived events are written to {@code analysis.jsonl} and outlive the
     * JVM.
     *
     * @return {@code true}
     */
    @Override
    public boolean persistsDerivedEvents() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code analysis.jsonl}, skipping the header line.
     *
     * @throws UncheckedIOException if the file exists but cannot be read or parsed
     */
    @Override
    public List<DerivedEvent> loadDerivedEvents(String experimentId, String runId) {
        Path file = analysisFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<DerivedEvent> derived = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = eventMapper.readTree(line);
                if (isHeader(node)) {
                    continue; // A5 schema-version header line — not a derived event
                }
                derived.add(eventMapper.treeToValue(node, DerivedEvent.class));
            }
            return derived;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load derived events for run: " + runId, e);
        }
    }

    // ========== Artifact Operations ==========

    /**
     * {@inheritDoc}
     *
     * <p>Writes the content to {@code artifacts/{name}}, replacing a file of the same name.
     *
     * @throws UncheckedIOException if the file cannot be written
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Reads {@code artifacts/{name}}.
     *
     * @throws UncheckedIOException if the file exists but cannot be read
     */
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

    /**
     * {@inheritDoc}
     *
     * <p>Lists the file names in the run's {@code artifacts} directory.
     *
     * @throws UncheckedIOException if the directory cannot be listed
     */
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
     * {@inheritDoc}
     *
     * <p>The registration applies to this storage object only. Register a type before loading
     * any file that contains it.
     */
    @Override
    public void registerEventSubtype(String typeName, Class<? extends JournalEvent> cls) {
        eventMapper.registerSubtypes(new NamedType(cls, typeName));
    }

    /**
     * Returns the directory this storage writes under.
     *
     * @return the base directory given to the constructor
     */
    public Path baseDir() {
        return baseDir;
    }
}
