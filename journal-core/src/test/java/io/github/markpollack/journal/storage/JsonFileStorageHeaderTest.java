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
}
