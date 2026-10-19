package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normalized record holds the content of every tool result, not only the text of a failed call.
 * The oracle reads the real captured stream with plain Jackson and does not use {@link SessionLogParser}.
 */
class ToolResultContentInEventsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a" })
    void everyMainThreadToolCallEventCarriesItsResultContent(String fixture) throws Exception {
        Path stream = Path.of(ToolResultContentInEventsTest.class
                .getResource("/fixtures/real-streams/" + fixture + "/stream.jsonl").toURI());
        List<String> lines = Files.readAllLines(stream);

        // oracle: tool_result blocks of the main thread, by tool_use_id
        Map<String, JsonNode> oracle = new LinkedHashMap<>();
        for (String line : lines) {
            JsonNode message = MAPPER.readTree(line);
            if (!"user".equals(message.path("type").asText()) || !message.path("parent_tool_use_id").isMissingNode()
                    && !message.path("parent_tool_use_id").isNull()) {
                continue;
            }
            for (JsonNode block : message.path("message").path("content")) {
                if ("tool_result".equals(block.path("type").asText())) {
                    oracle.put(block.path("tool_use_id").asText(), block.path("content"));
                }
            }
        }

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

        List<ToolCallEvent> calls = storage.loadEvents("exp", run.id()).stream()
                .filter(ToolCallEvent.class::isInstance).map(ToolCallEvent.class::cast).toList();
        assertThat(calls).isNotEmpty();
        int compared = 0;
        for (ToolCallEvent call : calls) {
            JsonNode expected = oracle.get(call.id());
            if (expected == null) {
                continue;
            }
            compared++;
            assertThat(call.output()).as("output of %s", call.id()).isNotNull();
            String actual = String.valueOf(call.output());
            if (expected.isTextual()) {
                assertThat(actual).isEqualTo(expected.asText());
            }
            else {
                for (JsonNode part : expected) {
                    if (part.hasNonNull("text")) {
                        assertThat(actual).contains(part.get("text").asText());
                    }
                }
            }
        }
        assertThat(compared).as("tool calls compared with the stream").isPositive();
    }
}
