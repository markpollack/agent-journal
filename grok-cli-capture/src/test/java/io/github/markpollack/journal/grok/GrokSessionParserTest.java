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

    @Test
    void endLineWithoutCostOrSomeTokensLeavesThemNotReportedInsteadOfZero() throws Exception {
        String stream = String.join("\n",
                "{\"type\":\"text\",\"data\":\"hi\"}",
                "{\"type\":\"end\",\"stopReason\":\"end_turn\",\"usage\":{\"input_tokens\":7,"
                        + "\"output_tokens\":3}}") + "\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(stream)), "p", "q");

        assertThat(capture.costReported()).isFalse();
        assertThat(capture.inputTokens()).isEqualTo(7);
        assertThat(capture.outputTokens()).isEqualTo(3);
        assertThat(capture.thinkingTokens()).isEqualTo(GrokPhaseCapture.NOT_REPORTED);
        assertThat(capture.cacheReadInputTokens()).isEqualTo(GrokPhaseCapture.NOT_REPORTED);
        assertThat(capture.tokensNotReported()).containsExactly("thinking", "cacheCreation", "cacheRead");
    }

    @Test
    void realEndLineCostAndEveryTokenCountAreReported() throws Exception {
        GrokPhaseCapture capture = GrokSessionParser.parse(fixture("grok-streaming-json.jsonl"), "p", "q");

        assertThat(capture.costReported()).isTrue();
        assertThat(capture.tokensNotReported()).isEmpty();
    }

    @Test
    void reportedZeroCostIsNotMistakenForNotReported() throws Exception {
        String stream = "{\"type\":\"end\",\"stopReason\":\"end_turn\",\"total_cost_usd\":0.0,"
                + "\"usage\":{\"input_tokens\":0,\"output_tokens\":0,\"reasoning_tokens\":0,"
                + "\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0}}\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(stream)), "p", "q");

        assertThat(capture.costReported()).isTrue();
        assertThat(capture.totalCostUsd()).isZero();
        assertThat(capture.tokensNotReported()).isEmpty();
    }

    @Test
    void cutOffStreamDoesNotClaimACost() throws Exception {
        List<String> lines = Files.readAllLines(fixture("grok-streaming-json.jsonl"));
        String truncated = String.join("\n", lines.subList(0, lines.size() - 1)) + "\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(truncated)),
                "p", "q");

        assertThat(capture.costReported()).isFalse();
        assertThat(capture.totalCostUsd()).isZero();
    }

    @Test
    void aPlainTextLineIsKeptWholeAndTheRestOfTheStreamIsStillCaptured() throws Exception {
        List<String> lines = Files.readAllLines(fixture("grok-streaming-json.jsonl"));
        lines.add(1, "Not signed in. Run `grok login`.");

        GrokPhaseCapture capture = GrokSessionParser.parse(
                new BufferedReader(new StringReader(String.join("\n", lines) + "\n")), "p", "q");

        assertThat(capture.vendorEvents()).anySatisfy(e -> {
            assertThat(e.kind()).isEqualTo(GrokVendorEvent.NON_JSON_LINE);
            assertThat(e.line()).isEqualTo(2);
            assertThat(e.payload()).containsEntry("text", "Not signed in. Run `grok login`.");
        });
        assertThat(capture.toolUses()).hasSize(2);
        assertThat(capture.costReported()).isTrue();
        assertThat(capture.isError()).isFalse();
    }

    @Test
    void aStreamThatIsOnlyPlainTextIsAnIncompleteCaptureNotAnException() throws Exception {
        GrokPhaseCapture capture = GrokSessionParser.parse(
                new BufferedReader(new StringReader("Not signed in\nPlease log in\n")), "p", "q");

        assertThat(capture.isError()).isTrue();
        assertThat(capture.stopReason()).isEqualTo("incomplete");
        assertThat(capture.vendorEvents()).extracting(GrokVendorEvent::kind)
                .containsExactly(GrokVendorEvent.NON_JSON_LINE, GrokVendorEvent.NON_JSON_LINE);
        assertThat(capture.costReported()).isFalse();
    }

    @Test
    void everyLineThatFeedsNoFieldIsKeptWholeInOrder() throws Exception {
        Path file = fixture("grok-multistate-streaming-json.jsonl");
        java.util.Set<String> consumed = java.util.Set.of("text", "thought", "tool_call", "tool_call_update", "usage", "end");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<Object> expected = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(line);
            String type = node.path("type").asText(null);
            boolean needsId = "tool_call".equals(type) || "tool_call_update".equals(type);
            boolean fed = type != null && consumed.contains(type) && (!needsId || node.hasNonNull("toolCallId"));
            if (!fed) {
                expected.add(mapper.convertValue(node, java.util.Map.class));
            }
        }

        GrokPhaseCapture capture = GrokSessionParser.parse(file, "p", "q");

        assertThat(capture.vendorEvents()).extracting(GrokVendorEvent::payload).containsExactlyElementsOf(
                expected.stream().map(o -> (java.util.Map<String, Object>) o).toList());
    }

    @Test
    void anUnknownUpdateTypeAndAToolUpdateWithoutACallIdAreKeptWholeNotDropped() throws Exception {
        String stream = String.join("\n",
                "{\"type\":\"brand_new_update\",\"n\":1}",
                "{\"type\":\"tool_call_update\",\"status\":\"completed\"}",
                "{\"n\":2}",
                "{\"type\":\"end\",\"stopReason\":\"end_turn\"}") + "\n";

        GrokPhaseCapture capture = GrokSessionParser.parse(new BufferedReader(new StringReader(stream)), "p", "q");

        assertThat(capture.vendorEvents()).extracting(GrokVendorEvent::kind)
                .containsExactly("brand_new_update", "tool_call_update", "unknown");
        assertThat(capture.vendorEvents()).extracting(GrokVendorEvent::line).containsExactly(1L, 2L, 3L);
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(GrokSessionParserTest.class.getResource("/fixtures/" + name).toURI());
    }
}
