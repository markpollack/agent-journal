package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.derived.StepOutcomeEvent;
import io.github.markpollack.journal.event.FeedbackEvent;
import io.github.markpollack.journal.event.JournalEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * {@link #SCHEMA_VERSION} and, since 1.11.0, the name and version of the library that wrote the
 * file ({@code producer}, {@code producerVersion}); {@code feedback.jsonl} has no header. A file
 * without a producer version was written by 1.10.1 or earlier. The load methods skip header
 * lines, refuse a file whose schema version is newer than {@link #SCHEMA_VERSION}, and read the
 * whole file into memory. Event types defined outside journal-core must be
 * registered with {@link #registerEventSubtype(String, Class)} before they can be loaded; a
 * registration applies to every storage in the process, including ones created later.
 *
 * <p>Reading tolerates <em>unknown fields only</em>: a field that this version does not know,
 * such as one added by a newer version, is ignored in run, experiment, event, derived event and
 * feedback records. Everything else still fails the load with {@link UncheckedIOException}: an
 * unknown {@code @type} name, an unknown enum value (other than a tool kind, which reads as
 * {@code other}), and a line that is not valid JSON.
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
     * {@code analysis.jsonl}, so a reader can tell which format a file uses. It describes what a
     * reader must know to parse a file: the keys, their types and units, the enum vocabularies
     * and the documented definition of each field. It changes when a file written by the new
     * version could be misread by a reader that correctly implements the previous version's
     * documented format: a key renamed, removed or moved; a value's type or unit changed; a key
     * made to carry a quantity its definition did not cover; or a stored enum given a value (a
     * reader that predates the value fails on it, and ignoring unknown fields does not help; a
     * tool kind is the exception, because an unknown one reads as {@code other}).
     *
     * <p>It does not change when the producer writes better values under an unchanged
     * definition: a value that was wrong is corrected, or a definition that allowed several
     * readings is narrowed so that new values still satisfy the old one. Such changes are told
     * apart by {@code producerVersion} on the same header line, and the release notes list them.
     * Nor does a new field change it; readers from 1.11.0 ignore fields they do not know, and
     * earlier readers reject them in a record. The load methods refuse a file with a newer
     * schema version than this one, so a reader never half-understands a changed format.
     *
     * <p>Version 1.11.0 corrects values (run status on an early exit, Claude Code token counts,
     * Codex input tokens, unmeasured tool durations) and adds keys, and changes no key, type,
     * unit, definition or vocabulary, so the version is still 1. Trace files have their own,
     * separate schema version.
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
    private ObjectMapper eventMapper;
    private int eventMapperGeneration;

    /**
     * Creates a storage that writes under the given directory. Nothing is written until the first
     * save; directories are created as needed.
     *
     * @param baseDir the directory to write under
     */
    public JsonFileStorage(Path baseDir) {
        this.baseDir = baseDir;
        this.objectMapper = createObjectMapper();
        eventMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }

    private ObjectMapper createEventMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // No indent for JSONL - one line per event
        mapper.disable(SerializationFeature.INDENT_OUTPUT);
        // Type info is handled via @JsonTypeInfo on JournalEvent interface
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        EventTypeRegistry.types().forEach((name, cls) -> mapper.registerSubtypes(new NamedType(cls, name)));
        return mapper;
    }

    // A new mapper rather than registerSubtypes on the old one: Jackson caches the reader for
    // JournalEvent after the first load, so a subtype added later would not be seen.
    private synchronized ObjectMapper eventMapper() {
        int generation = EventTypeRegistry.generation();
        if (eventMapper == null || generation != eventMapperGeneration) {
            eventMapper = createEventMapper();
            eventMapperGeneration = generation;
        }
        return eventMapper;
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
     * time it is created (A5): {@code {"@type":"header","schemaVersion":N,"stream":"…","runId":"…",
     * "producer":"agent-journal","producerVersion":"…"}}.
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
        header.put("producer", ProducerVersion.PRODUCER);
        header.put("producerVersion", ProducerVersion.value());
        try (BufferedWriter writer = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(eventMapper().writeValueAsString(header));
            writer.newLine();
        }
    }

    private static boolean isHeader(JsonNode node) {
        return node != null && HEADER_TYPE.equals(node.path("@type").asText(null));
    }

    /**
     * Refuses a file whose header carries a newer schema version than {@link #SCHEMA_VERSION}.
     * Such a file may have renamed fields or new enum values, and reading it while ignoring
     * unknown fields would return wrong values without an error.
     */
    private static void requireReadableSchema(JsonNode header, String fileName) throws IOException {
        JsonNode declared = header.path("schemaVersion");
        if (!declared.isMissingNode() && !declared.isNull() && !declared.isIntegralNumber()) {
            throw new IOException(fileName + " has a schema version that is not a whole number: " + declared);
        }
        int version = declared.asInt(SCHEMA_VERSION);
        if (version > SCHEMA_VERSION) {
            throw new IOException(fileName + " has schema version " + version + ", written by "
                    + header.path("producer").asText("an unknown producer") + " "
                    + header.path("producerVersion").asText("of unknown version")
                    + "; this version reads up to " + SCHEMA_VERSION);
        }
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

            String json = eventMapper().writeValueAsString(event);
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
     * @throws UncheckedIOException if the file exists but cannot be read or parsed, or its header
     *         carries a newer schema version than {@link #SCHEMA_VERSION}
     */
    @Override
    public List<JournalEvent> loadEvents(String experimentId, String runId) {
        Path file = eventsFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            ObjectMapper mapper = eventMapper();
            List<JournalEvent> events = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                if (isHeader(node)) {
                    requireReadableSchema(node, "events.jsonl");
                    continue; // A5 schema-version header line — not an execution event
                }
                events.add(mapper.treeToValue(node, JournalEvent.class));
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

            String json = eventMapper().writeValueAsString(feedback);
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
            ObjectMapper mapper = eventMapper();
            List<FeedbackEvent> feedbackEvents = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    feedbackEvents.add(mapper.readValue(line, FeedbackEvent.class));
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
     * is new. Only the built-in derived events, {@link StepCostEvent} and
     * {@link StepOutcomeEvent}, can be stored here: no other type could be read back, and one such
     * line would make the run's whole {@code analysis.jsonl} unreadable.
     *
     * @throws IllegalArgumentException if {@code event} is not a {@link StepCostEvent} or a
     *         {@link StepOutcomeEvent}; nothing is written
     * @throws UncheckedIOException if the file cannot be written
     */
    @Override
    public void appendDerivedEvent(String experimentId, String runId, DerivedEvent event) {
        if (!(event instanceof StepCostEvent) && !(event instanceof StepOutcomeEvent)) {
            throw new IllegalArgumentException("JsonFileStorage stores only the built-in derived events "
                    + "(step_cost, step_outcome), not " + event.getClass().getName()
                    + ": it could not be read back. Use InMemoryStorage for other DerivedEvent types.");
        }
        try {
            Path file = analysisFile(experimentId, runId);
            writeHeaderIfNew(file, "analysis", runId);

            String json = eventMapper().writeValueAsString(event);
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
     * @throws UncheckedIOException if the file exists but cannot be read or parsed, or its header
     *         carries a newer schema version than {@link #SCHEMA_VERSION}
     */
    @Override
    public List<DerivedEvent> loadDerivedEvents(String experimentId, String runId) {
        Path file = analysisFile(experimentId, runId);
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            ObjectMapper mapper = eventMapper();
            List<DerivedEvent> derived = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                if (isHeader(node)) {
                    requireReadableSchema(node, "analysis.jsonl");
                    continue; // A5 schema-version header line — not a derived event
                }
                derived.add(mapper.treeToValue(node, DerivedEvent.class));
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
     * <p>The registration applies to every storage in this process, including ones already in
     * use and ones created later, so it is kept when
     * {@link io.github.markpollack.journal.Journal#configure} swaps the storage. It takes effect on
     * the next load, even after files have been loaded. Writing does not need it.
     */
    @Override
    public void registerEventSubtype(String typeName, Class<? extends JournalEvent> cls) {
        EventTypeRegistry.register(typeName, cls);
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
