package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 13, package 1: the ordered content of every main-thread assistant message (text, thinking with its signature,
 * tool use), the permission decision of every tool result, the vendor-timestamp duration of every tool call, and
 * every main-thread assistant and user line whole, are in the run's events (a sub-agent's lines, blocks and
 * decisions land on its child run, which {@code SubagentRunRecordingTest} covers). The oracle reads the real captured stream with plain
 * Jackson and never uses {@link SessionLogParser}.
 */
class OrderedContentOnRealStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private record Fixture(List<String> lines, List<JournalEvent> events) {
    }

    private static Fixture record(String fixture) throws Exception {
        Path stream = Path.of(OrderedContentOnRealStreamTest.class
                .getResource("/fixtures/real-streams/" + fixture + "/stream.jsonl").toURI());
        List<String> lines = Files.readAllLines(stream);
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : lines) {
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // skipped by the SDK, as in the stream reader
            }
        }
        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "run", "prompt");
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        new RunRecorder(run).recordPhase(capture);
        return new Fixture(lines, storage.loadEvents("exp", run.id()));
    }

    private static List<CustomEvent> custom(List<JournalEvent> events, String name) {
        return events.stream().filter(e -> e instanceof CustomEvent c && c.name().equals(name))
                .map(CustomEvent.class::cast).toList();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b" })
    void everyAssistantBlockIsInTheEventsInStreamOrderWithItsSignature(String fixture) throws Exception {
        Fixture f = record(fixture);

        // oracle: (kind, text or signature) per block of every main-thread assistant line, in stream order
        List<List<String>> expected = new ArrayList<>();
        for (String line : f.lines()) {
            JsonNode m = MAPPER.readTree(line);
            if (!"assistant".equals(m.path("type").asText()) || m.hasNonNull("parent_tool_use_id")) {
                continue;
            }
            for (JsonNode block : m.path("message").path("content")) {
                String kind = block.path("type").asText();
                String payload = switch (kind) {
                    case "text" -> block.path("text").asText();
                    case "thinking" -> block.path("signature").asText(null);
                    case "tool_use" -> block.path("id").asText();
                    default -> "";
                };
                expected.add(List.of(kind, payload == null ? "" : payload));
            }
        }
        assertThat(expected).isNotEmpty();

        // events: assistant_text and thinking_block carry (messageNumber, blockIndex); message_blocks gives the order
        List<CustomEvent> order = custom(f.events(), "message_blocks").stream()
                .filter(e -> !e.attributes().containsKey("parentToolUseId")).toList();
        Map<String, CustomEvent> byPosition = new LinkedHashMap<>();
        for (CustomEvent e : custom(f.events(), "assistant_text")) {
            byPosition.put(e.attributes().get("messageNumber") + ":" + e.attributes().get("blockIndex"), e);
        }
        for (CustomEvent e : custom(f.events(), "thinking_block")) {
            byPosition.put(e.attributes().get("messageNumber") + ":" + e.attributes().get("blockIndex"), e);
        }
        List<List<String>> actual = new ArrayList<>();
        for (CustomEvent line : order) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) line.attributes().get("blocks");
            for (Map<String, Object> block : blocks) {
                String kind = (String) block.get("kind");
                CustomEvent content = byPosition.get(line.attributes().get("messageNumber") + ":" + block.get("index"));
                String payload = switch (kind) {
                    case "text" -> (String) content.attributes().get("text");
                    case "thinking" -> (String) content.attributes().getOrDefault("signature", "");
                    case "tool_use" -> (String) block.get("toolUseId");
                    default -> "";
                };
                actual.add(List.of(kind, payload == null ? "" : payload));
            }
        }
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "single-result-parallel-calls", "foreground-subagent-one-result" })
    void everyPermissionDecisionOnTheStreamIsAnEvent(String fixture) throws Exception {
        Fixture f = record(fixture);

        List<List<String>> expected = new ArrayList<>();
        for (String line : f.lines()) {
            JsonNode m = MAPPER.readTree(line);
            if (!"user".equals(m.path("type").asText()) || m.hasNonNull("parent_tool_use_id")) {
                continue; // a sub-agent's decisions land on its child run
            }
            for (JsonNode meta : m.path("tool_result_meta")) {
                JsonNode d = meta.path("permission_decision");
                if (d.isObject()) {
                    expected.add(List.of(meta.path("id").asText(), d.path("decision").asText(),
                            d.path("source").asText(), d.path("reason_type").asText("")));
                }
            }
        }
        assertThat(expected).as("the fixture carries permission decisions").isNotEmpty();

        List<List<String>> actual = custom(f.events(), "permission_decision").stream()
                .map(e -> List.of((String) e.attributes().get("toolUseId"), (String) e.attributes().get("decision"),
                        (String) e.attributes().get("source"), (String) e.attributes().getOrDefault("reasonType", "")))
                .toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "single-result-parallel-calls", "foreground-subagent-one-result" })
    void toolDurationsAreTheVendorsOwnTimestampsOrNotReported(String fixture) throws Exception {
        Fixture f = record(fixture);

        Map<String, Instant> issued = new LinkedHashMap<>();
        Map<String, Long> expected = new LinkedHashMap<>();
        for (String line : f.lines()) {
            JsonNode m = MAPPER.readTree(line);
            if (m.hasNonNull("parent_tool_use_id")) {
                continue;
            }
            String ts = m.path("timestamp").asText(null);
            if ("assistant".equals(m.path("type").asText())) {
                for (JsonNode block : m.path("message").path("content")) {
                    if ("tool_use".equals(block.path("type").asText()) && ts != null) {
                        issued.put(block.path("id").asText(), Instant.parse(ts));
                    }
                }
            }
            if ("user".equals(m.path("type").asText()) && m.path("message").path("content").isArray()) {
                for (JsonNode block : m.path("message").path("content")) {
                    if ("tool_result".equals(block.path("type").asText())) {
                        String id = block.path("tool_use_id").asText();
                        Instant start = issued.get(id);
                        expected.put(id, start != null && ts != null
                                ? Duration.between(start, Instant.parse(ts)).toMillis() : -1L);
                    }
                }
            }
        }
        assertThat(expected).isNotEmpty();

        Map<String, Long> actual = new LinkedHashMap<>();
        for (JournalEvent e : f.events()) {
            if (e instanceof ToolCallEvent t) {
                actual.put(t.id(), t.durationMs());
            }
        }
        assertThat(actual).containsAllEntriesOf(expected);
        assertThat(custom(f.events(), "tool_timing")).allSatisfy(e -> assertThat(e.attributes())
                .containsEntry("durationSource", ToolResultRecord.DURATION_FROM_VENDOR_TIMESTAMPS));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "single-result-parallel-calls", "background-subagent-two-results-a" })
    void everyAssistantAndUserLineIsKeptWholeAtItsOccurrence(String fixture) throws Exception {
        Fixture f = record(fixture);

        List<String> expected = new ArrayList<>();
        int number = 0;
        for (String line : f.lines()) {
            JsonNode m = MAPPER.readTree(line);
            String type = m.path("type").asText();
            if ("rate_limit_event".equals(type)) {
                continue; // the SDK drops it before Journal sees it
            }
            number++;
            if (("assistant".equals(type) || "user".equals(type)) && !m.hasNonNull("parent_tool_use_id")) {
                expected.add(number + " " + line); // a sub-agent's lines land on its child run, with their owner
            }
        }

        List<String> actual = custom(f.events(), "source_record").stream()
                .map(e -> e.attributes().get("messageNumber") + " " + e.attributes().get("rawJson")).toList();
        assertThat(actual).containsExactlyElementsOf(expected);
    }
}
