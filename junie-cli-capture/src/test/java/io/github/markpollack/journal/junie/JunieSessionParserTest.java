package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JunieSessionParserTest {

    /**
     * The ACP fixture's own {@code session/prompt} usage block, quoted here as an independent
     * cross-check: these numbers came back over the wire, not out of the trace file.
     */
    private static final int ACP_REPORTED_INPUT = 24_639;
    private static final int ACP_REPORTED_OUTPUT = 1_187;
    private static final int ACP_REPORTED_CACHED_READ = 63_616;
    private static final int ACP_REPORTED_CACHED_WRITE = 0;
    private static final int ACP_REPORTED_TOTAL = 89_442;

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void foldsIncrementalBlockUpdatesIntoDistinctSteps() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        // 21 *BlockUpdatedEvent lines describe 4 tool steps, 2 thoughts and 1 result. Counting
        // lines instead of folding by stepId would report a trajectory five times too long.
        assertThat(capture.toolUses()).hasSize(4);
        assertThat(capture.toolUses()).extracting(JunieToolUseRecord::id).doesNotHaveDuplicates();
    }

    @Test
    void namesStepsByTheVerbatimEventKindAndClassifiesAdditively() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        assertThat(capture.toolUses()).extracting(JunieToolUseRecord::name)
                .containsExactly("ViewFilesBlockUpdatedEvent", "ViewFilesBlockUpdatedEvent",
                        "FileChangesBlockUpdatedEvent", "TerminalBlockUpdatedEvent");
        assertThat(capture.toolUses()).extracting(JunieToolUseRecord::kind)
                .containsExactly(ToolKind.READ, ToolKind.READ, ToolKind.EDIT, ToolKind.EXECUTE);

        // The alphabet must have more than one symbol (Codex's collapse) while still repeating
        // symbols across steps (a per-step unique name is just as useless for a transition
        // matrix). Three distinct names over four steps is a real trajectory.
        assertThat(capture.toolUses().stream().map(JunieToolUseRecord::name).distinct().count())
                .isEqualTo(3);
        assertThat(capture.toolUses().stream().map(JunieToolUseRecord::kind).distinct().count())
                .isEqualTo(3);
    }

    @Test
    void structuredBlockOutranksProseNarrationForTheSameStep() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        JunieToolUseRecord read = capture.toolUses().get(0);
        // Junie emitted ToolBlockUpdatedEvent("Open calc.py") and ViewFilesBlockUpdatedEvent
        // under one stepId. The structured kind names the step; the prose is kept as payload.
        assertThat(read.name()).isEqualTo("ViewFilesBlockUpdatedEvent");
        assertThat(read.input()).containsEntry("files", List.of("calc.py"));
        assertThat(read.input()).containsEntry("description", "Open calc.py");
    }

    @Test
    void foldedUsageMatchesTheUsageBlockAcpReportedOverTheWire() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        assertThat(capture.inputTokens()).isEqualTo(ACP_REPORTED_INPUT);
        assertThat(capture.outputTokens()).isEqualTo(ACP_REPORTED_OUTPUT);
        assertThat(capture.cacheReadTokens()).isEqualTo(ACP_REPORTED_CACHED_READ);
        assertThat(capture.cacheCreationTokens()).isEqualTo(ACP_REPORTED_CACHED_WRITE);

        // Junie's own totalTokens counts cached reads; journal-core's TokenUsage.total() does not.
        // Both are recorded, neither is reinterpreted to make the other agree.
        assertThat(ACP_REPORTED_INPUT + ACP_REPORTED_OUTPUT + ACP_REPORTED_CACHED_READ)
                .isEqualTo(ACP_REPORTED_TOTAL);
        assertThat(capture.tokenUsage().total()).isEqualTo(ACP_REPORTED_INPUT + ACP_REPORTED_OUTPUT);
        assertThat(capture.tokenUsage().cacheReadTokens()).isEqualTo(ACP_REPORTED_CACHED_READ);
    }

    @Test
    void perCallCostsReconcileToTheSessionTotal() throws Exception {
        for (String fixture : List.of("junie-acp-events.jsonl", "junie-cli-events.jsonl")) {
            JuniePhaseCapture capture = parse(fixture);

            assertThat(capture.hasModelCosts()).as(fixture).isTrue();
            assertThat(capture.reconcilesToModelCosts()).as(fixture).isTrue();
            assertThat(capture.modelCostSum())
                    .as(fixture)
                    .isCloseTo(capture.totalCostUsd(),
                            org.assertj.core.data.Offset.offset(JuniePhaseCapture.COST_RECONCILIATION_TOLERANCE_USD));
            assertThat(capture.totalCostUsd()).as(fixture).isGreaterThan(0.0);
        }
    }

    @Test
    void foldsEveryModelNotJustTheLaunchModel() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        // One Junie task fans out across the BYOK main model and internal helper models. All are
        // billed, and folding all of them is what makes the totals match the reported block.
        assertThat(capture.modelCosts()).extracting(JunieModelCost::model).contains("gpt-5.3-codex");
        assertThat(capture.modelCosts().stream().map(JunieModelCost::model).distinct().count())
                .isGreaterThan(1);
        assertThat(capture.numLlmCalls()).isEqualTo(16);
        assertThat(capture.model()).isEqualTo("gpt-5.3-codex");
    }

    @Test
    void capturesThinkingContentButReportsNoThinkingTokens() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        // Junie emits thought blocks on the ACP path but no thinking-token count anywhere, so the
        // content is captured and the token figure stays absent rather than being estimated.
        assertThat(capture.hasThinking()).isTrue();
        assertThat(capture.thinkingBlocks()).hasSize(2);
        assertThat(capture.thinkingBlocks().get(0)).contains("opening the calc file");
        assertThat(capture.tokenUsage().thinkingTokens()).isZero();
    }

    @Test
    void readsPromptAndOutcomeFromTheTraceOnTheAcpPath() throws Exception {
        JuniePhaseCapture capture = JunieSessionParser.parse(
                fixture("junie-acp-events.jsonl"), "junie-acp", "caller-supplied fallback");

        // The trace's own UserPromptEvent is what Junie actually ran; it wins over the argument.
        assertThat(capture.promptText()).isEqualTo(
                "Fix the bug in calc.py and run python3 test_calc.py to prove it passes.");
        assertThat(capture.taskState()).isEqualTo("COMPLETED");
        assertThat(capture.stopReason()).isEqualTo(StopReason.NATURAL_DONE);
        assertThat(capture.wasTruncated()).isFalse();
        assertThat(capture.isError()).isFalse();
        assertThat(capture.maxTurns()).isEqualTo(-1);
        assertThat(capture.durationMs()).isPositive();
        assertThat(capture.patch()).contains("return a + b");
        assertThat(capture.textOutput()).contains("Summary");
    }

    @Test
    void fallsBackToTheCallerPromptWhenTheCliPathEmitsNone() throws Exception {
        JuniePhaseCapture capture = JunieSessionParser.parse(
                fixture("junie-cli-events.jsonl"), "junie-cli", "caller-supplied fallback");

        // The plain-CLI path emits no UserPromptEvent and no TaskState; the outcome then comes
        // from the result block alone.
        assertThat(capture.promptText()).isEqualTo("caller-supplied fallback");
        assertThat(capture.taskState()).isNull();
        assertThat(capture.errorCode()).isEqualTo("Submit");
        assertThat(capture.stopReason()).isEqualTo(StopReason.NATURAL_DONE);
        assertThat(capture.taskId()).isEqualTo("task-260826-171831-hx9l");
    }

    @Test
    void recordsFailedStepsWithTheirExitCodes() throws Exception {
        JuniePhaseCapture capture = parse("junie-cli-events.jsonl");

        // The CLI run really did fail three commands before succeeding: a genuine recovery
        // trajectory, and the error bit must survive capture.
        assertThat(capture.toolUses()).hasSize(7);
        List<JunieToolUseRecord> failed = capture.toolUses().stream()
                .filter(JunieToolUseRecord::isError).toList();
        assertThat(failed).hasSize(3);
        assertThat(failed).extracting(JunieToolUseRecord::exitCode).containsExactly(127, 1, 1);
        assertThat(failed).allSatisfy(t -> assertThat(t.errorMessage()).isNotBlank());

        JunieToolUseRecord passed = capture.toolUses().get(6);
        assertThat(passed.isError()).isFalse();
        assertThat(passed.exitCode()).isZero();
        assertThat(passed.input()).containsEntry("command", "python3 test_calc.py");
        assertThat(passed.output()).asString().contains("ALL TESTS PASSED");
    }

    @Test
    void neverCapturesEnvironmentVariables() throws Exception {
        // Junie's EnvironmentVariablesUpdatedEvent carries the agent's whole environment with
        // values unredacted. A capture flows into events.jsonl and from there into a repository,
        // so nothing from that event may reach the capture. The fixture keeps the event shape
        // with a planted fake key so this assertion is meaningful.
        String rawFixture = Files.readString(fixture("junie-acp-events.jsonl"));
        assertThat(rawFixture).contains("FIXTURE_FAKE_API_KEY");

        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");
        assertThat(capture.toString()).doesNotContain("FIXTURE_FAKE_API_KEY", "not-a-real-secret");
    }

    @Test
    void emitsJournalEventsWithDistinctStatesAndRealCost() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        String runId;
        try (Run run = Journal.run("junie-experiment").start()) {
            runId = run.id();
            new JunieRunRecorder(run).recordPhase(capture);
        }

        List<JournalEvent> events = storage.loadEvents("junie-experiment", runId);
        assertThat(events).filteredOn(ToolCallEvent.class::isInstance)
                .extracting(e -> ((ToolCallEvent) e).kind())
                .containsExactly(ToolKind.READ, ToolKind.READ, ToolKind.EDIT, ToolKind.EXECUTE);
        assertThat(events).filteredOn(ToolCallEvent.class::isInstance)
                .extracting(e -> ((ToolCallEvent) e).toolName())
                .allSatisfy(name -> assertThat(name).endsWith("BlockUpdatedEvent"));

        LLMCallEvent llm = (LLMCallEvent) events.stream()
                .filter(LLMCallEvent.class::isInstance).findFirst().orElseThrow();
        assertThat(llm.metadata()).containsEntry("costAvailable", true)
                .containsEntry("costSource", "reported")
                .containsEntry("costReconciles", true)
                .containsEntry("stopReason", "NATURAL_DONE")
                .containsEntry("maxTurns", -1);

        assertThat(events).filteredOn(e -> e instanceof CustomEvent ce && "thinking_block".equals(ce.name()))
                .hasSize(2);

        assertThat(storage.loadDerivedEvents("junie-experiment", runId))
                .hasSize(4)
                .allSatisfy(e -> assertThat(e).isInstanceOf(StepCostEvent.class));
    }

    @Test
    void attributedStepCostsSumToTheSessionTotal() throws Exception {
        JuniePhaseCapture capture = parse("junie-acp-events.jsonl");

        double attributed = JunieJournalSteps.fromPhaseCapture(capture, "run-1").stream()
                .mapToDouble(io.github.markpollack.journal.trace.JournalStep::attributedCostUsd).sum();
        assertThat(attributed).isCloseTo(capture.totalCostUsd(),
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void ignoresUnknownAndMalformedShapesRatherThanThrowing() throws Exception {
        String lines = String.join("\n",
                "{\"kind\":\"TaskStartedEvent\",\"taskId\":\"task-x\",\"timestampMs\":1000}",
                "{\"kind\":\"SomeFutureTopLevelEvent\",\"payload\":{\"anything\":1}}",
                "{\"kind\":\"SessionA2uxEvent\",\"event\":{\"agentEvent\":{\"kind\":\"SomeFutureAgentEvent\"}}}",
                "{\"kind\":\"SessionA2uxEvent\",\"event\":{}}",
                "{\"noKindAtAll\":true}",
                "{\"kind\":\"SessionA2uxEvent\",\"event\":{\"agentEvent\":{\"kind\":\"TerminalBlockUpdatedEvent\"}}}",
                "");

        JuniePhaseCapture capture = JunieSessionParser.parse(
                new BufferedReader(new StringReader(lines)), "defensive", "p");

        // A step with no stepId is skipped rather than synthesising an identity for it.
        assertThat(capture.toolUses()).isEmpty();
        assertThat(capture.taskId()).isEqualTo("task-x");
        assertThat(capture.stopReason()).isEqualTo(StopReason.UNKNOWN);
        assertThat(capture.isError()).isTrue();
    }

    @Test
    void classifierCoversEveryBlockKindTheFixturesEmit() throws Exception {
        assertThat(JunieToolClassifier.knownToolNames())
                .containsExactlyInAnyOrder("TerminalBlockUpdatedEvent", "ViewFilesBlockUpdatedEvent",
                        "FileChangesBlockUpdatedEvent", "ToolBlockUpdatedEvent");
        assertThat(JunieToolClassifier.classify("SomeFutureBlockUpdatedEvent")).isEqualTo(ToolKind.OTHER);
        assertThat(JunieToolClassifier.classify(null)).isEqualTo(ToolKind.OTHER);
    }

    private static JuniePhaseCapture parse(String name) throws Exception {
        return JunieSessionParser.parse(fixture(name), "junie", "prompt");
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(JunieSessionParserTest.class.getResource("/fixtures/" + name).toURI());
    }
}
