package io.github.markpollack.journal.codex;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records that the parser does not turn into a field are kept whole at level 1, in file order.
 * The oracle reads the rollout file itself and does not use the parser's own bookkeeping.
 */
class CodexVendorEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Payload types that feed a field of the capture. */
    private static final Set<String> CONSUMED_PAYLOAD_TYPES = Set.of("custom_tool_call", "custom_tool_call_output",
            "function_call", "function_call_output", "token_count", "task_complete", "turn_aborted");

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void realRolloutKeepsExactlyTheRecordsThatFeedNoField() throws Exception {
        Path file = Path.of(CodexVendorEventTest.class.getResource("/fixtures/codex-rollout.jsonl").toURI());

        List<Map<String, Object>> expected = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            JsonNode record = MAPPER.readTree(line);
            String envelope = record.path("type").asText();
            String payloadType = record.path("payload").path("type").asText(null);
            boolean consumed = "session_meta".equals(envelope) || "turn_context".equals(envelope)
                    || (payloadType != null && CONSUMED_PAYLOAD_TYPES.contains(payloadType));
            if (!consumed) {
                expected.add(MAPPER.convertValue(record, new TypeReference<Map<String, Object>>() {
                }));
            }
        }

        CodexPhaseCapture capture = CodexSessionParser.parse(file, "p", "q");

        assertThat(expected).isNotEmpty();
        assertThat(capture.vendorEvents()).extracting(CodexVendorEvent::payload).containsExactlyElementsOf(expected);
        assertThat(capture.vendorEvents()).extracting(CodexVendorEvent::kind).contains("task_started");
    }

    @Test
    void reasoningMessagesRateLimitsFutureKindsAndUnpairableToolRecordsAreKeptWhole() throws Exception {
        String rollout = String.join("\n",
                "{\"timestamp\":\"2026-10-19T10:00:00.000Z\",\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"t1\",\"cli_version\":\"0.160.1\"}}",
                "{\"timestamp\":\"2026-10-19T10:00:01.000Z\",\"ordinal\":1,\"type\":\"response_item\",\"payload\":{\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"abc\"}}",
                "{\"timestamp\":\"2026-10-19T10:00:02.000Z\",\"ordinal\":2,\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}}",
                "{\"timestamp\":\"2026-10-19T10:00:03.000Z\",\"ordinal\":3,\"type\":\"event_msg\",\"payload\":{\"type\":\"rate_limits\",\"primary\":{\"used_percent\":12.5}}}",
                "{\"timestamp\":\"2026-10-19T10:00:04.000Z\",\"ordinal\":4,\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"x\",\"arguments\":\"{}\"}}",
                "{\"timestamp\":\"2026-10-19T10:00:05.000Z\",\"ordinal\":5,\"type\":\"event_msg\",\"payload\":{\"type\":\"item_completed\",\"item\":{\"type\":\"AgentMessage\",\"id\":\"m1\"}}}",
                "{\"timestamp\":\"2026-10-19T10:00:06.000Z\",\"ordinal\":6,\"type\":\"brand_new_envelope\",\"payload\":{\"type\":\"brand_new_kind\",\"n\":1}}",
                "{\"timestamp\":\"2026-10-19T10:00:07.000Z\",\"ordinal\":7,\"type\":\"compacted\"}",
                "{\"timestamp\":\"2026-10-19T10:00:08.000Z\",\"ordinal\":8,\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"duration_ms\":5,\"last_agent_message\":\"done\"}}")
                + "\n";

        CodexPhaseCapture capture = CodexSessionParser.parse(new BufferedReader(new StringReader(rollout)), "p", "q");

        assertThat(capture.vendorEvents()).extracting(CodexVendorEvent::kind).containsExactly("reasoning", "message",
                "rate_limits", "function_call", "item_completed", "brand_new_kind", "compacted");
        assertThat(capture.vendorEvents()).extracting(CodexVendorEvent::ordinal).containsExactly(1L, 2L, 3L, 4L, 5L, 6L,
                7L);
        assertThat(capture.vendorEvents().get(0).payload()).containsEntry("type", "response_item");
        assertThat(capture.vendorEvents().get(0).timestamp()).isEqualTo("2026-10-19T10:00:01.000Z");
        // Nothing the typed layer reads is repeated, and the unpairable function_call made no tool.
        assertThat(capture.toolUses()).isEmpty();
        assertThat(capture.textOutput()).isEqualTo("done");
    }

    @Test
    void aForkedChildKeepsItsOwnRecordsAndNotTheHistoryReplayedFromItsParent() throws Exception {
        CodexPhaseCapture capture = CodexSessionParser.parse(CodexSubagentCaptureTest.allRollouts(), "p", "q");

        CodexSubagentCapture forked = CodexSubagentCaptureTest.byThread(capture, CodexSubagentCaptureTest.CHILD_B);
        assertThat(forked.replayedRecordsSkipped()).isPositive();
        assertThat(forked.vendorEvents()).extracting(CodexVendorEvent::ordinal)
                .allSatisfy(ordinal -> assertThat(ordinal).isGreaterThanOrEqualTo(4L));
        // The root's own records are not in the children's lists and the reverse.
        assertThat(capture.vendorEvents()).extracting(CodexVendorEvent::payload)
                .doesNotContainAnyElementsOf(forked.vendorEvents().stream().map(CodexVendorEvent::payload).toList());
    }

    @Test
    void recorderLogsEachKeptRecordAsAVendorEventOnTheRightRun() throws Exception {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        String rollout = String.join("\n",
                "{\"timestamp\":\"2026-10-19T10:00:00.000Z\",\"ordinal\":0,\"type\":\"session_meta\",\"payload\":{\"id\":\"t1\"}}",
                "{\"timestamp\":\"2026-10-19T10:00:03.000Z\",\"ordinal\":3,\"type\":\"event_msg\",\"payload\":{\"type\":\"rate_limits\",\"primary\":{\"used_percent\":12.5}}}",
                "{\"timestamp\":\"not a time\",\"ordinal\":4,\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"duration_ms\":5}}")
                + "\n";
        CodexPhaseCapture capture = CodexSessionParser.parse(new BufferedReader(new StringReader(rollout)), "p", "q");
        Run run = Journal.run("exp").start();

        new CodexRunRecorder(run).recordPhase(capture);

        List<JournalEvent> events = storage.loadEvents("exp", run.id());
        assertThat(events).filteredOn(e -> e instanceof CustomEvent c && c.type().equals("vendor_event"))
                .singleElement().satisfies(e -> {
                    Map<String, Object> attributes = ((CustomEvent) e).attributes();
                    assertThat(attributes).containsEntry("vendor", "codex").containsEntry("kind", "rate_limits")
                            .containsEntry("envelopeType", "event_msg").containsEntry("ordinal", 3L)
                            .containsEntry("vendorTimestamp", "2026-10-19T10:00:03.000Z");
                    assertThat(attributes.get("payload")).isEqualTo(capture.vendorEvents().get(0).payload());
                    assertThat(((CustomEvent) e).timestamp().toString()).isEqualTo("2026-10-19T10:00:03Z");
                });
    }
}
