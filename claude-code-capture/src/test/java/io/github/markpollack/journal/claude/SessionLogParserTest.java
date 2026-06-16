package io.github.markpollack.journal.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ContentBlock;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import io.github.markpollack.claude.agent.sdk.types.ThinkingBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolResultBlock;
import io.github.markpollack.claude.agent.sdk.types.ToolUseBlock;
import io.github.markpollack.claude.agent.sdk.types.UserMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
        assertThat(lines).hasSize(6); // header, thinking, tool_use, text, tool_result, result
        assertThat(lines.get(0)).contains("\"type\":\"header\"").contains("\"schemaVersion\":2");
        assertThat(lines.get(1)).contains("\"type\":\"thinking\"").contains("\"content\":\"analyzing...\"");
        assertThat(lines.get(2)).contains("\"type\":\"tool_use\"").contains("\"name\":\"Read\"")
                .contains("\"input\":{").contains("\"file_path\":\"/pom.xml\"");
        assertThat(lines.get(3)).contains("\"type\":\"text\"").contains("\"content\":\"Done reading\"");
        assertThat(lines.get(4)).contains("\"type\":\"tool_result\"").contains("\"isError\":false")
                .contains("\"content\":\"<project>data</project>\"");
        assertThat(lines.get(5)).contains("\"type\":\"result\"").contains("\"inputTokens\":400")
                .contains("\"sessionId\":\"sess-test\"");
    }

    @Test
    void traceFileIncludesToolInputs(@TempDir Path tempDir) throws IOException {
        ToolUseBlock readTool = ToolUseBlock.builder()
                .id("tool-1")
                .name("Read")
                .input(Map.of("file_path", "/project/pom.xml"))
                .build();
        ToolUseBlock bashTool = ToolUseBlock.builder()
                .id("tool-2")
                .name("Bash")
                .input(Map.of("command", "./mvnw test"))
                .build();
        ToolUseBlock grepTool = ToolUseBlock.builder()
                .id("tool-3")
                .name("Grep")
                .input(Map.of("pattern", "TODO", "path", "src/main"))
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(readTool, bashTool, grepTool))),
                wrap(resultMessage(0.03, 3000, 2500, 400, 200))
        );

        Path traceFile = tempDir.resolve("trace-inputs.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile);

        List<String> lines = Files.readAllLines(traceFile);
        // 1 header + 3 tool_use + 1 result = 5 lines
        assertThat(lines).hasSize(5);

        // Read tool includes file_path
        assertThat(lines.get(1)).contains("\"name\":\"Read\"")
                .contains("\"file_path\":\"/project/pom.xml\"");

        // Bash tool includes command
        assertThat(lines.get(2)).contains("\"name\":\"Bash\"")
                .contains("\"command\":\"./mvnw test\"");

        // Grep tool includes pattern
        assertThat(lines.get(3)).contains("\"name\":\"Grep\"")
                .contains("\"pattern\":\"TODO\"")
                .contains("\"path\":\"src/main\"");
    }

    @Test
    void traceFileEscapesNewlinesInToolInput(@TempDir Path tempDir) throws IOException {
        String multiLineContent = "# Status Report\n> Period: 2026-06-03\n\nAll good.\r\nDone.\tEnd.";
        ToolUseBlock writeTool = ToolUseBlock.builder()
                .id("tool-1")
                .name("Write")
                .input(Map.of("file_path", "/project/status.md", "content", multiLineContent))
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(writeTool))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        Path traceFile = tempDir.resolve("trace-newlines.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile);

        List<String> lines = Files.readAllLines(traceFile);
        // 1 header + 1 tool_use + 1 result = 3 lines (not more — newlines must be escaped)
        assertThat(lines).hasSize(3);

        String toolUseLine = lines.get(1);
        assertThat(toolUseLine).contains("\"name\":\"Write\"");
        // Verify escaped sequences appear in the JSON, not raw control characters
        assertThat(toolUseLine).contains("\\n").contains("\\r").contains("\\t");
        assertThat(toolUseLine).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
    }

    @Test
    void extractsCacheTokensFromUsageMap() {
        ResultMessage result = ResultMessage.builder()
                .durationMs(5000)
                .durationApiMs(4000)
                .numTurns(3)
                .sessionId("sess-test")
                .totalCostUsd(0.05)
                .usage(Map.of(
                        "input_tokens", 14,
                        "output_tokens", 200,
                        "cache_creation_input_tokens", 5000,
                        "cache_read_input_tokens", 80000
                ))
                .build();

        List<ParsedMessage> messages = List.of(wrap(result));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "prompt");

        assertThat(capture.inputTokens()).isEqualTo(14);
        assertThat(capture.cacheCreationInputTokens()).isEqualTo(5000);
        assertThat(capture.cacheReadInputTokens()).isEqualTo(80000);
        assertThat(capture.totalInputTokens()).isEqualTo(85014); // 14 + 5000 + 80000
    }

    @Test
    void cacheTokensDefaultToZeroWhenNotInUsageMap() {
        List<ParsedMessage> messages = List.of(
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "explore", "prompt");

        assertThat(capture.cacheCreationInputTokens()).isZero();
        assertThat(capture.cacheReadInputTokens()).isZero();
    }

    @Test
    void traceCapturesEmptyThinkingWithSignatureFaithfully(@TempDir Path tempDir) throws IOException {
        // The empirically-common upstream-redaction case: thinking:"" with a signature.
        // The trace must record what arrived (length 0, hasSignature true) — never
        // fabricate content.
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new ThinkingBlock("", "sig-base64-blob")))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        Path traceFile = tempDir.resolve("trace-redacted.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile);

        List<String> lines = Files.readAllLines(traceFile);
        assertThat(lines.get(1)).contains("\"type\":\"thinking\"")
                .contains("\"length\":0")
                .contains("\"hasSignature\":true");
        // The 2KB signature blob itself is never written
        assertThat(lines.get(1)).doesNotContain("sig-base64-blob");
    }

    @Test
    void lengthsModePinsLengthOnlyBehavior(@TempDir Path tempDir) throws IOException {
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("secret content")))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50))
        );

        Path traceFile = tempDir.resolve("trace-lengths.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile, TraceContentMode.LENGTHS);

        List<String> lines = Files.readAllLines(traceFile);
        assertThat(lines.get(0)).contains("\"contentMode\":\"LENGTHS\"");
        assertThat(lines.get(1)).contains("\"type\":\"text\"").contains("\"length\":14")
                .doesNotContain("secret content");
    }

    @Test
    void truncatedToolResultGetsSourcePointerFromMatchingToolUse(@TempDir Path tempDir) throws IOException {
        ToolUseBlock readTool = ToolUseBlock.builder()
                .id("tool-1")
                .name("Read")
                .input(Map.of("file_path", "/work/big.dat"))
                .build();
        String bigContent = "x".repeat(60_001);
        ToolResultBlock result = ToolResultBlock.builder()
                .toolUseId("tool-1")
                .content(bigContent)
                .isError(false)
                .build();

        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(readTool))),
                wrap(UserMessage.of(List.of(result))),
                wrap(resultMessage(0.02, 2000, 1500, 300, 100))
        );

        Path traceFile = tempDir.resolve("trace-truncated.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile);

        List<String> lines = Files.readAllLines(traceFile);
        String toolResultLine = lines.get(2);
        assertThat(toolResultLine).contains("\"type\":\"tool_result\"")
                .contains("\"contentLength\":60001")
                .contains("\"truncated\":true")
                .contains("\"kind\":\"file_path\"")
                .contains("\"value\":\"/work/big.dat\"");
    }

    @Test
    void rawModeFullPersistsVerbatimWireWithUnmodeledFields(@TempDir Path tempDir) throws IOException {
        // The wire envelope the typed Message drops (sub-agent linkage + permission_denials).
        String assistantWire = "{\"type\":\"assistant\",\"isSidechain\":true,\"parentUuid\":\"u-1\","
                + "\"parent_tool_use_id\":\"toolu_parent\","
                + "\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Hello\"}]}}";
        String resultWire = "{\"type\":\"result\",\"permission_denials\":[{\"tool\":\"Bash\"}]}";

        List<ParsedMessage> messages = List.of(
                wrapRaw(new AssistantMessage(List.of(new TextBlock("Hello"))), assistantWire),
                wrapRaw(resultMessage(0.01, 1000, 800, 100, 50), resultWire));

        Path traceFile = tempDir.resolve("trace-raw.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile, TraceContentMode.TRUNCATED,
                TraceRawMode.FULL);

        List<String> lines = Files.readAllLines(traceFile);
        assertThat(lines.get(0)).contains("\"rawMode\":\"FULL\"");

        // Both raw lines present; the dropped envelope + permission_denials are recoverable.
        long rawLines = lines.stream().filter(l -> l.contains("\"type\":\"raw\"")).count();
        assertThat(rawLines).isEqualTo(2);
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"isSidechain\":true")
                .contains("\"parent_tool_use_id\":\"toolu_parent\""));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"permission_denials\""));

        // The modeled lines (text, result) are still written alongside the raw lines.
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"text\"").contains("\"content\":\"Hello\""));
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"result\""));
    }

    @Test
    void defaultParseEmitsNoRawLines(@TempDir Path tempDir) throws IOException {
        List<ParsedMessage> messages = List.of(
                wrapRaw(new AssistantMessage(List.of(new TextBlock("Hello"))), "{\"type\":\"assistant\"}"),
                wrap(resultMessage(0.01, 1000, 800, 100, 50)));

        Path traceFile = tempDir.resolve("trace-default.jsonl");
        SessionLogParser.parse(messages.iterator(), "test", "prompt", traceFile);

        List<String> lines = Files.readAllLines(traceFile);
        assertThat(lines.get(0)).contains("\"rawMode\":\"NONE\"");
        assertThat(lines).noneMatch(l -> l.contains("\"type\":\"raw\""));
    }

    @Test
    void capturesPerTurnUsageFromAssistantWire() {
        // Per-turn usage lives on the wire message.usage (snake_case); the typed
        // AssistantMessage carries only content, so this comes via rawJson.
        String turn1 = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_1\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":3528,\"cache_creation_input_tokens\":18646,"
                + "\"cache_read_input_tokens\":0,\"output_tokens\":196}}}";
        String turn2 = "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_2\",\"model\":\"claude-opus-4-8\","
                + "\"usage\":{\"input_tokens\":3528,\"cache_creation_input_tokens\":2734,"
                + "\"cache_read_input_tokens\":15857,\"output_tokens\":135}}}";

        List<ParsedMessage> messages = List.of(
                wrapRaw(new AssistantMessage(List.of(new TextBlock("a"))), turn1),
                wrapRaw(new AssistantMessage(List.of(new TextBlock("b"))), turn2),
                wrap(resultMessage(0.05, 5000, 4000, 800, 100)));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "prompt");

        assertThat(capture.hasTurns()).isTrue();
        assertThat(capture.turns()).hasSize(2);
        TurnUsage t1 = capture.turns().get(0);
        assertThat(t1.messageId()).isEqualTo("msg_1");
        assertThat(t1.model()).isEqualTo("claude-opus-4-8");
        assertThat(t1.outputTokens()).isEqualTo(196);
        assertThat(t1.cacheCreationInputTokens()).isEqualTo(18646);
        assertThat(t1.totalInputTokens()).isEqualTo(3528 + 18646 + 0);
        assertThat(capture.turns().get(1).outputTokens()).isEqualTo(135);
    }

    @Test
    void capturesModelCostsThatSumToTotal() {
        // The result wire's modelUsage (camelCase, costUSD) — the exact cost decomposition.
        String resultWire = "{\"type\":\"result\",\"total_cost_usd\":0.094841,\"modelUsage\":{"
                + "\"claude-opus-4-8[1m]\":{\"inputTokens\":3532,\"outputTokens\":288,"
                + "\"cacheReadInputTokens\":56778,\"cacheCreationInputTokens\":6568,\"costUSD\":0.094299},"
                + "\"claude-haiku-4-5-20251001\":{\"inputTokens\":467,\"outputTokens\":15,\"costUSD\":0.000542}}}";

        List<ParsedMessage> messages = List.of(
                wrapRaw(resultMessage(0.094841, 7534, 11560, 3532, 288), resultWire));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "prompt");

        assertThat(capture.hasModelCosts()).isTrue();
        assertThat(capture.modelCosts()).hasSize(2);
        assertThat(capture.modelCosts().get(0).model()).isEqualTo("claude-opus-4-8[1m]");
        assertThat(capture.modelCosts().get(0).costUsd()).isEqualTo(0.094299);
        // R2.2 acceptance: the per-model decomposition sums to the run total (±rounding).
        assertThat(capture.modelCostSum()).isCloseTo(0.094841, within(1e-9));
        assertThat(capture.modelCostSum()).isCloseTo(capture.totalCostUsd(), within(1e-9));
    }

    @Test
    void noPerTurnUsageWhenRawJsonAbsent() {
        // Programmatic construction (wrap → rawJson null) yields no per-turn records.
        List<ParsedMessage> messages = List.of(
                wrap(new AssistantMessage(List.of(new TextBlock("hi")))),
                wrap(resultMessage(0.01, 1000, 800, 100, 50)));

        PhaseCapture capture = SessionLogParser.parse(messages.iterator(), "execute", "prompt");

        assertThat(capture.hasTurns()).isFalse();
        assertThat(capture.hasModelCosts()).isFalse();
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

    private static ParsedMessage wrap(io.github.markpollack.claude.agent.sdk.types.Message message) {
        return ParsedMessage.RegularMessage.of(message);
    }

    private static ParsedMessage wrapRaw(io.github.markpollack.claude.agent.sdk.types.Message message, String rawJson) {
        return ParsedMessage.RegularMessage.of(message, rawJson);
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
