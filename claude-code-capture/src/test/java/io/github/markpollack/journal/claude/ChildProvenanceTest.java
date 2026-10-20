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
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Stage 13 FB-1 (review findings R13-10 and R13-11): two successive invocations under one phase name, each with
 * sub-agents, persisted and reloaded. Every record of a child run references the invocation that recorded it; each
 * child's tool durations carry the same {@code tool_timing} declaration as the main loop's; occurrences stay unique
 * per run. The first invocation is the synthetic sub-agent stream (no line timestamps, a successful child result, a
 * failed child result, a child tool use without a result); the second is a real 2.1.299 stream (timestamps on every
 * line). The oracle is read with plain Jackson, never through the parser.
 */
class ChildProvenanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private static List<String> lines(String resource) throws Exception {
        return Files.readAllLines(Path.of(ChildProvenanceTest.class.getResource(resource).toURI()));
    }

    private static PhaseCapture capture(List<String> lines) {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // skipped by the SDK, as in the stream reader
            }
        }
        return SessionLogParser.parse(messages.iterator(), "run", "prompt");
    }

    private static List<Map<String, Object>> customs(List<JournalEvent> events, String name) {
        return events.stream().filter(e -> e instanceof CustomEvent c && c.name().equals(name))
                .map(e -> ((CustomEvent) e).attributes()).toList();
    }

    private static Map<String, ToolCallEvent> toolCalls(List<JournalEvent> events) {
        Map<String, ToolCallEvent> out = new LinkedHashMap<>();
        for (JournalEvent e : events) {
            if (e instanceof ToolCallEvent t) {
                out.put(t.id(), t);
            }
        }
        return out;
    }

    @Test
    @DisplayName("child runs of two successive invocations reference their invocation and declare their durations")
    void childRecordsReferenceTheirInvocationAndDeclareTheirDurations(@TempDir Path dir) throws Exception {
        List<String> synthetic = lines("/fixtures/claude-subagents-synthetic.jsonl");
        List<String> real = lines("/fixtures/real-streams/foreground-subagent-one-result/stream.jsonl");
        JsonFileStorage storage = new JsonFileStorage(dir);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).archiveSessionFiles(false);
        recorder.recordPhase(capture(synthetic));
        recorder.recordPhase(capture(real));
        recorder.finish();

        JsonFileStorage fresh = new JsonFileStorage(dir);
        List<RunData> runs = fresh.listRuns("exp");
        Map<String, RunData> childBySpawn = new LinkedHashMap<>();
        for (RunData r : runs) {
            Object spawn = r.config().values().get(BaseRunRecorder.CONFIG_SPAWN_TOOL_USE_ID);
            if (spawn != null) {
                childBySpawn.put((String) spawn, r);
            }
        }
        // invocation 0: a, b, c (child of b), d; refused never started. invocation 1: the real sub-agent
        assertThat(childBySpawn.keySet()).containsExactlyInAnyOrder("toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_c",
                "toolu_spawn_d", "toolu_01BnEuCUmZ2Vu1LpADzksmrJ");
        Map<String, Integer> expectedInvocation = Map.of("toolu_spawn_a", 0, "toolu_spawn_b", 0, "toolu_spawn_c", 0,
                "toolu_spawn_d", 0, "toolu_01BnEuCUmZ2Vu1LpADzksmrJ", 1);

        // owner linkage: a, b, d and the real child hang off the root; c off b
        assertThat(childBySpawn.get("toolu_spawn_a").parentRunId()).isEqualTo(run.id());
        assertThat(childBySpawn.get("toolu_spawn_b").parentRunId()).isEqualTo(run.id());
        assertThat(childBySpawn.get("toolu_spawn_d").parentRunId()).isEqualTo(run.id());
        assertThat(childBySpawn.get("toolu_01BnEuCUmZ2Vu1LpADzksmrJ").parentRunId()).isEqualTo(run.id());
        assertThat(childBySpawn.get("toolu_spawn_c").parentRunId()).isEqualTo(childBySpawn.get("toolu_spawn_b").id());

        // the root's inventory has both invocations, and its own records reference them
        JsonNode capture = MAPPER.valueToTree(fresh.loadRun("exp", run.id()).orElseThrow().summary()).path("capture");
        assertThat(capture.path("invocations")).hasSize(2);
        List<JournalEvent> rootEvents = fresh.loadEvents("exp", run.id());
        assertThat(customs(rootEvents, "source_record")).extracting(r -> r.get("invocation")).containsOnly(0, 1).contains(0, 1);
        assertThat(customs(rootEvents, "vendor_event")).extracting(r -> r.get("invocation")).containsOnly(0, 1).contains(0, 1);

        for (Map.Entry<String, RunData> child : childBySpawn.entrySet()) {
            List<JournalEvent> events = fresh.loadEvents("exp", child.getValue().id());
            List<Map<String, Object>> records = customs(events, "source_record");
            assertThat(records).as("child %s has its lines", child.getKey()).isNotEmpty();
            // R13-10: every record references the invocation that recorded this child, never -1
            assertThat(records).extracting(r -> r.get("invocation")).containsOnly(expectedInvocation.get(child.getKey()));
            assertThat(records).extracting(r -> r.get("parentToolUseId")).containsOnly(child.getKey());
            // occurrence uniqueness on the child run
            assertThat(records.stream().map(r -> r.get("invocation") + ":" + r.get("messageNumber")).distinct().count())
                    .isEqualTo(records.size());
            // R13-11: every tool call with a result has a tool_timing declaration, and durations agree
            Map<String, ToolCallEvent> calls = toolCalls(events);
            Map<String, Map<String, Object>> timing = new LinkedHashMap<>();
            for (Map<String, Object> t : customs(events, "tool_timing")) {
                assertThat(timing.put((String) t.get("toolUseId"), t)).as("one tool_timing per tool use").isNull();
            }
            for (ToolCallEvent call : calls.values()) {
                Map<String, Object> t = timing.get(call.id());
                if (call.output() == null && call.errorMessage() == null) {
                    assertThat(t).as("no result, no timing: %s", call.id()).isNull();
                    assertThat(call.durationMs()).isEqualTo(-1L);
                    continue;
                }
                assertThat(t).as("tool_timing for %s on child %s", call.id(), child.getKey()).isNotNull();
                assertThat(((Number) t.get("durationMs")).longValue()).isEqualTo(call.durationMs());
            }
        }
        // the root's uniqueness across both invocations: messageNumber repeats, (invocation, messageNumber) does not
        List<Map<String, Object>> rootRecords = customs(rootEvents, "source_record");
        assertThat(rootRecords.stream().map(r -> r.get("invocation") + ":" + r.get("messageNumber")).distinct().count())
                .isEqualTo(rootRecords.size());
        assertThat(rootRecords.stream().map(r -> r.get("messageNumber")).distinct().count()).isLessThan(rootRecords.size());

        // invocation 0 (no timestamps): not-reported, -1, on the successful (a) and the failed (c) child results
        ToolCallEvent aBash = toolCalls(fresh.loadEvents("exp", childBySpawn.get("toolu_spawn_a").id())).get("toolu_a_bash");
        assertThat(aBash.success()).isTrue();
        assertThat(aBash.durationMs()).isEqualTo(-1L);
        Map<String, Object> aTiming = customs(fresh.loadEvents("exp", childBySpawn.get("toolu_spawn_a").id()), "tool_timing").get(0);
        assertThat(aTiming).containsEntry("toolUseId", "toolu_a_bash")
                .containsEntry("durationSource", ToolResultRecord.DURATION_NOT_REPORTED).hasEntrySatisfying("durationMs", d -> assertThat(((Number) d).longValue()).isEqualTo(-1L))
                .doesNotContainKeys("issuedAt", "resultAt");
        ToolCallEvent cBash = toolCalls(fresh.loadEvents("exp", childBySpawn.get("toolu_spawn_c").id())).get("toolu_c_bash");
        assertThat(cBash.success()).isFalse();
        assertThat(cBash.errorMessage()).isNotBlank();
        assertThat(customs(fresh.loadEvents("exp", childBySpawn.get("toolu_spawn_c").id()), "tool_timing"))
                .singleElement().satisfies(t -> assertThat(t).containsEntry("toolUseId", "toolu_c_bash")
                        .containsEntry("durationSource", ToolResultRecord.DURATION_NOT_REPORTED));
        // d: a tool use without a result, no timing
        assertThat(customs(fresh.loadEvents("exp", childBySpawn.get("toolu_spawn_d").id()), "tool_timing")).isEmpty();

        // invocation 1 (timestamps on every line): vendor-timestamps, equal to the stream's own difference
        Instant issued = null;
        Instant resulted = null;
        for (String line : real) {
            JsonNode m = MAPPER.readTree(line);
            if (!"toolu_01BnEuCUmZ2Vu1LpADzksmrJ".equals(m.path("parent_tool_use_id").asText(null))) {
                continue;
            }
            for (JsonNode block : m.path("message").path("content")) {
                if ("tool_use".equals(block.path("type").asText()) && "toolu_018fX6Mygf8aSE8c8bt7pyu6".equals(block.path("id").asText())) {
                    issued = Instant.parse(m.path("timestamp").asText());
                }
                if ("tool_result".equals(block.path("type").asText()) && "toolu_018fX6Mygf8aSE8c8bt7pyu6".equals(block.path("tool_use_id").asText())) {
                    resulted = Instant.parse(m.path("timestamp").asText());
                }
            }
        }
        assertThat(issued).isNotNull();
        assertThat(resulted).isNotNull();
        final String issuedAt = issued.toString();
        final String resultAt = resulted.toString();
        long expectedMs = Duration.between(issued, resulted).toMillis();
        List<JournalEvent> realChild = fresh.loadEvents("exp", childBySpawn.get("toolu_01BnEuCUmZ2Vu1LpADzksmrJ").id());
        assertThat(toolCalls(realChild).get("toolu_018fX6Mygf8aSE8c8bt7pyu6").durationMs()).isEqualTo(expectedMs);
        assertThat(customs(realChild, "tool_timing")).singleElement().satisfies(t -> assertThat(t)
                .containsEntry("toolUseId", "toolu_018fX6Mygf8aSE8c8bt7pyu6")
                .containsEntry("durationSource", ToolResultRecord.DURATION_FROM_VENDOR_TIMESTAMPS)
                .containsEntry("issuedAt", issuedAt).containsEntry("resultAt", resultAt)
                .hasEntrySatisfying("durationMs", d -> assertThat(((Number) d).longValue()).isEqualTo(expectedMs)));
    }
}
