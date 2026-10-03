package io.github.markpollack.journal.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.test.TestEvents;
import io.github.markpollack.journal.trace.AttributionMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading records written by a newer version, and event types registered outside journal-core.
 */
class JsonFileStorageReadCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void eventWithAnUnknownFieldLoads(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendEvent("exp", "run", TestEvents.bashSuccess());
        addFieldToLastLine(dir.resolve("experiments/exp/runs/run/events.jsonl"));

        assertThat(storage.loadEvents("exp", "run")).singleElement().isInstanceOf(ToolCallEvent.class);
    }

    @Test
    void runRecordWithAnUnknownFieldLoads(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.saveRun(RunData.builder().id("run").experimentId("exp").name("r").status(RunStatus.FINISHED)
                .build());
        Path runFile = dir.resolve("experiments/exp/runs/run/run.json");
        ObjectNode run = (ObjectNode) MAPPER.readTree(runFile.toFile());
        run.put("fieldFromANewerVersion", 1);
        MAPPER.writeValue(runFile.toFile(), run);

        assertThat(storage.loadRun("exp", "run")).hasValueSatisfying(r -> assertThat(r.id()).isEqualTo("run"));
    }

    @Test
    void derivedEventWithAnUnknownFieldLoads(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendDerivedEvent("exp", "run", stepCost());
        addFieldToLastLine(dir.resolve("experiments/exp/runs/run/analysis.jsonl"));

        assertThat(storage.loadDerivedEvents("exp", "run")).singleElement().isInstanceOf(StepCostEvent.class);
    }

    @Test
    void unknownEnumValueStillFails(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendDerivedEvent("exp", "run", stepCost());
        Path analysis = dir.resolve("experiments/exp/runs/run/analysis.jsonl");
        List<String> lines = Files.readAllLines(analysis);
        ObjectNode last = (ObjectNode) MAPPER.readTree(lines.get(lines.size() - 1));
        last.put("attributionMethod", "A_METHOD_FROM_A_NEWER_VERSION");
        lines.set(lines.size() - 1, MAPPER.writeValueAsString(last));
        Files.write(analysis, lines);

        assertThatThrownBy(() -> storage.loadDerivedEvents("exp", "run")).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void registrationSurvivesConfigure(@TempDir Path dir) throws Exception {
        Journal.configure(new JsonFileStorage(dir));
        Journal.registerEventType("compat_survives_configure", CustomEvent.class);

        Journal.configure(new JsonFileStorage(dir));
        writeCustomEventLine(dir, "compat_survives_configure");

        assertThat(Journal.storage().loadEvents("exp", "run")).singleElement().isInstanceOf(CustomEvent.class);
    }

    @Test
    void registrationBeforeConfigureIsKept(@TempDir Path dir) throws Exception {
        Journal.registerEventType("compat_before_configure", CustomEvent.class);

        Journal.configure(new JsonFileStorage(dir));
        writeCustomEventLine(dir, "compat_before_configure");

        assertThat(Journal.storage().loadEvents("exp", "run")).singleElement().isInstanceOf(CustomEvent.class);
    }

    @Test
    void lateRegistrationTakesEffect(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendEvent("exp", "builtin", TestEvents.bashSuccess());
        assertThat(storage.loadEvents("exp", "builtin")).hasSize(1);
        writeCustomEventLine(dir, "compat_late_registration");
        assertThatThrownBy(() -> storage.loadEvents("exp", "run")).isInstanceOf(UncheckedIOException.class);

        storage.registerEventSubtype("compat_late_registration", CustomEvent.class);

        assertThat(storage.loadEvents("exp", "run")).singleElement().isInstanceOf(CustomEvent.class);
    }

    @Test
    void registrationOnOneStorageReachesAnotherAlreadyInUse(@TempDir Path dir) throws Exception {
        JsonFileStorage reader = new JsonFileStorage(dir);
        reader.appendEvent("exp", "builtin", TestEvents.bashSuccess());
        assertThat(reader.loadEvents("exp", "builtin")).hasSize(1);

        new JsonFileStorage(dir).registerEventSubtype("compat_other_instance", CustomEvent.class);
        writeCustomEventLine(dir, "compat_other_instance");

        assertThat(reader.loadEvents("exp", "run")).singleElement().isInstanceOf(CustomEvent.class);
    }

    @Test
    void appendingANonBuiltInDerivedEventFailsBeforeWriting(@TempDir Path dir) {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendDerivedEvent("exp", "run", stepCost());

        assertThatThrownBy(() -> storage.appendDerivedEvent("exp", "run", new CustomDerivedEvent(Instant.now())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CustomDerivedEvent.class.getName())
                .hasMessageContaining("step_cost");

        assertThat(storage.loadDerivedEvents("exp", "run")).singleElement().isInstanceOf(StepCostEvent.class);
    }

    @Test
    void inMemoryStorageStillAcceptsANonBuiltInDerivedEvent() {
        InMemoryStorage storage = new InMemoryStorage();

        storage.appendDerivedEvent("exp", "run", new CustomDerivedEvent(Instant.now()));

        assertThat(storage.loadDerivedEvents("exp", "run")).singleElement().isInstanceOf(CustomDerivedEvent.class);
    }

    private static StepCostEvent stepCost() {
        return new StepCostEvent(Instant.parse("2026-01-01T00:00:00Z"), "run", "toolu_1", "msg_1", "Bash", 10, 20,
                0.01, 0.01, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code");
    }

    private static void addFieldToLastLine(Path file) throws Exception {
        List<String> lines = new ArrayList<>(Files.readAllLines(file));
        ObjectNode last = (ObjectNode) MAPPER.readTree(lines.get(lines.size() - 1));
        last.put("fieldFromANewerVersion", "ignored");
        lines.set(lines.size() - 1, MAPPER.writeValueAsString(last));
        Files.write(file, lines);
    }

    private static void writeCustomEventLine(Path dir, String typeName) throws Exception {
        Path file = dir.resolve("experiments/exp/runs/run/events.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"@type\":\"" + typeName + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"note\":\"n\"}\n");
    }

    record CustomEvent(Instant timestamp, String note) implements JournalEvent {
        @Override
        public String type() {
            return "custom";
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of("note", note);
        }
    }

    record CustomDerivedEvent(Instant timestamp) implements DerivedEvent {
        @Override
        public String type() {
            return "custom_derived";
        }

        @Override
        public String runId() {
            return "run";
        }

        @Override
        public String stepId() {
            return null;
        }

        @Override
        public Map<String, Object> toMap() {
            return Map.of();
        }
    }
}
