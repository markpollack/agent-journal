package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.trace.JournalStep;
import io.github.markpollack.journal.trace.TraceContentMode;
import io.github.markpollack.journal.trace.TraceRawMode;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolResultBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.claude.agent.sdk.types.UserMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <strong>The capture-gap regression fence (1.9.0).</strong>
 *
 * <p>
 * Every assertion here exists because the field it guards was <em>already available on the wire</em>
 * and was dropped at parse time — per-turn thinking tokens, the cache vector at step granularity,
 * the stop reason, the turn ordinal, and the tool duration that was computed, logged, and then
 * thrown away. Three separate silent field losses have happened in this repository's history
 * (per-step tokens never captured, {@code phase_duration_ms} present in v1 and gone by v3,
 * {@code stop_reason} computed by the harness and persisted nowhere).
 *
 * <p>
 * The reason this test is worth its weight: <strong>analysis is free to redo, capture is one-shot
 * per run.</strong> A field that silently stops being written is not noticed until someone asks a
 * question of a dataset that can no longer answer it, and by then the runs are spent. So this test
 * asserts against the <em>production record path</em> end to end — the parser, the durable
 * {@code events.jsonl} / {@code analysis.jsonl} written by {@link RunRecorder}, and the JSONL trace
 * — rather than against the in-memory records alone. If any of these stops being persisted, this
 * fails.
 */
@DisplayName("capture contract: the 1.9.0 fields stay on the production record path")
class CaptureContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    // Two assistant turns on the wire. Turn 1 thinks, then calls two tools in parallel; turn 2
    // thinks less and ends the conversation. Shapes taken from a real session log: thinking tokens
    // live one level deeper than the rest of the vector, under output_tokens_details.
    private static final String TURN_1 = """
            {"type":"assistant","message":{"id":"msg_1","model":"claude-opus-4-8",
             "stop_reason":"tool_use",
             "usage":{"input_tokens":120,"output_tokens":300,
                      "cache_creation_input_tokens":18646,"cache_read_input_tokens":0,
                      "output_tokens_details":{"thinking_tokens":175}}}}""";

    private static final String TURN_2 = """
            {"type":"assistant","message":{"id":"msg_2","model":"claude-opus-4-8",
             "stop_reason":"end_turn",
             "usage":{"input_tokens":90,"output_tokens":100,
                      "cache_creation_input_tokens":2734,"cache_read_input_tokens":15857,
                      "output_tokens_details":{"thinking_tokens":42}}}}""";

    private static PhaseCapture parseTwoTurnRun(Path traceFile, int maxTurns) {
        ToolUseBlock read = ToolUseBlock.builder().id("toolu_1").name("Read")
                .input(Map.of("file_path", "/a.java")).build();
        ToolUseBlock bash = ToolUseBlock.builder().id("toolu_2").name("Bash")
                .input(Map.of("command", "ls")).build();

        List<ParsedMessage> messages = new ArrayList<>();
        messages.add(ParsedMessage.RegularMessage.of(
                new AssistantMessage(List.of(new TextBlock("looking"), read, bash)), TURN_1));
        messages.add(ParsedMessage.RegularMessage.of(
                new UserMessage(List.of(new ToolResultBlock("toolu_1", "file body", false)))));
        messages.add(ParsedMessage.RegularMessage.of(
                new UserMessage(List.of(new ToolResultBlock("toolu_2", "boom", true)))));
        messages.add(ParsedMessage.RegularMessage.of(
                new AssistantMessage(List.of(new TextBlock("done"))), TURN_2));
        messages.add(ParsedMessage.RegularMessage.of(ResultMessage.builder()
                .subtype("success")
                .durationMs(4000)
                .durationApiMs(3000)
                .numTurns(2)
                .sessionId("sess-1")
                .totalCostUsd(0.40)
                .usage(Map.of("input_tokens", 90, "output_tokens", 100))
                .result("done")
                .build()));

        return SessionLogParser.parse(messages.iterator(), "execute", "prompt", traceFile,
                TraceContentMode.TRUNCATED, TraceRawMode.NONE, maxTurns);
    }

    @Test
    @DisplayName("J2: the full five-field per-turn token vector survives to the step record")
    void perTurnTokenVectorReachesEveryStep(@TempDir Path tempDir) {
        PhaseCapture capture = parseTwoTurnRun(tempDir.resolve("trace.jsonl"), 40);

        // Thinking is read from the wire, exactly — not the chars/4 estimate that used to stand in
        // for it. 175 + 42, and neither is added to the billed output total.
        assertThat(capture.turns()).extracting(TurnUsage::thinkingTokens).containsExactly(175L, 42L);
        assertThat(capture.thinkingTokensFromTurns()).isEqualTo(217L);
        assertThat(capture.thinkingTokens()).isEqualTo(217);

        List<JournalStep> steps = capture.stepCosts();
        assertThat(steps).hasSize(3);

        // Every step carries its turn's whole vector, cache included. Before 1.9.0 the step record
        // carried input and output only, so a per-state cost could not be priced.
        JournalStep first = steps.get(0);
        assertThat(first.inputTokens()).isEqualTo(120L);
        assertThat(first.outputTokens()).isEqualTo(300L);
        assertThat(first.thinkingTokens()).isEqualTo(175L);
        assertThat(first.cacheCreationTokens()).isEqualTo(18646L);
        assertThat(first.cacheReadTokens()).isEqualTo(0L);
        assertThat(first.totalInputTokens()).isEqualTo(120L + 18646L);

        JournalStep last = steps.get(2);
        assertThat(last.cacheReadTokens()).isEqualTo(15857L);
        assertThat(last.thinkingTokens()).isEqualTo(42L);

        // The allocation still sums to the ground-truth total — carrying more fields must not
        // disturb the one identity that has to hold.
        assertThat(steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum())
                .isEqualTo(capture.totalCostUsd(), org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("J3: stop reason and maxTurns are persisted together, on the durable event path")
    void stopReasonAndMaxTurnsPersistTogether(@TempDir Path tempDir) {
        PhaseCapture capture = parseTwoTurnRun(tempDir.resolve("trace.jsonl"), 40);

        assertThat(capture.stopReason()).isEqualTo(StopReason.NATURAL_DONE);
        assertThat(capture.maxTurns()).isEqualTo(40);
        assertThat(capture.wasTruncated()).isFalse();

        Journal.configure(new JsonFileStorage(tempDir.resolve("journal")));
        Run run = Journal.run("exp").config("model", "claude-opus-4-8").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(capture);
        }

        LLMCallEvent llm = onlyLlmEvent(tempDir.resolve("journal"), run);
        // Both keys, always — a numTurns with no ceiling beside it is uninterpretable.
        assertThat(llm.metadata()).containsEntry(JournalSteps.META_STOP_REASON, "NATURAL_DONE");
        assertThat(llm.metadata()).containsEntry(JournalSteps.META_MAX_TURNS, 40);
    }

    @Test
    @DisplayName("J3: a run cut off at its ceiling is recorded as truncated, not as finished")
    void maxTurnsCutoffIsDistinguishableFromCompletion() {
        List<ParsedMessage> messages = List.of(
                ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(new TextBlock("x"))), TURN_2),
                ParsedMessage.RegularMessage.of(ResultMessage.builder()
                        .subtype("error_max_turns")
                        .durationMs(1000).durationApiMs(900).numTurns(40)
                        .sessionId("sess-2").totalCostUsd(0.10)
                        .usage(Map.of("input_tokens", 1, "output_tokens", 1))
                        .build()));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "p", null,
                TraceContentMode.TRUNCATED, TraceRawMode.NONE, 40);

        // The final turn said "end_turn" — taking the turn's word for it would have recorded this
        // cut-off run as a natural finish. The harness-level subtype wins for exactly this reason.
        assertThat(capture.stopReason()).isEqualTo(StopReason.MAX_TURNS);
        assertThat(capture.wasTruncated()).isTrue();
        assertThat(capture.maxTurns()).isEqualTo(40);
    }

    @Test
    @DisplayName("J3/J4: an unreported value is recorded as unknown, never as a plausible default")
    void unknownIsRecordedAsUnknown() {
        List<ParsedMessage> messages = List.of(
                ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(new TextBlock("hi")))),
                ParsedMessage.RegularMessage.of(ResultMessage.builder()
                        .durationMs(10).durationApiMs(5).numTurns(1).sessionId("s").totalCostUsd(0.01)
                        .usage(Map.of("input_tokens", 1, "output_tokens", 1)).build()));

        // No raw wire, no caller-supplied ceiling: nothing is known, and it must say so.
        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "p");

        assertThat(capture.maxTurns()).isEqualTo(SessionLogParser.UNKNOWN_MAX_TURNS);
        assertThat(capture.stopReason()).isEqualTo(StopReason.UNKNOWN);
        // Never 0, which would read as "turn zero" / "instant tool".
        assertThat(new ToolUseRecord("toolu_x", "Read", Map.of()).turnIndex()).isEqualTo(-1);
        assertThat(new ToolResultRecord("toolu_x", "c", false).durationMs()).isEqualTo(-1L);
    }

    @Test
    @DisplayName("J4: per-tool duration and turn ordinal reach the durable execution stream")
    void toolDurationAndTurnIndexReachEventsJsonl(@TempDir Path tempDir) {
        PhaseCapture capture = parseTwoTurnRun(tempDir.resolve("trace.jsonl"), 40);

        // Both tool calls were issued by turn 0 and both got results, so both have a measured
        // duration. This is the pairing v1 had and v3/v4 lost.
        assertThat(capture.toolUses()).extracting(ToolUseRecord::turnIndex).containsExactly(0, 0);
        assertThat(capture.toolUses()).extracting(ToolUseRecord::turnId).containsExactly("msg_1", "msg_1");
        assertThat(capture.toolResults()).allSatisfy(r -> assertThat(r.hasDuration()).isTrue());

        Journal.configure(new JsonFileStorage(tempDir.resolve("journal")));
        Run run = Journal.run("exp").config("model", "claude-opus-4-8").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(capture);
        }

        List<ToolCallEvent> tools = new ArrayList<>();
        for (JournalEvent e : loadEvents(tempDir.resolve("journal"), run)) {
            if (e instanceof ToolCallEvent tc) {
                tools.add(tc);
            }
        }
        assertThat(tools).hasSize(2);
        assertThat(tools).extracting(ToolCallEvent::turnIndex).containsExactly(0, 0);
        assertThat(tools).extracting(ToolCallEvent::turnId).containsExactly("msg_1", "msg_1");
        assertThat(tools).allSatisfy(tc -> assertThat(tc.durationMs()).isGreaterThanOrEqualTo(0L));
        // The error bit still round-trips alongside the new fields.
        assertThat(tools).extracting(ToolCallEvent::success).containsExactly(true, false);
    }

    @Test
    @DisplayName("the trace's step_cost, tool_use, tool_result and result lines all carry the new fields")
    void traceLinesCarryTheNewFields(@TempDir Path tempDir) throws IOException {
        Path traceFile = tempDir.resolve("trace.jsonl");
        parseTwoTurnRun(traceFile, 40);

        List<JsonNode> lines = new ArrayList<>();
        for (String line : Files.readAllLines(traceFile)) {
            lines.add(MAPPER.readTree(line));
        }

        JsonNode result = lastOfType(lines, "result");
        assertThat(result.path("stopReason").asText()).isEqualTo("NATURAL_DONE");
        assertThat(result.path("maxTurns").asInt()).isEqualTo(40);

        JsonNode toolUse = lines.stream().filter(n -> "tool_use".equals(n.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(toolUse.path("turnIndex").asInt()).isEqualTo(0);
        assertThat(toolUse.path("turnId").asText()).isEqualTo("msg_1");

        JsonNode toolResult = lines.stream().filter(n -> "tool_result".equals(n.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(toolResult.has("durationMs")).isTrue();
        assertThat(toolResult.path("durationMs").asLong()).isGreaterThanOrEqualTo(0L);

        JsonNode stepCost = lines.stream().filter(n -> "step_cost".equals(n.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(stepCost.path("thinkingTokens").asLong()).isEqualTo(175L);
        assertThat(stepCost.path("cacheCreationTokens").asLong()).isEqualTo(18646L);
        assertThat(stepCost.path("cacheReadTokens").asLong()).isEqualTo(0L);
        assertThat(stepCost.path("turnIndex").asInt()).isEqualTo(0);
        assertThat(stepCost.has("durationMs")).isTrue();

        // The five Markov-contract keys on the result line are untouched — every 1.9.0 change is
        // an addition, so an existing loader keeps working byte-for-byte.
        assertThat(result.has("inputTokens")).isTrue();
        assertThat(result.has("outputTokens")).isTrue();
        assertThat(result.has("costUsd")).isTrue();
        assertThat(result.has("numTurns")).isTrue();
        assertThat(result.has("durationMs")).isTrue();
    }

    @Test
    @DisplayName("offline re-derivation from events.jsonl matches the capture, new fields included")
    void offlineRederivationStillMatches(@TempDir Path tempDir) {
        PhaseCapture capture = parseTwoTurnRun(tempDir.resolve("trace.jsonl"), 40);

        Journal.configure(new JsonFileStorage(tempDir.resolve("journal")));
        Run run = Journal.run("exp").config("model", "claude-opus-4-8").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(capture);
        }

        List<JournalStep> fromEvents = JournalSteps.fromEvents(loadEvents(tempDir.resolve("journal"), run),
                run.id());
        List<JournalStep> fromCapture = JournalSteps.fromPhaseCapture(capture, run.id());

        // The closure that makes a lost analysis.jsonl recoverable has to survive the new fields:
        // token vector, ordinal and duration all re-derive from the immutable log alone.
        assertThat(fromEvents).hasSameSizeAs(fromCapture);
        assertThat(fromEvents).extracting(JournalStep::thinkingTokens)
                .isEqualTo(fromCapture.stream().map(JournalStep::thinkingTokens).toList());
        assertThat(fromEvents).extracting(JournalStep::cacheCreationTokens)
                .isEqualTo(fromCapture.stream().map(JournalStep::cacheCreationTokens).toList());
        assertThat(fromEvents).extracting(JournalStep::cacheReadTokens)
                .isEqualTo(fromCapture.stream().map(JournalStep::cacheReadTokens).toList());
        assertThat(fromEvents).extracting(JournalStep::turnIndex)
                .isEqualTo(fromCapture.stream().map(JournalStep::turnIndex).toList());
    }

    @Test
    @DisplayName("the named token-sum caveat holds: per-turn tokens do not sum to the result snapshot")
    void tokenSumCaveatIsRealAndTheCostIdentityIsTheHonestCheck(@TempDir Path tempDir) {
        PhaseCapture capture = parseTwoTurnRun(tempDir.resolve("trace.jsonl"), 40);

        // PER_TURN_INPUT_NOT_ADDITIVE, demonstrated rather than asserted in prose: summing the
        // per-turn input over an accumulating context window (120 + 90 fresh, plus 37k of cache)
        // is a different quantity from the result's final-request snapshot (90). Neither is wrong.
        assertThat(capture.aggregateUsage().inputTokens()).isEqualTo(210);
        assertThat(capture.inputTokens()).isEqualTo(90);
        assertThat(capture.aggregateUsage().inputTokens()).isNotEqualTo(capture.inputTokens());

        // With no modelUsage on the wire there is nothing to reconcile against, and "unverified"
        // must not read as "verified".
        assertThat(capture.hasModelCosts()).isFalse();
        assertThat(capture.reconcilesToModelCosts()).isFalse();
    }

    // ===== helpers =====

    private static List<JournalEvent> loadEvents(Path baseDir, Run run) {
        return new JsonFileStorage(baseDir).loadEvents(run.experiment().id(), run.id());
    }

    private static LLMCallEvent onlyLlmEvent(Path baseDir, Run run) {
        return loadEvents(baseDir, run).stream()
                .filter(LLMCallEvent.class::isInstance)
                .map(LLMCallEvent.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static JsonNode lastOfType(List<JsonNode> lines, String type) {
        JsonNode found = null;
        for (JsonNode n : lines) {
            if (type.equals(n.path("type").asText())) {
                found = n;
            }
        }
        assertThat(found).as("a %s line", type).isNotNull();
        return found;
    }
}
