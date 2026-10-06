package io.github.markpollack.journal.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.test.TestEvents;
import io.github.markpollack.journal.trace.AttributionMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A5: each Path-A stream ({@code events.jsonl} + {@code analysis.jsonl}) carries a schema-version
 * {@code @type:"header"} as its first line so a reader (e.g. agent-control-theory) can version-route
 * where it already reads. The header is skipped by {@code loadEvents}/{@code loadDerivedEvents}, so
 * it is additive and invisible to existing consumers.
 */
@DisplayName("JsonFileStorage Path-A schema header (A5)")
class JsonFileStorageHeaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("events.jsonl: header is the first line; loadEvents skips it")
    void eventsHeader(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendEvent("exp", "run", TestEvents.llmCall());
        storage.appendEvent("exp", "run", TestEvents.bashSuccess());

        List<String> lines = Files.readAllLines(dir.resolve("experiments/exp/runs/run/events.jsonl"));
        JsonNode header = mapper.readTree(lines.get(0));
        assertThat(header.path("@type").asText()).isEqualTo(JsonFileStorage.HEADER_TYPE);
        assertThat(header.path("schemaVersion").asInt()).isEqualTo(JsonFileStorage.SCHEMA_VERSION);
        assertThat(header.path("stream").asText()).isEqualTo("events");
        assertThat(header.path("runId").asText()).isEqualTo("run");

        // The header is not an execution event — loadEvents returns only the two real events.
        assertThat(storage.loadEvents("exp", "run")).hasSize(2);
    }

    @Test
    @DisplayName("analysis.jsonl: header is the first line; loadDerivedEvents skips it")
    void analysisHeader(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendDerivedEvent("exp", "run", new StepCostEvent(Instant.now(), "run", "toolu_1", "msg_1",
                "Bash", 10, 20, 0.01, 0.01, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code"));

        List<String> lines = Files.readAllLines(dir.resolve("experiments/exp/runs/run/analysis.jsonl"));
        JsonNode header = mapper.readTree(lines.get(0));
        assertThat(header.path("@type").asText()).isEqualTo("header");
        assertThat(header.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(header.path("stream").asText()).isEqualTo("analysis");

        List<DerivedEvent> derived = storage.loadDerivedEvents("exp", "run");
        assertThat(derived).hasSize(1);
        assertThat(((StepCostEvent) derived.get(0)).stepId()).isEqualTo("toolu_1");
    }

    @Test
    @DisplayName("the schema version is independent of the Path-B trace schemaVersion")
    void independentVersioning() {
        // Documented invariant: Path-A starts at 1; the trace's header is its own (2). Different artifacts.
        assertThat(JsonFileStorage.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("the header names the producer and its version, so a reader can tell which release wrote a file")
    void headerCarriesProducerVersion(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendEvent("exp", "run", TestEvents.llmCall());
        storage.appendDerivedEvent("exp", "run", new StepCostEvent(Instant.now(), "run", "toolu_1", "msg_1",
                "Bash", 10, 20, 0.01, 0.01, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code"));

        for (String stream : List.of("events", "analysis")) {
            JsonNode header = mapper.readTree(
                    Files.readAllLines(dir.resolve("experiments/exp/runs/run/" + stream + ".jsonl")).get(0));
            assertThat(header.path("producer").asText()).isEqualTo("agent-journal");
            // The build writes the project version into the jar; an unfiltered placeholder is a build error.
            assertThat(header.path("producerVersion").asText()).matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
        }
    }

    @Test
    @DisplayName("a file written before the producer version existed still loads")
    void headerWithoutProducerVersionLoads(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendEvent("exp", "run", TestEvents.bashSuccess());
        Path events = dir.resolve("experiments/exp/runs/run/events.jsonl");
        List<String> lines = new java.util.ArrayList<>(Files.readAllLines(events));
        lines.set(0, "{\"@type\":\"header\",\"schemaVersion\":1,\"stream\":\"events\",\"runId\":\"run\"}");
        Files.write(events, lines);

        assertThat(new JsonFileStorage(dir).loadEvents("exp", "run")).hasSize(1);
    }

    @Test
    @DisplayName("a file with a newer schema version is refused with a clear error, not half-read")
    void newerSchemaVersionIsRefused(@TempDir Path dir) throws Exception {
        JsonFileStorage storage = new JsonFileStorage(dir);
        storage.appendEvent("exp", "run", TestEvents.bashSuccess());
        storage.appendDerivedEvent("exp", "run", new StepCostEvent(Instant.now(), "run", "toolu_1", "msg_1",
                "Bash", 10, 20, 0.01, 0.01, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code"));
        int newer = JsonFileStorage.SCHEMA_VERSION + 1;
        for (String stream : List.of("events", "analysis")) {
            Path file = dir.resolve("experiments/exp/runs/run/" + stream + ".jsonl");
            List<String> lines = new java.util.ArrayList<>(Files.readAllLines(file));
            lines.set(0, "{\"@type\":\"header\",\"schemaVersion\":" + newer + ",\"stream\":\"" + stream
                    + "\",\"producer\":\"agent-journal\",\"producerVersion\":\"9.0.0\"}");
            Files.write(file, lines);
        }
        JsonFileStorage reader = new JsonFileStorage(dir);

        assertThatThrownBy(() -> reader.loadEvents("exp", "run"))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasRootCauseMessage("events.jsonl has schema version " + newer + ", written by agent-journal 9.0.0;"
                        + " this version reads up to " + JsonFileStorage.SCHEMA_VERSION);
        assertThatThrownBy(() -> reader.loadDerivedEvents("exp", "run"))
                .isInstanceOf(java.io.UncheckedIOException.class)
                .hasRootCauseMessage("analysis.jsonl has schema version " + newer + ", written by agent-journal 9.0.0;"
                        + " this version reads up to " + JsonFileStorage.SCHEMA_VERSION);
    }
}
