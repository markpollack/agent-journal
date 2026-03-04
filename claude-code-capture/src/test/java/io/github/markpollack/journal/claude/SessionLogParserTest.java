/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.claude.agent.sdk.parsing.ParsedMessage;
import org.springaicommunity.claude.agent.sdk.types.AssistantMessage;
import org.springaicommunity.claude.agent.sdk.types.ContentBlock;
import org.springaicommunity.claude.agent.sdk.types.ResultMessage;
import org.springaicommunity.claude.agent.sdk.types.TextBlock;
import org.springaicommunity.claude.agent.sdk.types.ThinkingBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolResultBlock;
import org.springaicommunity.claude.agent.sdk.types.ToolUseBlock;
import org.springaicommunity.claude.agent.sdk.types.UserMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionLogParserTest {

    @Test
    void parsesTextFromAssistantMessage() {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("Hello world")))),
                wrap(resultMessage(0.01, 1000, 500, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "test prompt");

        assertThat(capture.textOutput()).isEqualTo("Hello world");
        assertThat(capture.phaseName()).isEqualTo("explore");
    }

    @Test
    void capturesPromptText() {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("response")))),
                wrap(resultMessage(0.01, 1000, 500, 100, 50))
        );

        String prompt = "Analyze the project structure and identify javax imports";
        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", prompt);

        assertThat(capture.promptText()).isEqualTo(prompt);
    }

    @Test
    void extractsTokenCountsFromUsageMap() {
        List<ParsedMessage> messages = List.of(
                wrap(resultMessage(0.05, 5000, 2000, 800, 100))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "plan", "plan prompt");

        assertThat(capture.inputTokens()).isEqualTo(800);
        assertThat(capture.outputTokens()).isEqualTo(100);
        assertThat(capture.totalTokens()).isEqualTo(900);
    }

    @Test
    void extractsThinkingTokensFromUsageMap() {
        ResultMessage result = ResultMessage.builder()
                .durationMs(3000)
                .durationApiMs(2500)
                .numTurns(2)
                .sessionId("sess-123")
                .totalCostUsd(0.03)
                .usage(Map.of("input_tokens", 500, "output_tokens", 200, "thinking_tokens", 150))
                .build();

        List<ParsedMessage> messages = List.of(wrap(result));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "test prompt");

        assertThat(capture.thinkingTokens()).isEqualTo(150);
        assertThat(capture.totalTokens()).isEqualTo(850); // 500 + 200 + 150
    }

    @Test
    void extractsCostAndTimingFromResultMessage() {
        List<ParsedMessage> messages = List.of(
                wrap(resultMessage(0.05, 5000, 4000, 800, 100))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "plan", "plan prompt");

        assertThat(capture.totalCostUsd()).isEqualTo(0.05);
        assertThat(capture.durationMs()).isEqualTo(5000);
        assertThat(capture.apiDurationMs()).isEqualTo(4000);
    }

    @Test
    void extractsSessionMetadata() {
        ResultMessage result = ResultMessage.builder()
                .durationMs(3000)
                .durationApiMs(2000)
                .numTurns(5)
                .sessionId("sess-abc")
                .isError(true)
                .totalCostUsd(0.01)
                .usage(Map.of("input_tokens", 100, "output_tokens", 50))
                .build();

        List<ParsedMessage> messages = List.of(wrap(result));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "execute prompt");

        assertThat(capture.sessionId()).isEqualTo("sess-abc");
        assertThat(capture.numTurns()).isEqualTo(5);
        assertThat(capture.isError()).isTrue();
    }

    @Test
    void capturesThinkingBlocks() {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(
                        ThinkingBlock.of("Analyzing dependencies..."),
                        new TextBlock("Here is the plan")
                ))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "test prompt");

        assertThat(capture.hasThinking()).isTrue();
        assertThat(capture.thinkingBlocks()).containsExactly("Analyzing dependencies...");
        assertThat(capture.textOutput()).isEqualTo("Here is the plan");
    }

    @Test
    void capturesToolUses() {
        ToolUseBlock readTool = ToolUseBlock.builder()
                .id("tool-1")
                .name("Read")
                .input(Map.of("file_path", "/pom.xml"))
                .build();
        ToolUseBlock writeTool = ToolUseBlock.builder()
                .id("tool-2")
                .name("Write")
                .input(Map.of("file_path", "/pom.xml", "content", "<project/>"))
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(readTool, new TextBlock("Done"), writeTool))),
                wrap(resultMessage(0.02, 2000, 1500, 300, 100))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "plan", "plan prompt");

        assertThat(capture.hasToolUses()).isTrue();
        assertThat(capture.toolUses()).hasSize(2);
        assertThat(capture.toolUses().get(0).name()).isEqualTo("Read");
        assertThat(capture.toolUses().get(0).id()).isEqualTo("tool-1");
        assertThat(capture.toolUses().get(1).name()).isEqualTo("Write");
    }

    @Test
    void concatenatesTextFromMultipleAssistantMessages() {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("Part 1")))),
                wrap(new AssistantMessage(List.of(new TextBlock(" Part 2")))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "execute prompt");

        assertThat(capture.textOutput()).isEqualTo("Part 1 Part 2");
    }

    @Test
    void skipsNonRegularMessages() {
        List<ParsedMessage> messages = List.of(
                ParsedMessage.EndOfStream.INSTANCE,
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        Iterator<ParsedMessage> iter = messages.stream()
                .filter(pm -> !(pm instanceof ParsedMessage.EndOfStream))
                .iterator();

        PhaseCapture capture = SessionLogParser.parse(iter, "explore", "test prompt");

        assertThat(capture.totalCostUsd()).isEqualTo(0.01);
    }

    @Test
    void handlesEmptyResponse() {
        List<ParsedMessage> messages = List.of();

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "test prompt");

        assertThat(capture.phaseName()).isEqualTo("explore");
        assertThat(capture.textOutput()).isEmpty();
        assertThat(capture.inputTokens()).isZero();
        assertThat(capture.totalCostUsd()).isZero();
        assertThat(capture.hasThinking()).isFalse();
        assertThat(capture.hasToolUses()).isFalse();
    }

    @Test
    void handlesNullUsageMap() {
        ResultMessage result = ResultMessage.builder()
                .durationMs(1000)
                .totalCostUsd(0.01)
                .build();

        List<ParsedMessage> messages = List.of(wrap(result));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "plan", "plan prompt");

        assertThat(capture.inputTokens()).isZero();
        assertThat(capture.outputTokens()).isZero();
        assertThat(capture.thinkingTokens()).isZero();
    }

    @Test
    void handlesNullTotalCost() {
        ResultMessage result = ResultMessage.builder()
                .durationMs(1000)
                .usage(Map.of("input_tokens", 100, "output_tokens", 50))
                .build();

        List<ParsedMessage> messages = List.of(wrap(result));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "plan", "plan prompt");

        assertThat(capture.totalCostUsd()).isZero();
    }

    @Test
    void capturesRawResult() {
        ResultMessage result = ResultMessage.builder()
                .durationMs(1000)
                .totalCostUsd(0.01)
                .result("The migration is complete.")
                .usage(Map.of("input_tokens", 100, "output_tokens", 50))
                .build();

        List<ParsedMessage> messages = List.of(wrap(result));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "act", "act prompt");

        assertThat(capture.rawResult()).isEqualTo("The migration is complete.");
    }

    @Test
    void estimatesThinkingTokensWhenNotInUsageMap() {
        // 40 chars of thinking → ~10 tokens at 4 chars/token
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(
                        ThinkingBlock.of("This is a forty character thinking text."),
                        new TextBlock("result")
                ))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "test prompt");

        assertThat(capture.thinkingTokens()).isEqualTo(10); // 40 chars / 4
    }

    @Test
    void capturesToolResultsFromUserMessages() {
        ToolUseBlock readTool = ToolUseBlock.builder()
                .id("tool-1")
                .name("Read")
                .input(Map.of("file_path", "/pom.xml"))
                .build();
        ToolResultBlock result = ToolResultBlock.builder()
                .toolUseId("tool-1")
                .content("<project>...</project>")
                .isError(false)
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(readTool))),
                wrap(UserMessage.of(List.of(result))),
                wrap(resultMessage(0.02, 2000, 1500, 300, 100))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "test prompt");

        assertThat(capture.hasToolResults()).isTrue();
        assertThat(capture.toolResults()).hasSize(1);
        assertThat(capture.toolResults().get(0).toolUseId()).isEqualTo("tool-1");
        assertThat(capture.toolResults().get(0).content()).isEqualTo("<project>...</project>");
        assertThat(capture.toolResults().get(0).isError()).isFalse();
    }

    @Test
    void capturesToolResultErrors() {
        ToolResultBlock errorResult = ToolResultBlock.builder()
                .toolUseId("tool-2")
                .content("Permission denied")
                .isError(true)
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(UserMessage.of(List.of(errorResult))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "test prompt");

        assertThat(capture.toolResults()).hasSize(1);
        assertThat(capture.toolResults().get(0).isError()).isTrue();
        assertThat(capture.toolResults().get(0).content()).isEqualTo("Permission denied");
    }

    @Test
    void toolResultsEmptyWhenNoUserMessages() {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("Hello")))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "test prompt");

        assertThat(capture.toolResults()).isEmpty();
    }

    @Test
    void writesJsonlTraceFile(@TempDir Path tempDir) throws IOException {
        ToolUseBlock readTool = ToolUseBlock.builder()
                .id("tool-1")
                .name("Read")
                .input(Map.of("file_path", "/pom.xml"))
                .build();
        ToolResultBlock result = ToolResultBlock.builder()
                .toolUseId("tool-1")
                .content("<project>data</project>")
                .isError(false)
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(
                        ThinkingBlock.of("analyzing..."),
                        readTool,
                        new TextBlock("Done reading")
                ))),
                wrap(UserMessage.of(List.of(result))),
                wrap(resultMessage(0.03, 3000, 2500, 400, 200))
        );

        Path traceFile = tempDir.resolve("trace.jsonl");
        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile);

        assertThat(capture.phaseName()).isEqualTo("test");
        assertThat(traceFile).exists();

        List<String> lines = Files.readAllLines(traceFile);
        assertThat(lines).hasSize(5); // thinking, tool_use, text, tool_result, result
        assertThat(lines.get(0)).contains("\"type\":\"thinking\"");
        assertThat(lines.get(1)).contains("\"type\":\"tool_use\"").contains("\"name\":\"Read\"");
        assertThat(lines.get(2)).contains("\"type\":\"text\"");
        assertThat(lines.get(3)).contains("\"type\":\"tool_result\"").contains("\"isError\":false");
        assertThat(lines.get(4)).contains("\"type\":\"result\"").contains("\"inputTokens\":400");
    }

    @Test
    void parseWithNullTraceFileWorksNormally() {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("Hello")))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "test", "prompt", null);

        assertThat(capture.textOutput()).isEqualTo("Hello");
    }

    // --- Helpers ---

    private static ParsedMessage wrap(org.springaicommunity.claude.agent.sdk.types.Message message) {
        return ParsedMessage.RegularMessage.of(message);
    }

    private static ResultMessage resultMessage(double cost, int durationMs, int apiDurationMs,
                                                int inputTokens, int outputTokens) {
        return ResultMessage.builder()
                .durationMs(durationMs)
                .durationApiMs(apiDurationMs)
                .numTurns(3)
                .sessionId("sess-test")
                .totalCostUsd(cost)
                .usage(Map.of("input_tokens", inputTokens, "output_tokens", outputTokens))
                .build();
    }
}
