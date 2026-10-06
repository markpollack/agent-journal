package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.TokenUsage;
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

class CodexSessionParserTest {

    @AfterEach
    void resetJournal() {
        Journal.reset();
    }

    @Test
    void classifiesNestedExecInputInsteadOfTheOuterExecName() throws Exception {
        CodexPhaseCapture capture = CodexSessionParser.parse(fixture(), "codex-fixture", "release work");

        assertThat(capture.toolUses()).hasSize(6);
        assertThat(capture.toolUses()).extracting(CodexToolUseRecord::name).containsOnly("exec");
        assertThat(capture.toolUses()).extracting(CodexToolUseRecord::kind)
                .containsExactly(ToolKind.SEARCH, ToolKind.READ, ToolKind.READ,
                        ToolKind.READ, ToolKind.READ, ToolKind.READ);
        assertThat(capture.toolUses().stream().map(CodexToolUseRecord::kind).distinct())
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(capture.toolUses()).extracting(CodexToolUseRecord::id).doesNotHaveDuplicates();
        assertThat(capture.toolUses()).allSatisfy(tool -> assertThat(tool.output()).isNotNull());

        CodexToolUseRecord first = capture.toolUses().get(0);
        assertThat(first.input()).containsEntry("codex_tool", "exec_command")
                .containsEntry("classification_source", "input.tools.exec_command.cmd");
        assertThat(first.input().get("command").toString()).contains("rg --files");

        assertThat(capture.cliVersion()).isEqualTo("0.148.0");
        assertThat(capture.inputTokens()).isEqualTo(47_555);
        assertThat(capture.cachedInputTokens()).isEqualTo(41_216);
        assertThat(capture.outputTokens()).isEqualTo(576);
        assertThat(capture.reasoningOutputTokens()).isEqualTo(146);
    }

    @Test
    void tokenUsageExcludesCachedInputFromInputTokens() throws Exception {
        CodexPhaseCapture capture = CodexSessionParser.parse(fixture(), "codex-fixture", "release work");

        // The capture keeps Codex's own counts: 47,555 input, of which 41,216 were read from the cache.
        assertThat(capture.inputTokens()).isEqualTo(47_555);

        TokenUsage usage = capture.tokenUsage();
        assertThat(usage.inputTokens()).isEqualTo(47_555 - 41_216);
        assertThat(usage.cacheReadTokens()).isEqualTo(41_216);
        assertThat(usage.outputTokens()).isEqualTo(576);
        assertThat(usage.thinkingTokens()).isEqualTo(146);
        // Codex's own total for this session is input (cache included) plus output.
        assertThat(usage.total() + usage.cacheReadTokens()).isEqualTo(47_555 + 576);
        assertThat(usage.cacheHitRatio()).isEqualTo(41_216.0 / 47_555.0);
    }

    @Test
    void tokenUsageInputIsNeverNegative() {
        CodexPhaseCapture capture = new CodexPhaseCapture("p", null, "gpt-5", "0.148.0", "s1", 10, 5, 0,
                0, 25, 100L, false, "done", List.of());

        assertThat(capture.tokenUsage().inputTokens()).isZero();
        assertThat(capture.tokenUsage().cacheReadTokens()).isEqualTo(25);
    }

    @Test
    void recordsARealMultiStateToolSequence() throws Exception {
        CodexPhaseCapture capture = CodexSessionParser.parse(fixture(), "codex-fixture", "release work");
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        String runId;
        try (Run run = Journal.run("codex-experiment").start()) {
            runId = run.id();
            new CodexRunRecorder(run).recordPhase(capture);
        }

        assertThat(storage.loadEvents("codex-experiment", runId))
                .filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).toolName())
                .containsOnly("exec");
        assertThat(storage.loadEvents("codex-experiment", runId))
                .filteredOn(ToolCallEvent.class::isInstance)
                .extracting(event -> ((ToolCallEvent) event).kind())
                .containsExactly(ToolKind.SEARCH, ToolKind.READ, ToolKind.READ,
                        ToolKind.READ, ToolKind.READ, ToolKind.READ);
        assertThat(storage.loadDerivedEvents("codex-experiment", runId))
                .hasSize(6)
                .allSatisfy(event -> assertThat(event).isInstanceOf(StepCostEvent.class));
    }

    @Test
    void exitCodeZeroInToolOutputIsNotAFailure() throws Exception {
        CodexPhaseCapture capture = parseWithFirstOutput("Process exited with code 0\\nOutput:\\nok");

        assertThat(capture.toolUses().get(0).isError()).isFalse();
    }

    @Test
    void nonZeroExitCodeInToolOutputIsAFailure() throws Exception {
        CodexPhaseCapture capture = parseWithFirstOutput("Process exited with code 1\\nOutput:\\nboom");

        assertThat(capture.toolUses().get(0).isError()).isTrue();
        assertThat(capture.toolUses().get(0).errorMessage()).contains("Process exited with code 1");
    }

    @Test
    void scriptFailedInToolOutputIsStillAFailure() throws Exception {
        CodexPhaseCapture capture = parseWithFirstOutput("Script failed\\nboom");

        assertThat(capture.toolUses().get(0).isError()).isTrue();
    }

    @Test
    void rolloutCutOffBeforeTaskCompleteIsRecordedAsAnError() throws Exception {
        List<String> lines = Files.readAllLines(fixture());
        assertThat(lines.get(lines.size() - 1)).contains("task_complete");
        String truncated = String.join("\n", lines.subList(0, lines.size() - 1)) + "\n";

        CodexPhaseCapture capture = CodexSessionParser.parse(new BufferedReader(new StringReader(truncated)),
                "codex-fixture", "release work");

        assertThat(capture.isError()).isTrue();
        assertThat(capture.toolUses()).hasSize(6);
    }

    @Test
    void completeRolloutIsNotAnError() throws Exception {
        CodexPhaseCapture capture = CodexSessionParser.parse(fixture(), "codex-fixture", "release work");

        assertThat(capture.isError()).isFalse();
    }

    /**
     * Parses the recorded rollout with the redacted text of its first tool output replaced, so the
     * output patterns can be checked on the real envelope.
     */
    private static CodexPhaseCapture parseWithFirstOutput(String outputText) throws Exception {
        List<String> lines = Files.readAllLines(fixture());
        String redacted = "Verified output redacted at the public-repository boundary.";
        StringBuilder rollout = new StringBuilder();
        boolean replaced = false;
        for (String line : lines) {
            if (!replaced && line.contains("custom_tool_call_output") && line.contains(redacted)) {
                line = line.replace(redacted, outputText);
                replaced = true;
            }
            rollout.append(line).append('\n');
        }
        assertThat(replaced).isTrue();
        return CodexSessionParser.parse(new BufferedReader(new StringReader(rollout.toString())), "p", null);
    }

    private static Path fixture() throws URISyntaxException {
        return Path.of(CodexSessionParserTest.class.getResource("/fixtures/codex-rollout.jsonl").toURI());
    }
}
