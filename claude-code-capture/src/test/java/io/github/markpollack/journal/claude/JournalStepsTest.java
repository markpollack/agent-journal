package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Cost-attribution contract for JournalSteps: per-step shares sum to the true run total
 * (ground truth preserved), are non-uniform (the ACT signal), and carry stable step ids.
 */
class JournalStepsTest {

    private static final String RUN = "run-1";

    @Test
    void attributesRunCostByOutputTokensAndSumsToTotal() {
        // Turn 1: one tool, 100 output tokens. Turn 2: one tool, 300 output tokens. Total $0.04.
        // Output-proportional → toolu_1 = 0.25*0.04 = 0.01, toolu_2 = 0.75*0.04 = 0.03.
        List<ParsedMessage> messages = List.of(
                assistantTurn("msg_1", "toolu_1", "Read", 10, 100),
                assistantTurn("msg_2", "toolu_2", "Bash", 12, 300),
                wrap(result(0.04)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);

        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(JournalStep::stepId).containsExactly("toolu_1", "toolu_2");
        assertThat(steps).allSatisfy(s -> {
            assertThat(s.actualRunCostUsd()).isEqualTo(0.04);
            assertThat(s.attributionMethod()).isEqualTo(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL);
            assertThat(s.vendor()).isEqualTo("claude-code");
        });
        // Ground truth preserved: shares sum to the real total.
        assertThat(steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum()).isCloseTo(0.04, within(1e-12));
        // Non-uniform: the high-output turn costs more.
        assertThat(steps.get(0).attributedCostUsd()).isCloseTo(0.01, within(1e-9));
        assertThat(steps.get(1).attributedCostUsd()).isCloseTo(0.03, within(1e-9));
        assertThat(steps.get(1).attributedCostUsd()).isGreaterThan(steps.get(0).attributedCostUsd());
    }

    @Test
    void splitsTurnCostEvenlyAcrossParallelTools() {
        // One turn issuing two tool calls → the turn's cost splits evenly between them.
        ToolUseBlock a = ToolUseBlock.builder().id("toolu_a").name("Read").input(Map.of("file_path", "/x")).build();
        ToolUseBlock b = ToolUseBlock.builder().id("toolu_b").name("Read").input(Map.of("file_path", "/y")).build();
        String wire = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":200,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}}";
        List<ParsedMessage> messages = List.of(
                wrapRaw(new AssistantMessage(List.of(a, b)), wire),
                wrap(result(0.02)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);

        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(JournalStep::stepId).containsExactly("toolu_a", "toolu_b");
        assertThat(steps).allSatisfy(s -> assertThat(s.attributedCostUsd()).isCloseTo(0.01, within(1e-9)));
        assertThat(steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum()).isCloseTo(0.02, within(1e-12));
    }

    @Test
    void toollessTurnBecomesTurnLevelStep() {
        String wire = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_final\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":120,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}}";
        List<ParsedMessage> messages = List.of(
                wrapRaw(new AssistantMessage(List.of(new TextBlock("final answer"))), wire),
                wrap(result(0.01)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);

        assertThat(steps).hasSize(1);
        JournalStep s = steps.get(0);
        assertThat(s.stepId()).isEqualTo("msg_final");
        assertThat(s.turnId()).isEqualTo("msg_final");
        assertThat(s.toolName()).isNull();
        assertThat(s.attributedCostUsd()).isCloseTo(0.01, within(1e-9));
    }

    @Test
    void fallsBackToEvenSplitWhenNoPerTurnUsage() {
        // No rawJson (wrap, not wrapRaw) → no turns → even-split across tool calls.
        ToolUseBlock a = ToolUseBlock.builder().id("toolu_a").name("Read").input(Map.of("file_path", "/x")).build();
        ToolUseBlock b = ToolUseBlock.builder().id("toolu_b").name("Bash").input(Map.of("command", "ls")).build();
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(a, b))),
                wrap(result(0.03)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        assertThat(phase.hasTurns()).isFalse();

        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);
        assertThat(steps).hasSize(2);
        assertThat(steps).extracting(JournalStep::stepId).containsExactly("toolu_a", "toolu_b");
        assertThat(steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum()).isCloseTo(0.03, within(1e-12));
    }

    @Test
    void stampsEvenSplitWhenNoPerTurnUsage() {
        // No rawJson → no turns → the whole allocation degrades to a flat even split → EVEN_SPLIT (A1).
        ToolUseBlock a = ToolUseBlock.builder().id("toolu_a").name("Read").input(Map.of("file_path", "/x")).build();
        ToolUseBlock b = ToolUseBlock.builder().id("toolu_b").name("Bash").input(Map.of("command", "ls")).build();
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(a, b))),
                wrap(result(0.03)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        assertThat(phase.hasTurns()).isFalse();

        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);
        assertThat(steps).isNotEmpty();
        assertThat(steps).extracting(JournalStep::attributionMethod)
                .containsOnly(AttributionMethod.EVEN_SPLIT);
    }

    @Test
    void withinTurnParallelToolSplitStaysProportional() {
        // Per-turn tokens ARE present; the even split among a turn's parallel tools is part of the
        // proportional method, NOT the coarse fallback (A1).
        ToolUseBlock a = ToolUseBlock.builder().id("toolu_a").name("Read").input(Map.of("file_path", "/x")).build();
        ToolUseBlock b = ToolUseBlock.builder().id("toolu_b").name("Read").input(Map.of("file_path", "/y")).build();
        String wire = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":200,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}}";
        List<ParsedMessage> messages = List.of(
                wrapRaw(new AssistantMessage(List.of(a, b)), wire),
                wrap(result(0.02)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);

        assertThat(steps).extracting(JournalStep::attributionMethod)
                .containsOnly(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL);
    }

    @Test
    void residualKeepsSumExactlyEqualToTotal() {
        // Output tokens 1/1/1 across three single-tool turns: 0.10/3 doesn't divide evenly.
        List<ParsedMessage> messages = List.of(
                assistantTurn("msg_1", "toolu_1", "Read", 5, 1),
                assistantTurn("msg_2", "toolu_2", "Read", 5, 1),
                assistantTurn("msg_3", "toolu_3", "Read", 5, 1),
                wrap(result(0.10)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);

        assertThat(steps).hasSize(3);
        double sum = steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum();
        assertThat(sum).isEqualTo(0.10); // exact, residual folded into the last step
    }

    @Test
    void marksSubagentSpawnStepsWithoutFlattening() {
        // A Task tool call is a sub-agent spawn — its interior steps are not in the stream
        // (they live in subagents/*.jsonl, R2.5b). The spawn must be marked, not flattened.
        List<ParsedMessage> messages = List.of(
                assistantTurn("msg_1", "toolu_read", "Read", 10, 100),
                assistantTurn("msg_2", "toolu_task", "Task", 10, 300),
                wrap(result(0.04)));

        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), RUN, "p");
        List<JournalStep> steps = JournalSteps.fromPhaseCapture(phase, RUN);

        JournalStep read = steps.stream().filter(s -> s.stepId().equals("toolu_read")).findFirst().orElseThrow();
        JournalStep task = steps.stream().filter(s -> s.stepId().equals("toolu_task")).findFirst().orElseThrow();
        assertThat(read.isSubagentSpawn()).isFalse();
        assertThat(task.isSubagentSpawn()).isTrue();
        assertThat(task.toolName()).isEqualTo("Task");
        // still a real, cost-bearing step — not dropped
        assertThat(task.attributedCostUsd()).isGreaterThan(0.0);
    }

    // --- helpers ---

    private static ParsedMessage assistantTurn(String msgId, String toolId, String toolName, long in, long out) {
        ToolUseBlock tool = ToolUseBlock.builder().id(toolId).name(toolName)
                .input(Map.of("file_path", "/f")).build();
        String wire = "{\"type\":\"assistant\",\"message\":{\"id\":\"" + msgId + "\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out
                + ",\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0}}}";
        return wrapRaw(new AssistantMessage(List.of(tool)), wire);
    }

    private static ResultMessage result(double cost) {
        return ResultMessage.builder().durationMs(1000).durationApiMs(800).numTurns(2).sessionId("sess")
                .totalCostUsd(cost).usage(Map.of("input_tokens", 100, "output_tokens", 200)).build();
    }

    private static ParsedMessage wrap(Message message) {
        return ParsedMessage.RegularMessage.of(message);
    }

    private static ParsedMessage wrapRaw(Message message, String rawJson) {
        return ParsedMessage.RegularMessage.of(message, rawJson);
    }
}
