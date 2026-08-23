package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GrokSessionParserTest {

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void parsesAndPairsTheVerifiedAcpShapedFixture() throws Exception {
        GrokPhaseCapture capture = GrokSessionParser.parse(fixture("grok-streaming-json.jsonl"),
                "grok-fixture", "read both files");

        assertThat(capture.toolUses()).hasSize(2);
        assertThat(capture.toolUses()).extracting(GrokToolUseRecord::id).doesNotHaveDuplicates();
        assertThat(capture.toolUses()).extracting(GrokToolUseRecord::name)
                .containsExactly("read_file", "read_file");
        assertThat(capture.toolUses()).extracting(GrokToolUseRecord::classification)
                .containsOnly("read");
        assertThat(capture.toolUses()).extracting(GrokToolUseRecord::kind)
                .containsOnly(ToolKind.READ);
        assertThat(capture.toolUses()).allSatisfy(tool -> {
            assertThat(tool.output()).isNotNull();
            assertThat(tool.status()).isEqualTo("completed");
            assertThat(tool.isError()).isFalse();
        });
        assertThat(capture.inputTokens()).isEqualTo(10_334);
        assertThat(capture.cacheReadInputTokens()).isEqualTo(21_632);
        assertThat(capture.outputTokens()).isEqualTo(116);
        assertThat(capture.thinkingTokens()).isEqualTo(58);
        assertThat(capture.totalCostUsd()).isCloseTo(0.0054706, within(1e-12));
        assertThat(capture.model()).isEqualTo("grok-4.6-build");
    }

    @Test
    void liveFixtureProducesDistinctToolStatesAndJournalEvents() throws Exception {
        GrokPhaseCapture capture = GrokSessionParser.parse(fixture("grok-multistate-streaming-json.jsonl"),
                "grok-multistate", "list then read");

        assertThat(capture.toolUses()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(capture.toolUses()).extracting(GrokToolUseRecord::name)
                .contains("run_terminal_command", "read_file")
                .doesNotHaveDuplicates();
        assertThat(capture.toolUses()).extracting(GrokToolUseRecord::kind)
                .contains(ToolKind.EXECUTE, ToolKind.READ)
                .doesNotHaveDuplicates();

        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        String runId;
        try (Run run = Journal.run("grok-experiment").start()) {
            runId = run.id();
            new GrokRunRecorder(run).recordPhase(capture);
        }

        List<JournalEvent> events = storage.loadEvents("grok-experiment", runId);
        assertThat(events).filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).toolName())
                .containsExactly("run_terminal_command", "read_file");
        assertThat(events).filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).kind())
                .containsExactly(ToolKind.EXECUTE, ToolKind.READ);

        List<DerivedEvent> derived = storage.loadDerivedEvents("grok-experiment", runId);
        assertThat(derived).hasSize(2).allSatisfy(event -> assertThat(event).isInstanceOf(StepCostEvent.class));
        double attributed = derived.stream()
                .map(StepCostEvent.class::cast)
                .mapToDouble(StepCostEvent::attributedCostUsd)
                .sum();
        assertThat(attributed).isCloseTo(capture.totalCostUsd(), within(1e-12));
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(GrokSessionParserTest.class.getResource("/fixtures/" + name).toURI());
    }
}
