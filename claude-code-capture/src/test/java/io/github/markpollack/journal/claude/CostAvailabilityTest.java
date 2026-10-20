package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 13 review R13-02: a cost the vendor did not report is unknown, not zero and not "reported". The four
 * cases: no result line, a result line without {@code total_cost_usd}, an explicitly reported zero, and a positive
 * reported figure. Each is checked on the parsed capture and on the persisted, reloaded LLM call.
 */
@DisplayName("Cost availability follows what the result line reported")
class CostAvailabilityTest {

    private static final String ASSISTANT = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
            + "\"role\":\"assistant\",\"model\":\"claude-synthetic-1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":5,\"output_tokens\":3}},"
            + "\"parent_tool_use_id\":null,\"session_id\":\"s1\"}";

    private static String result(String costField) {
        return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"duration_ms\":10,\"duration_api_ms\":5,"
                + "\"num_turns\":1,\"result\":\"hi\",\"session_id\":\"s1\"" + costField
                + ",\"usage\":{\"input_tokens\":5,\"output_tokens\":3}}";
    }

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    private static PhaseCapture parse(String... lines) {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : lines) {
            messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
        }
        return SessionLogParser.parse(messages.iterator(), "execute", "p");
    }

    private static LLMCallEvent recorded(Path dir, PhaseCapture capture) {
        JsonFileStorage storage = new JsonFileStorage(dir);
        Journal.configure(storage);
        Run run = Journal.run("exp").start();
        RunRecorder recorder = new RunRecorder(run).archiveSessionFiles(false);
        recorder.recordPhase(capture);
        recorder.finish();
        return new JsonFileStorage(dir).loadEvents("exp", run.id()).stream()
                .filter(LLMCallEvent.class::isInstance).map(LLMCallEvent.class::cast).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("no result line: the cost is not reported")
    void noResultLine(@TempDir Path dir) {
        PhaseCapture capture = parse(ASSISTANT);
        assertThat(capture.costReported()).isFalse();
        assertThat(capture.costSource()).isEqualTo(PhaseCapture.COST_SOURCE_NOT_REPORTED_NO_RESULT);

        LLMCallEvent llm = recorded(dir, capture);
        assertThat(llm.metadata()).containsEntry("costAvailable", false)
                .containsEntry("costSource", PhaseCapture.COST_SOURCE_NOT_REPORTED_NO_RESULT);
        assertThat(llm.totalCostUsd()).isZero(); // a placeholder; costAvailable=false says so
    }

    @Test
    @DisplayName("a result line without total_cost_usd: the cost is not reported")
    void resultWithoutCostField(@TempDir Path dir) {
        PhaseCapture capture = parse(ASSISTANT, result(""));
        assertThat(capture.costReported()).isFalse();
        assertThat(capture.costSource()).isEqualTo(PhaseCapture.COST_SOURCE_NOT_REPORTED_NO_FIELD);

        LLMCallEvent llm = recorded(dir, capture);
        assertThat(llm.metadata()).containsEntry("costAvailable", false)
                .containsEntry("costSource", PhaseCapture.COST_SOURCE_NOT_REPORTED_NO_FIELD);
    }

    @Test
    @DisplayName("an explicitly reported zero is a reported cost of zero")
    void reportedZero(@TempDir Path dir) {
        PhaseCapture capture = parse(ASSISTANT, result(",\"total_cost_usd\":0.0"));
        assertThat(capture.costReported()).isTrue();
        assertThat(capture.totalCostUsd()).isZero();

        LLMCallEvent llm = recorded(dir, capture);
        assertThat(llm.metadata()).containsEntry("costAvailable", true)
                .containsEntry("costSource", PhaseCapture.COST_SOURCE_RESULT_LINE);
        assertThat(llm.totalCostUsd()).isZero();
    }

    @Test
    @DisplayName("a positive reported figure is reported with its source")
    void reportedPositive(@TempDir Path dir) {
        PhaseCapture capture = parse(ASSISTANT, result(",\"total_cost_usd\":0.0254435"));
        assertThat(capture.costReported()).isTrue();
        assertThat(capture.totalCostUsd()).isEqualTo(0.0254435);

        LLMCallEvent llm = recorded(dir, capture);
        assertThat(llm.metadata()).containsEntry("costAvailable", true)
                .containsEntry("costSource", PhaseCapture.COST_SOURCE_RESULT_LINE);
        assertThat(llm.totalCostUsd()).isEqualTo(0.0254435);
    }

    @Test
    @DisplayName("a capture built directly names its builder as the source, and stays available")
    void builderCapture(@TempDir Path dir) {
        PhaseCapture capture = new PhaseCapture("explore", "p", 1, 1, 0, 10L, 5L, 0.10, "s1", 1, false, "done",
                List.of(), List.of(), "done");
        assertThat(capture.costReported()).isTrue();
        assertThat(capture.costSource()).isEqualTo(PhaseCapture.COST_SOURCE_CAPTURE_BUILDER);
        assertThat(recorded(dir, capture).metadata()).containsEntry("costAvailable", true)
                .containsEntry("costSource", PhaseCapture.COST_SOURCE_CAPTURE_BUILDER);
    }
}
