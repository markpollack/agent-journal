package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.event.TokenUsage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parses the hand-written {@code codex-subagents-synthetic} fixture; see fixtures/README.md. */
class CodexSubagentCaptureTest {

    static final Path FIXTURES = Path.of("src/test/resources/fixtures/codex-subagents-synthetic");
    static final String ROOT = "thread-root-0001";
    static final String CHILD_A = "thread-child-a-0002";
    static final String CHILD_B = "thread-child-b-0003";
    static final String CHILD_C = "thread-child-c-0005";
    static final String ORPHAN = "thread-orphan-0004";

    static CodexRollout rollout(String file, String threadId, String parentThreadId) throws IOException {
        Path path = FIXTURES.resolve(file);
        return new CodexRollout(threadId, parentThreadId, path.toString(), Files.readAllLines(path));
    }

    static CodexRollouts allRollouts() throws IOException {
        return new CodexRollouts(List.of(
                rollout("root.jsonl", ROOT, null),
                rollout("child-a.jsonl", CHILD_A, ROOT),
                rollout("child-b.jsonl", CHILD_B, ROOT),
                rollout("child-c.jsonl", CHILD_C, ROOT),
                rollout("orphan.jsonl", ORPHAN, "thread-unknown-9999")));
    }

    static CodexSubagentCapture byThread(CodexPhaseCapture phase, String threadId) {
        return phase.subagents().stream().filter(s -> threadId.equals(s.threadId())).findFirst().orElseThrow();
    }

    @Test
    void parentTrackKeepsOnlyItsOwnToolUses() throws IOException {
        CodexPhaseCapture phase = CodexSessionParser.parse(allRollouts(), "phase", "Root prompt");

        assertThat(phase.toolUses()).extracting(CodexToolUseRecord::name)
                .containsExactly("spawn_agent", "spawn_agent", "wait_agent", "spawn_agent", "spawn_agent");
        assertThat(phase.toolUses()).extracting(CodexToolUseRecord::id)
                .doesNotContain("call_a_exec", "call_b_exec");
        assertThat(phase.subagentTracksAvailable()).isTrue();
        assertThat(phase.hasSubagents()).isTrue();
        assertThat(phase.sessionId()).isEqualTo(ROOT);
    }

    @Test
    void childIdentitiesAndSpawnJoins() throws IOException {
        CodexPhaseCapture phase = CodexSessionParser.parse(allRollouts(), "phase", "Root prompt");

        assertThat(phase.subagents()).extracting(CodexSubagentCapture::threadId)
                .containsExactlyInAnyOrder(CHILD_A, CHILD_B, CHILD_C, ORPHAN);
        CodexSubagentCapture a = byThread(phase, CHILD_A);
        assertThat(a.spawnCallId()).isEqualTo("call_spawn_a");
        assertThat(a.parentSpawnCallId()).isNull();
        assertThat(a.depth()).isEqualTo(1);
        assertThat(a.agentPath()).isEqualTo("workers/checker");
        assertThat(a.forked()).isFalse();
        assertThat(a.promptText()).isEqualTo("Check A");
        assertThat(a.textOutput()).isEqualTo("A done");
        assertThat(a.toolUses()).extracting(CodexToolUseRecord::id).containsExactly("call_a_exec");
        assertThat(a.durationMs()).isEqualTo(1500L);
        assertThat(byThread(phase, CHILD_B).spawnCallId()).isEqualTo("call_spawn_b");
    }

    @Test
    void forkedChildSkipsReplayedRecordsAndKeepsFirstSessionMeta() throws IOException {
        CodexSubagentCapture b = byThread(CodexSessionParser.parse(allRollouts(), "phase", "p"), CHILD_B);

        assertThat(b.forked()).isTrue();
        assertThat(b.replayedRecordsSkipped()).isEqualTo(3);
        assertThat(b.threadId()).isEqualTo(CHILD_B);
        assertThat(b.cliVersion()).isEqualTo("0.160.1-synthetic");
        assertThat(b.promptText()).isEqualTo("Lint B");
        assertThat(b.toolUses()).extracting(CodexToolUseRecord::id).containsExactly("call_b_exec");
        assertThat(b.durationMs()).isEqualTo(900L);
        assertThat(b.textOutput()).isEqualTo("B done");
    }

    @Test
    void statusesAndSources() throws IOException {
        CodexPhaseCapture phase = CodexSessionParser.parse(allRollouts(), "phase", "p");

        CodexSubagentCapture a = byThread(phase, CHILD_A);
        assertThat(a.status()).isEqualTo("completed");
        assertThat(a.statusSource()).isEqualTo("parent_sub_agent_activity_last");
        CodexSubagentCapture b = byThread(phase, CHILD_B);
        assertThat(b.status()).isEqualTo("interrupted"); // the parent's activity outranks B's own task_complete
        assertThat(b.statusSource()).isEqualTo("parent_sub_agent_activity_last");
        CodexSubagentCapture c = byThread(phase, CHILD_C);
        assertThat(c.spawnCallId()).isEqualTo("call_spawn_c");
        assertThat(c.status()).isEqualTo("unknown"); // started only; no later activity, no own turn end
        assertThat(c.statusSource()).isEqualTo("none");
        assertThat(c.durationMs()).isEqualTo(-1L);
        CodexSubagentCapture orphan = byThread(phase, ORPHAN);
        assertThat(orphan.status()).isEqualTo("interrupted"); // its own last turn-terminal record is turn_aborted
        assertThat(orphan.statusSource()).isEqualTo("child_last_turn");
        assertThat(orphan.spawnCallId()).isNull();
        assertThat(orphan.parentSpawnCallId()).isNull();
        assertThat(orphan.depth()).isEqualTo(-1);
    }

    @Test
    void spawnsWithoutTrack() throws IOException {
        CodexPhaseCapture phase = CodexSessionParser.parse(allRollouts(), "phase", "p");
        assertThat(phase.subagentsWithoutTrack())
                .containsExactly(new CodexPhaseCapture.SpawnWithoutTrack("call_spawn_d", "spawn_failed")); // C is collected

        List<CodexRollout> withoutB = new ArrayList<>(allRollouts().rollouts());
        withoutB.removeIf(r -> CHILD_B.equals(r.threadId()));
        CodexPhaseCapture partial = CodexSessionParser.parse(new CodexRollouts(withoutB), "phase", "p");
        assertThat(partial.subagentsWithoutTrack()).containsExactly(
                new CodexPhaseCapture.SpawnWithoutTrack("call_spawn_b", "not_collected"),
                new CodexPhaseCapture.SpawnWithoutTrack("call_spawn_d", "spawn_failed"));
    }

    @Test
    void tokensArePerTrackAndParentIsItsOwnLastTokenCount() throws IOException {
        CodexPhaseCapture phase = CodexSessionParser.parse(allRollouts(), "phase", "p");

        assertThat(phase.inputTokens()).isEqualTo(1200);
        assertThat(phase.tokenUsage()).isEqualTo(new TokenUsage(700, 300, 80, 0, 500, 0));
        assertThat(byThread(phase, CHILD_A).tokenUsage()).isEqualTo(new TokenUsage(200, 40, 10, 0, 100, 0));
        assertThat(byThread(phase, CHILD_B).tokenUsage()).isEqualTo(new TokenUsage(300, 60, 20, 0, 200, 0));
        assertThat(byThread(phase, CHILD_C).tokenUsage()).isEqualTo(new TokenUsage(50, 5, 1, 0, 30, 0));
        assertThat(byThread(phase, ORPHAN).tokenUsage()).isEqualTo(new TokenUsage(100, 10, 0, 0, 0, 0));
    }

    @Test
    void singleFileParseOfRootMatchesMultiFileCore() throws IOException {
        CodexPhaseCapture single = CodexSessionParser.parse(FIXTURES.resolve("root.jsonl"), "phase", "p");
        CodexPhaseCapture multi = CodexSessionParser.parse(allRollouts(), "phase", "p");

        assertThat(single.subagents()).isEmpty();
        assertThat(single.subagentTracksAvailable()).isFalse();
        assertThat(single.subagentsWithoutTrack()).isEmpty();
        assertThat(new CodexPhaseCapture(multi.phaseName(), multi.promptText(), multi.model(),
                multi.cliVersion(), multi.sessionId(), multi.inputTokens(), multi.outputTokens(),
                multi.reasoningOutputTokens(), multi.cacheWriteInputTokens(), multi.cachedInputTokens(),
                multi.durationMs(), multi.isError(), multi.textOutput(), multi.toolUses())).isEqualTo(single);
    }

    @Test
    void rootMustBeUnique() throws IOException {
        assertThatThrownBy(() -> new CodexRollouts(List.of(rollout("child-a.jsonl", CHILD_A, ROOT))).root())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("found 0");
        assertThatThrownBy(() -> new CodexRollouts(List.of(
                rollout("root.jsonl", ROOT, null), rollout("root.jsonl", "other", null))).root())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("found 2");
    }
}
