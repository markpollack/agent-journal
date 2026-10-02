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

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Files;
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

    @Test
    void streamCutOffBeforeItsEndLineIsRecordedAsAnIncompleteError() throws Exception {
        List<String> lines = Files.readAllLines(fixture("grok-streaming-json.jsonl"));
        String truncated = String.join("\n", lines.subList(0, lines.size() - 1)) + "\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(truncated)),
                "grok-fixture", "read both files");

        assertThat(capture.isError()).isTrue();
        assertThat(capture.stopReason()).isEqualTo("incomplete");
        assertThat(capture.toolUses()).isNotEmpty();
    }


    @Test
    void modelIsTheOneThatUsedTheMostTokensWhenTheEndLineNamesSeveral() throws Exception {
        List<String> lines = Files.readAllLines(fixture("grok-streaming-json.jsonl"));
        String end = lines.get(lines.size() - 1);
        String small = "\"modelUsage\":{\"grok-small\":{\"inputTokens\":10,\"outputTokens\":1,"
                + "\"cacheReadInputTokens\":0,\"cacheCreationInputTokens\":0,\"modelCalls\":1,\"costUSD\":0.0001},";
        assertThat(end).contains("\"modelUsage\":{");
        lines.set(lines.size() - 1, end.replace("\"modelUsage\":{", small));

        GrokPhaseCapture capture = GrokSessionParser.parse(
                new BufferedReader(new StringReader(String.join("\n", lines) + "\n")), "grok-fixture", "p");

        assertThat(capture.model()).isEqualTo("grok-4.6-build");
    }

    @Test
    void toolCallThatIsBornFailedWithNoUpdateIsAnError() throws Exception {
        String stream = String.join("\n",
                "{\"type\":\"tool_call\",\"toolCallId\":\"call-1\",\"toolName\":\"run_terminal_command\","
                        + "\"kind\":\"execute\",\"status\":\"failed\",\"rawInput\":{\"command\":\"false\"},"
                        + "\"rawOutput\":{\"error\":{\"message\":\"exit 1\"}}}",
                "{\"type\":\"end\",\"stopReason\":\"end_turn\"}") + "\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(stream)), "p", "q");

        GrokToolUseRecord tool = capture.toolUses().get(0);
        assertThat(tool.status()).isEqualTo("failed");
        assertThat(tool.isError()).isTrue();
        assertThat(tool.errorMessage()).isEqualTo("exit 1");
    }

    @Test
    void toolCallThatFailsAndThenCompletesIsNotAnError() throws Exception {
        String stream = String.join("\n",
                "{\"type\":\"tool_call\",\"toolCallId\":\"call-1\",\"toolName\":\"read_file\","
                        + "\"kind\":\"read\",\"status\":\"pending\",\"rawInput\":{\"target_file\":\"A.txt\"}}",
                "{\"type\":\"tool_call_update\",\"toolCallId\":\"call-1\",\"status\":\"failed\","
                        + "\"rawOutput\":{\"message\":\"busy\"}}",
                "{\"type\":\"tool_call_update\",\"toolCallId\":\"call-1\",\"status\":\"completed\","
                        + "\"rawOutput\":{\"content\":\"text\"}}",
                "{\"type\":\"end\",\"stopReason\":\"end_turn\"}") + "\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(stream)), "p", "q");

        GrokToolUseRecord tool = capture.toolUses().get(0);
        assertThat(tool.status()).isEqualTo("completed");
        assertThat(tool.isError()).isFalse();
        assertThat(tool.errorMessage()).isNull();
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(GrokSessionParserTest.class.getResource("/fixtures/" + name).toURI());
    }
}
