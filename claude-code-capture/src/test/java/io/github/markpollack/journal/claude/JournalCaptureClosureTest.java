package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.Message;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DESIGN §2.2 closure: the derived per-step cost layer is (a) eager — it rides on any
 * {@link PhaseCapture} via {@link PhaseCapture#stepCosts()} — and (b) regenerable offline from the
 * persisted immutable log via {@link JournalSteps#fromEvents}, which must equal
 * {@link JournalSteps#fromPhaseCapture}. So losing {@code analysis.jsonl} is recoverable, not fatal.
 */
@DisplayName("Journal capture closure (stepCosts + fromEvents)")
class JournalCaptureClosureTest {

    /** Minimal concrete recorder — exercises the base phase recording without the fail-loud wrapper. */
    private static final class Rec extends BaseRunRecorder {
        Rec(Run run) {
            this.currentRun = run;
        }
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Nested
    @DisplayName("PhaseCapture.stepCosts() — eager, pure")
    class EagerStepCosts {

        @Test
        @DisplayName("sums to total_cost_usd with the float residual on the last step")
        void sumsToTotalWithResidualOnLastStep() {
            // Three single-tool turns, equal output → 0.10/3 doesn't divide evenly.
            PhaseCapture phase = parse(List.of(
                    assistantTurn("msg_1", "toolu_1", "Read", 5, 1),
                    assistantTurn("msg_2", "toolu_2", "Read", 5, 1),
                    assistantTurn("msg_3", "toolu_3", "Read", 5, 1),
                    wrap(result(0.10))));

            List<JournalStep> steps = phase.stepCosts();

            assertThat(steps).hasSize(3);
            assertThat(steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum()).isEqualTo(0.10);
            assertThat(steps).allSatisfy(s -> {
                assertThat(s.actualRunCostUsd()).isEqualTo(0.10);
                assertThat(s.attributionMethod()).isEqualTo(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL);
                assertThat(s.runId()).isNull(); // eager — no Run yet
            });
        }

        @Test
        @DisplayName("equals fromPhaseCapture with a null runId (it is the same derivation)")
        void equalsFromPhaseCaptureWithNullRunId() {
            PhaseCapture phase = parse(multiTurn(0.10));
            assertThat(phase.stepCosts()).isEqualTo(JournalSteps.fromPhaseCapture(phase, null));
        }
    }

    @Nested
    @DisplayName("JournalSteps.fromEvents == fromPhaseCapture")
    class OfflineReDerivation {

        @Test
        @DisplayName("on an in-memory run (no serialization)")
        void equalsInMemory() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);
            PhaseCapture phase = parse(multiTurn(0.10));

            String runId;
            List<JournalStep> expected;
            try (Run run = Journal.run("exp").start()) {
                runId = run.id();
                expected = JournalSteps.fromPhaseCapture(phase, runId);
                new Rec(run).recordPhase(phase);
            }

            List<JournalStep> actual = JournalSteps.fromEvents(storage.loadEvents("exp", runId), runId);
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("on a persisted run reloaded from events.jsonl (the acceptance: persisted == capture)")
        void equalsAfterPersistAndReload(@TempDir Path dir) {
            Journal.configure(new JsonFileStorage(dir));
            PhaseCapture phase = parse(multiTurn(0.04));

            String runId;
            List<JournalStep> expected;
            try (Run run = Journal.run("exp").start()) {
                runId = run.id();
                expected = JournalSteps.fromPhaseCapture(phase, runId);
                new Rec(run).recordPhase(phase);
            }

            // Reload from disk — the transient PhaseCapture is gone; only events.jsonl remains.
            JsonFileStorage reopened = new JsonFileStorage(dir);
            List<JournalEvent> events = reopened.loadEvents("exp", runId);
            List<JournalStep> actual = JournalSteps.fromEvents(events, runId);

            assertThat(actual).isEqualTo(expected);
            assertThat(actual.stream().mapToDouble(JournalStep::attributedCostUsd).sum()).isEqualTo(0.04);
        }

        @Test
        @DisplayName("preserves a tool's error bit through the execution stream")
        void preservesToolErrorBit() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);
            // One turn issuing a tool that errored; the tool_result carries isError=true.
            TurnUsage turn = new TurnUsage("msg_1", "claude-opus-4-8", 100, 200, 0, 0, List.of("toolu_1"));
            PhaseCapture phase = new PhaseCapture("explore", "do it", 100, 200, 0, 0, 0, 1000L, 800L, 0.02,
                    "sess", 1, false, "done", List.of(),
                    List.of(new ToolUseRecord("toolu_1", "Bash", Map.of("command", "boom"))), "done",
                    List.of(new ToolResultRecord("toolu_1", "command failed", true)),
                    List.of(turn), List.of());

            String runId;
            List<JournalStep> expected;
            try (Run run = Journal.run("exp").start()) {
                runId = run.id();
                expected = JournalSteps.fromPhaseCapture(phase, runId);
                new Rec(run).recordPhase(phase);
            }

            List<JournalStep> actual = JournalSteps.fromEvents(storage.loadEvents("exp", runId), runId);
            assertThat(actual).isEqualTo(expected);
            assertThat(actual).singleElement()
                    .satisfies(s -> {
                        assertThat(s.stepId()).isEqualTo("toolu_1");
                        assertThat(s.isError()).isTrue();
                    });
        }

        @Test
        @DisplayName("re-derives each phase independently for a multi-phase run")
        void equalsForMultiPhaseRun() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);
            PhaseCapture explore = parse(multiTurn(0.04));
            PhaseCapture execute = parse(List.of(
                    assistantTurn("msg_x", "toolu_x", "Write", 10, 300), wrap(result(0.06))));

            String runId;
            List<JournalStep> expected;
            try (Run run = Journal.run("exp").start()) {
                runId = run.id();
                Rec rec = new Rec(run);
                rec.recordPhase(explore);
                rec.recordPhase(execute);
                expected = concat(
                        JournalSteps.fromPhaseCapture(explore, runId),
                        JournalSteps.fromPhaseCapture(execute, runId));
            }

            List<JournalStep> actual = JournalSteps.fromEvents(storage.loadEvents("exp", runId), runId);
            assertThat(actual).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("Provenance (A1) — attributionMethod distinguishes precise from coarse")
    class ProvenanceA1 {

        @Test
        @DisplayName("a no-per-turn-tokens capture stamps EVEN_SPLIT, serialized into analysis.jsonl")
        void evenSplitStampedAndSerialized(@TempDir Path dir) throws Exception {
            Journal.configure(new JsonFileStorage(dir));
            PhaseCapture phase = parse(noTurns(0.03));
            assertThat(phase.hasTurns()).isFalse();

            String runId;
            try (Run run = Journal.run("exp").start()) {
                runId = run.id();
                new Rec(run).recordPhase(phase);
            }

            List<DerivedEvent> derived = new JsonFileStorage(dir).loadDerivedEvents("exp", runId);
            assertThat(derived).isNotEmpty();
            assertThat(derived).allSatisfy(d -> assertThat(((StepCostEvent) d).attributionMethod())
                    .isEqualTo(AttributionMethod.EVEN_SPLIT));

            // The distinct value is the durable signal — assert it is actually on the wire.
            Path analysis = dir.resolve("experiments/exp/runs/" + runId + "/analysis.jsonl");
            for (String line : Files.readAllLines(analysis)) {
                if (line.isBlank() || line.contains("\"@type\":\"header\"")) {
                    continue; // A5 schema-version header line
                }
                assertThat(line).contains("\"attributionMethod\":\"EVEN_SPLIT\"");
            }
        }

        @Test
        @DisplayName("a normal capture stamps OUTPUT_TOKEN_PROPORTIONAL (incl. the tool-less turn step)")
        void proportionalForNormalCapture(@TempDir Path dir) {
            Journal.configure(new JsonFileStorage(dir));

            String runId;
            try (Run run = Journal.run("exp").start()) {
                runId = run.id();
                new Rec(run).recordPhase(parse(multiTurn(0.04)));
            }

            List<DerivedEvent> derived = new JsonFileStorage(dir).loadDerivedEvents("exp", runId);
            assertThat(derived).isNotEmpty();
            assertThat(derived).allSatisfy(d -> assertThat(((StepCostEvent) d).attributionMethod())
                    .isEqualTo(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL));
        }
    }

    // --- helpers (mirror JournalStepsTest's message construction) ---

    /** A capture with no rawJson → no per-turn usage → the coarse EVEN_SPLIT fallback. */
    private static List<ParsedMessage> noTurns(double cost) {
        ToolUseBlock a = ToolUseBlock.builder().id("toolu_a").name("Read").input(Map.of("file_path", "/x")).build();
        ToolUseBlock b = ToolUseBlock.builder().id("toolu_b").name("Bash").input(Map.of("command", "ls")).build();
        return List.of(wrap(new AssistantMessage(List.of(a, b))), wrap(result(cost)));
    }

    /** Two turns: one tool call (200 output) + one tool-less final turn (50 output). */
    private static List<ParsedMessage> multiTurn(double cost) {
        String finalWire = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_final\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":50,\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0}}}";
        return List.of(
                assistantTurn("msg_1", "toolu_1", "Bash", 100, 200),
                wrapRaw(new AssistantMessage(List.of(new TextBlock("done"))), finalWire),
                wrap(result(cost)));
    }

    private static PhaseCapture parse(List<ParsedMessage> messages) {
        return SessionLogParser.parse(messages.iterator(), "RUN", "p");
    }

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

    private static List<JournalStep> concat(List<JournalStep> a, List<JournalStep> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
