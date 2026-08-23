package io.github.markpollack.journal.antigravity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AntigravitySessionParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void parsesTheVerifiedToolErrorFixtureWithoutCollapsingStates() throws Exception {
        AntigravityPhaseCapture capture = AntigravitySessionParser.parse(
                fixture("antigravity-stream-json.jsonl"), "antigravity-errors", "inspect workspace");

        assertThat(capture.toolUses()).hasSize(2);
        assertThat(capture.toolUses()).extracting(AntigravityToolUseRecord::kind)
                .containsExactly(ToolKind.EXECUTE, ToolKind.READ)
                .doesNotHaveDuplicates();
        assertThat(capture.toolUses()).extracting(AntigravityToolUseRecord::name)
                .containsExactly("run_command", "list_dir");
        assertThat(capture.toolUses()).allSatisfy(tool -> {
            assertThat(tool.isError()).isTrue();
            assertThat(tool.state()).isEqualTo("ERROR");
            assertThat(tool.errorMessage()).isNotBlank();
        });
        assertThat(capture.isError()).isTrue();
        assertThat(capture.model()).isEqualTo("gemini-3.1-pro-high");
        assertThat(capture.inputTokens()).isEqualTo(11_982);
        assertThat(capture.outputTokens()).isEqualTo(1_222);
    }

    @Test
    void cleanLiveFixtureProducesTwoSuccessfulStatesAndJournalEvents() throws Exception {
        AntigravityPhaseCapture capture = AntigravitySessionParser.parse(
                fixture("antigravity-clean-stream-json.jsonl"), "antigravity-clean", "list then read");

        assertThat(capture.toolUses()).hasSize(2);
        assertThat(capture.toolUses()).extracting(AntigravityToolUseRecord::kind)
                .containsOnly(ToolKind.READ);
        assertThat(capture.toolUses()).allSatisfy(tool -> {
            assertThat(tool.isError()).isFalse();
            assertThat(tool.state()).isEqualTo("DONE");
            assertThat(tool.output()).isNotNull();
        });
        assertThat(capture.isError()).isFalse();
        assertThat(capture.status()).isEqualTo("SUCCESS");

        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        String runId;
        try (Run run = Journal.run("antigravity-experiment").start()) {
            runId = run.id();
            new AntigravityRunRecorder(run).recordPhase(capture);
        }

        assertThat(storage.loadEvents("antigravity-experiment", runId))
                .filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).toolName())
                .containsExactly("list_dir", "view_file");
        assertThat(storage.loadEvents("antigravity-experiment", runId))
                .filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).kind())
                .containsOnly(ToolKind.READ);
        assertThat(storage.loadDerivedEvents("antigravity-experiment", runId))
                .hasSize(2)
                .allSatisfy(event -> assertThat(event).isInstanceOf(StepCostEvent.class));
    }

    @Test
    void classifierCoversTheWholeAdvertisedAntigravityVocabulary() throws Exception {
        String initLine = Files.readAllLines(fixture("antigravity-clean-stream-json.jsonl")).get(0);
        JsonNode tools = MAPPER.readTree(initLine).path("init").path("tools");
        Set<String> advertised = new LinkedHashSet<>();
        tools.forEach(tool -> advertised.add(tool.asText()));

        assertThat(AntigravityToolClassifier.knownToolNames())
                .containsExactlyInAnyOrderElementsOf(advertised);
        assertThat(advertised).allSatisfy(name ->
                assertThat(AntigravityToolClassifier.classify(name)).isNotNull());
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(AntigravitySessionParserTest.class.getResource("/fixtures/" + name).toURI());
    }
}
