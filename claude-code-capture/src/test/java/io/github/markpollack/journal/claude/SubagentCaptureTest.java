package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.trace.TraceContentMode;
import io.github.markpollack.journal.trace.TraceRawMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-agent activity is kept apart from the main loop's, each sub-agent is linked to the tool call
 * that started it, and no token is counted twice. The session is the hand-written
 * {@code fixtures/claude-subagents-synthetic.jsonl}; its README has the arithmetic.
 */
@DisplayName("Sub-agent capture from a Claude Code stream")
class SubagentCaptureTest {

    static final String FIXTURE = "/fixtures/claude-subagents-synthetic.jsonl";

    /** Parses the fixture the way the SDK hands a session to the parser: typed message plus wire line. */
    static List<ParsedMessage> fixtureMessages() throws Exception {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(SubagentCaptureTest.class.getResource(FIXTURE).toURI()))) {
            if (!line.isBlank()) {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
        }
        return messages;
    }

    static PhaseCapture parseFixture() throws Exception {
        return SessionLogParser.parse(fixtureMessages().iterator(), "execute", "the prompt");
    }

    private static SubagentCapture subagent(PhaseCapture capture, String spawnToolUseId) {
        return capture.subagents().stream().filter(s -> s.spawnToolUseId().equals(spawnToolUseId))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the main loop holds only its own text, thinking, tool calls and results")
    void mainLoopHoldsOnlyItsOwnActivity() throws Exception {
        PhaseCapture capture = parseFixture();

        assertThat(capture.textOutput()).isEqualTo("Delegating.Done.");
        assertThat(capture.thinkingBlocks()).containsExactly("Split the work in two.");
        assertThat(capture.toolUses()).extracting(ToolUseRecord::id).containsExactly(
                "toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_refused", "toolu_spawn_d");
        assertThat(capture.toolResults()).extracting(ToolResultRecord::toolUseId).containsExactly(
                "toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_refused");
        assertThat(capture.subagentTracksAvailable()).isTrue();
    }

    @Test
    @DisplayName("a message sent as several lines is one turn, and its usage is counted once")
    void aMessageIsOneTurn() throws Exception {
        PhaseCapture capture = parseFixture();

        // msg_main_1 arrives as five lines: thinking, text and three tool calls.
        assertThat(capture.turns()).extracting(TurnUsage::messageId)
                .containsExactly("msg_main_1", "msg_main_2", "msg_main_3");
        TurnUsage first = capture.turns().get(0);
        assertThat(first.toolUseIds()).containsExactly("toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_refused");
        assertThat(first.inputTokens()).isEqualTo(10);
        assertThat(first.cacheCreationInputTokens()).isEqualTo(1000);
        assertThat(first.thinkingTokens()).isEqualTo(18);
        assertThat(first.stopReason()).isEqualTo("tool_use");
        assertThat(capture.turns()).extracting(TurnUsage::turnIndex).containsExactly(0, 1, 2);
        // The three tool calls of the first message share its turn; the fourth is in the second.
        assertThat(capture.toolUses()).extracting(ToolUseRecord::turnIndex).containsExactly(0, 0, 0, 1);
        assertThat(capture.toolUses()).extracting(ToolUseRecord::turnId)
                .containsExactly("msg_main_1", "msg_main_1", "msg_main_1", "msg_main_2");
    }

    @Test
    @DisplayName("the main loop's tokens equal the result's usage; all agents together equal its modelUsage")
    void tokensReconcileWithoutDoubleCounting() throws Exception {
        PhaseCapture capture = parseFixture();

        TokenUsage main = capture.aggregateUsage();
        assertThat(main.inputTokens()).isEqualTo(36);
        assertThat(main.cacheCreationTokens()).isEqualTo(1300);
        assertThat(main.cacheReadTokens()).isEqualTo(2200);
        // The same three figures on the result line, which Claude Code reports for the main loop only.
        assertThat(capture.inputTokens()).isEqualTo(36);
        assertThat(capture.cacheCreationInputTokens()).isEqualTo(1300);
        assertThat(capture.cacheReadInputTokens()).isEqualTo(2200);

        TokenUsage all = main;
        for (SubagentCapture subagent : capture.subagents()) {
            all = all.plus(subagent.aggregateUsage());
        }
        // modelUsage in the fixture: 71 input, 3,250 cache creation, 3,700 cache read.
        assertThat(all.inputTokens()).isEqualTo(71);
        assertThat(all.cacheCreationTokens()).isEqualTo(3250);
        assertThat(all.cacheReadTokens()).isEqualTo(3700);
        assertThat(capture.totalCostUsd()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("each sub-agent is linked to the tool call that started it, at its depth")
    void subagentsAreLinkedToTheirSpawn() throws Exception {
        PhaseCapture capture = parseFixture();

        assertThat(capture.subagents()).extracting(SubagentCapture::spawnToolUseId).containsExactly(
                "toolu_spawn_a", "toolu_spawn_b", "toolu_spawn_c", "toolu_spawn_d");
        assertThat(capture.subagents()).extracting(SubagentCapture::depth).containsExactly(1, 1, 2, 1);
        assertThat(capture.subagents()).extracting(SubagentCapture::parentSpawnToolUseId)
                .containsExactly(null, null, "toolu_spawn_b", null);
        assertThat(capture.subagents()).extracting(SubagentCapture::agentId)
                .containsExactly("agent-a", "agent-b", "agent-c", "agent-d");

        SubagentCapture a = subagent(capture, "toolu_spawn_a");
        assertThat(a.subagentType()).isEqualTo("general-purpose");
        assertThat(a.description()).isEqualTo("List changed files");
        assertThat(a.promptText()).isEqualTo("List the files the change touches.");
        assertThat(a.textOutput()).isEqualTo("Looking.One file changed.");
        assertThat(a.toolUses()).singleElement().satisfies(use -> {
            assertThat(use.id()).isEqualTo("toolu_a_bash");
            assertThat(use.name()).isEqualTo("Bash");
            assertThat(use.input()).containsEntry("command", "git diff --name-only");
            assertThat(use.turnId()).isEqualTo("msg_a_1");
            assertThat(use.turnIndex()).isZero();
        });
        assertThat(a.toolResults()).singleElement().satisfies(result -> {
            assertThat(result.toolUseId()).isEqualTo("toolu_a_bash");
            assertThat(result.content()).isEqualTo("CHANGELOG.md");
            assertThat(result.isError()).isFalse();
        });
        assertThat(a.turns()).extracting(TurnUsage::messageId).containsExactly("msg_a_1", "msg_a_2");
        assertThat(a.model()).isEqualTo("claude-synthetic-1");

        // The nested sub-agent's spawning tool call is in its parent's capture, not the main loop's.
        SubagentCapture b = subagent(capture, "toolu_spawn_b");
        assertThat(b.toolUses()).extracting(ToolUseRecord::id).containsExactly("toolu_spawn_c");
        assertThat(b.thinkingBlocks()).containsExactly("The tests are the slow part.");
        assertThat(b.aggregateUsage().thinkingTokens()).isEqualTo(12);
        SubagentCapture c = subagent(capture, "toolu_spawn_c");
        assertThat(c.toolResults()).singleElement().satisfies(result -> assertThat(result.isError()).isTrue());
    }

    @Test
    @DisplayName("a status is recorded only when Claude Code reported one, with where it was read")
    void statusIsReportedNeverInferred() throws Exception {
        PhaseCapture capture = parseFixture();

        SubagentCapture a = subagent(capture, "toolu_spawn_a");
        assertThat(a.status()).isEqualTo("completed");
        assertThat(a.statusSource()).isEqualTo("task_notification");
        assertThat(a.reportedTotalTokens()).isEqualTo(1085);
        assertThat(a.reportedToolUses()).isEqualTo(1);
        assertThat(a.reportedDurationMs()).isEqualTo(1500);

        // No end message for b: its status comes from the summary on the result of its spawning call.
        SubagentCapture b = subagent(capture, "toolu_spawn_b");
        assertThat(b.status()).isEqualTo("completed");
        assertThat(b.statusSource()).isEqualTo("tool_use_result");
        assertThat(b.reportedTotalTokens()).isEqualTo(1312);

        SubagentCapture c = subagent(capture, "toolu_spawn_c");
        assertThat(c.status()).isEqualTo("failed");
        assertThat(c.reportedFailed()).isTrue();

        // d's messages arrived, but nothing says how it ended.
        SubagentCapture d = subagent(capture, "toolu_spawn_d");
        assertThat(d.status()).isNull();
        assertThat(d.statusSource()).isEqualTo("none");
        assertThat(d.reportedFailed()).isFalse();
        assertThat(d.reportedTotalTokens()).isEqualTo(-1);
    }

    @Test
    @DisplayName("a spawn with no sub-agent messages is reported as missing, with Claude Code's own counts")
    void aSpawnWithoutMessagesIsReported() throws Exception {
        PhaseCapture capture = parseFixture();

        assertThat(capture.subagentsWithoutTrack()).containsExactly("toolu_spawn_refused");
        assertThat(capture.reportedSubagentsSpawned()).isEqualTo(4);
        assertThat(capture.reportedSubagentStats()).containsEntry("failed", 1)
                .containsEntry("refused", Map.of("concurrency_limit", 1));
    }

    @Test
    @DisplayName("a sub-agent's stop reason does not become the main loop's")
    void subagentStopReasonStaysWithTheSubagent() throws Exception {
        // Cut the session after the sub-agents' last lines, before the main loop's last message
        // and the result: the last stop reason on the wire is then a sub-agent's "end_turn".
        List<ParsedMessage> cut = fixtureMessages().subList(0, 30);

        PhaseCapture capture = SessionLogParser.parse(cut.iterator(), "execute", "the prompt");

        assertThat(capture.turns().get(capture.turns().size() - 1).stopReason()).isEqualTo("tool_use");
        assertThat(capture.stopReason()).isNotEqualTo(io.github.markpollack.journal.event.StopReason.NATURAL_DONE);
    }

    @Test
    @DisplayName("without wire lines the agents cannot be told apart, and the capture says so")
    void withoutWireLinesTracksAreUnavailable() {
        List<ParsedMessage> messages = List.of(
                ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(
                        new ToolUseBlock("toolu_spawn_x", "Agent", Map.of("prompt", "p"))))),
                ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(new TextBlock("child text")))));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "the prompt");

        assertThat(capture.subagentTracksAvailable()).isFalse();
        assertThat(capture.subagents()).isEmpty();
        // Not "missing": whatever the sub-agent did is merged into the main loop, as before.
        assertThat(capture.subagentsWithoutTrack()).isEmpty();
    }

    @Test
    @DisplayName("the trace stays a flat record of every line, sub-agents' included")
    void traceKeepsEveryLine(@TempDir Path dir) throws Exception {
        Path trace = dir.resolve("trace.jsonl");

        SessionLogParser.parse(fixtureMessages().iterator(), "execute", "the prompt", trace,
                TraceContentMode.FULL, TraceRawMode.NONE);

        List<String> lines = Files.readAllLines(trace);
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"tool_use\"").contains("toolu_a_bash"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"tool_result\"").contains("CHANGELOG.md"));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"text\"").contains("One file changed."));
    }
}
