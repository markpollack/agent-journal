package io.github.markpollack.journal.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.agents.geminisdk.types.Cost;
import io.github.markpollack.agents.geminisdk.types.Message;
import io.github.markpollack.agents.geminisdk.types.Metadata;
import io.github.markpollack.agents.geminisdk.types.QueryResult;
import io.github.markpollack.agents.geminisdk.types.ResultStatus;
import io.github.markpollack.agents.geminisdk.types.TextMessage;
import io.github.markpollack.agents.geminisdk.types.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Parallel to {@code SessionLogParserTest}: proves the Gemini extractor projects a QueryResult
 * into the same portable capture + trace as the Claude path.
 */
class GeminiSessionParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static QueryResult successResult(String answer, int promptTokens, int completionTokens, double totalCost,
            long durationMs) {
        Usage usage = new Usage(promptTokens, completionTokens, promptTokens + completionTokens);
        Cost cost = new Cost(BigDecimal.valueOf(totalCost / 3), BigDecimal.valueOf(totalCost * 2 / 3),
                BigDecimal.valueOf(totalCost));
        Metadata metadata = new Metadata("gemini-2.5-pro", Instant.parse("2026-06-16T00:00:00Z"),
                Duration.ofMillis(durationMs), usage, cost);
        List<Message> messages = List.of(TextMessage.user("q"), TextMessage.assistant(answer));
        return QueryResult.of(messages, metadata, ResultStatus.SUCCESS);
    }

    @Test
    void capturesQueryResultFields() {
        GeminiPhaseCapture capture = GeminiSessionParser.parse(
                successResult("the answer", 100, 50, 0.003, 1500), "run-1", "the prompt");

        assertThat(capture.model()).isEqualTo("gemini-2.5-pro");
        assertThat(capture.promptTokens()).isEqualTo(100);
        assertThat(capture.completionTokens()).isEqualTo(50);
        assertThat(capture.totalTokens()).isEqualTo(150);
        assertThat(capture.totalCostUsd()).isCloseTo(0.003, within(1e-9));
        assertThat(capture.durationMs()).isEqualTo(1500);
        assertThat(capture.isError()).isFalse();
        assertThat(capture.status()).isEqualTo("SUCCESS");
        assertThat(capture.textOutput()).isEqualTo("the answer");
    }

    @Test
    void errorStatusMarksCaptureAsError() {
        QueryResult err = QueryResult.of(List.of(TextMessage.error("boom")), Metadata.empty(), ResultStatus.ERROR);
        GeminiPhaseCapture capture = GeminiSessionParser.parse(err, "run-1", "p");
        assertThat(capture.isError()).isTrue();
        assertThat(capture.status()).isEqualTo("ERROR");
    }

    @Test
    void writesPortableTraceReadableByTheSameLoaderContract(@TempDir Path tempDir) throws IOException {
        Path traceFile = tempDir.resolve("gemini-trace.jsonl");
        GeminiSessionParser.parse(successResult("hi", 100, 50, 0.003, 1500), "run-1", "p", traceFile);

        List<String> lines = Files.readAllLines(traceFile);
        // header, text, result, step_cost
        assertThat(lines.get(0)).contains("\"type\":\"header\"").contains("\"schemaVersion\":2");
        assertThat(lines).anySatisfy(l -> assertThat(l).contains("\"type\":\"text\"").contains("\"content\":\"hi\""));

        JsonNode result = findLine(lines, "result");
        // loader-contract keys present on a Gemini trace
        assertThat(result.get("inputTokens").asInt()).isEqualTo(100);
        assertThat(result.get("outputTokens").asInt()).isEqualTo(50);
        assertThat(result.get("durationMs").asLong()).isEqualTo(1500);
        assertThat(result.get("subtype").asText()).isEqualTo("SUCCESS");

        JsonNode step = findLine(lines, "step_cost");
        assertThat(step.get("stepId").asText()).isEqualTo("run-1:turn");
        assertThat(step.get("attributionMethod").asText()).isEqualTo("OUTPUT_TOKEN_PROPORTIONAL");
        assertThat(step.get("attributedCostUsd").asDouble()).isCloseTo(0.003, within(1e-9));
        assertThat(step.get("actualRunCostUsd").asDouble()).isCloseTo(0.003, within(1e-9));
    }

    private static JsonNode findLine(List<String> lines, String type) throws IOException {
        for (String line : lines) {
            JsonNode node = MAPPER.readTree(line);
            if (type.equals(node.get("type").asText())) {
                return node;
            }
        }
        throw new AssertionError("no line of type " + type);
    }
}
