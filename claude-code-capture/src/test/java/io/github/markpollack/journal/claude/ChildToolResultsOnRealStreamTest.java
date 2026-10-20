package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
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
 * Stage 13 review R13-03 on real streams (CLI 2.1.299 fixtures): after persistence and reload, every tool call a
 * sub-agent made is on that sub-agent's run with its vendor id, its parsed arguments, its result and its error
 * flag, read against the stream's own lines. A result whose content is a string must be equal; a structured
 * result (a content array) is only required to be present, because its normalized representation is Java's
 * collection rendering until the package-3 contract defines one (R13-04).
 */
class ChildToolResultsOnRealStreamTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    /**
     * Numbers compare by value, not by JSON token: claude-code-sdk 1.7.0 hands Journal an integral tool-input
     * value as a {@code double} ({@code "limit":1} arrives as {@code 1.0}), so the typed layer loses the integer
     * type before the parser runs; the {@code source_record} keeps the original text. Recorded as a known
     * normalization defect (Stage 13 review follow-up, R13-09), to be closed by the package-3 contract's numeric
     * rules and an SDK fix, not hidden by this test.
     */
    private static JsonNode numbersByValue(JsonNode node) {
        if (node.isNumber()) {
            double d = node.asDouble();
            return d == Math.rint(d) && !node.isBigDecimal() ? MAPPER.getNodeFactory().numberNode((long) d) : node;
        }
        if (node.isObject()) {
            var copy = MAPPER.createObjectNode();
            node.fields().forEachRemaining(f -> copy.set(f.getKey(), numbersByValue(f.getValue())));
            return copy;
        }
        if (node.isArray()) {
            var copy = MAPPER.createArrayNode();
            node.forEach(item -> copy.add(numbersByValue(item)));
            return copy;
        }
        return node;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "foreground-subagent-one-result", "background-subagent-two-results-a" })
    void everyChildToolExchangeIsOnTheChildRunAfterReload(String fixture, @TempDir Path dir) throws Exception {
        Path stream = Path.of(ChildToolResultsOnRealStreamTest.class
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
        Journal.configure(new JsonFileStorage(dir));
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).archiveSessionFiles(false);
        recorder.recordPhase(SessionLogParser.parse(messages.iterator(), "run", "prompt"));
        recorder.finish();

        // oracle from the stream: per owner (parent_tool_use_id), each tool_use's input and each tool_result
        Map<String, Map<String, JsonNode>> usesByOwner = new LinkedHashMap<>();
        Map<String, Map<String, JsonNode>> resultsByOwner = new LinkedHashMap<>();
        for (String line : lines) {
            JsonNode m = MAPPER.readTree(line);
            if (!m.hasNonNull("parent_tool_use_id")) {
                continue;
            }
            String owner = m.path("parent_tool_use_id").asText();
            JsonNode content = m.path("message").path("content");
            if (!content.isArray()) {
                continue;
            }
            for (JsonNode block : content) {
                if ("tool_use".equals(block.path("type").asText())) {
                    usesByOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(block.path("id").asText(), block);
                }
                if ("tool_result".equals(block.path("type").asText())) {
                    resultsByOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                            .put(block.path("tool_use_id").asText(), block);
                }
            }
        }
        assertThat(usesByOwner).as("the fixture has sub-agent tool calls").isNotEmpty();

        JsonFileStorage fresh = new JsonFileStorage(dir);
        for (Map.Entry<String, Map<String, JsonNode>> owner : usesByOwner.entrySet()) {
            RunData child = fresh.listRuns("exp").stream()
                    .filter(r -> owner.getKey().equals(r.config().values().get(BaseRunRecorder.CONFIG_SPAWN_TOOL_USE_ID)))
                    .findFirst().orElseThrow(() -> new AssertionError("no child run for spawn " + owner.getKey()));
            assertThat(child.parentRunId()).isEqualTo(run.id());
            List<ToolCallEvent> calls = fresh.loadEvents("exp", child.id()).stream()
                    .filter(ToolCallEvent.class::isInstance).map(ToolCallEvent.class::cast).toList();
            assertThat(calls).extracting(ToolCallEvent::id).containsExactlyElementsOf(owner.getValue().keySet());
            for (ToolCallEvent call : calls) {
                JsonNode use = owner.getValue().get(call.id());
                assertThat(call.toolName()).isEqualTo(use.path("name").asText());
                JsonNode input = numbersByValue(MAPPER.valueToTree(call.input()));
                assertThat(input).isEqualTo(numbersByValue(use.path("input")));
                JsonNode result = resultsByOwner.getOrDefault(owner.getKey(), Map.of()).get(call.id());
                if (result == null) {
                    assertThat(call.output()).isNull();
                    continue;
                }
                boolean isError = result.path("is_error").asBoolean(false);
                assertThat(call.success()).isEqualTo(!isError);
                JsonNode content = result.path("content");
                if (content.isTextual()) {
                    assertThat(call.output()).as("child %s result of %s", owner.getKey(), call.id())
                            .isEqualTo(content.asText());
                    assertThat(call.errorMessage()).isEqualTo(isError ? content.asText() : null);
                }
                else {
                    // structured result: present, but its shape is the package-3 contract's to define (R13-04)
                    assertThat(call.output()).isNotNull();
                }
            }
        }
    }
}
