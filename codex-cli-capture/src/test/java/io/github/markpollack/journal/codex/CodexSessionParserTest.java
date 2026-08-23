package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

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

    private static Path fixture() throws URISyntaxException {
        return Path.of(CodexSessionParserTest.class.getResource("/fixtures/codex-rollout.jsonl").toURI());
    }
}
