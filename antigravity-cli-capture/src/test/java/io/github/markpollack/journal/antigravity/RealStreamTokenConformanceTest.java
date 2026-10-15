package io.github.markpollack.journal.antigravity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token identities on real captured Antigravity CLI streams (agy 1.2.6, synthetic prompts, identifiers
 * scrubbed). The oracle reads the same lines with plain Jackson, independently of
 * {@link AntigravitySessionParser}.
 *
 * <ol>
 *   <li>the capture's totals equal the {@code result} event's usage;</li>
 *   <li>the per-call usage the capture records adds up to those totals (so the vendor's own numbers agree
 *       and nothing is dropped between level 0 and level 1);</li>
 *   <li>the capture records one call per usage-bearing step, with the stream's figures;</li>
 *   <li>a stream cut off before its {@code result} event is incomplete, and its totals are the sum of the
 *       calls already delivered.</li>
 * </ol>
 */
@DisplayName("Token identities on real captured Antigravity streams")
class RealStreamTokenConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ROOT = "/fixtures/real-streams/";

    private record Usage(int input, int output, int thinking, int cacheRead) {
        static Usage of(JsonNode u) {
            return new Usage(u.path("input_tokens").asInt(), u.path("output_tokens").asInt(),
                    u.path("thinking_tokens").asInt(), u.path("cache_read_tokens").asInt());
        }

        Usage plus(Usage o) {
            return new Usage(input + o.input, output + o.output, thinking + o.thinking, cacheRead + o.cacheRead);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"read-three-files-a", "read-three-files-b"})
    @DisplayName("1 and 2: totals equal the result usage and the sum of the per-call usage")
    void totalsEqualResultAndSumOfCalls(String fixture) throws Exception {
        List<String> lines = lines(fixture);
        AntigravityPhaseCapture capture = parse(lines);

        Usage result = null;
        Map<Integer, Usage> perStep = new LinkedHashMap<>();
        for (String line : lines) {
            JsonNode d = MAPPER.readTree(line);
            if ("result".equals(d.path("event").asText())) {
                result = Usage.of(d.path("result").path("usage"));
            }
            JsonNode su = d.path("step_update");
            if (su.path("usage").isObject()) {
                perStep.put(su.path("step_index").asInt(), Usage.of(su.path("usage")));
            }
        }
        Usage oracleSum = perStep.values().stream().reduce(new Usage(0, 0, 0, 0), Usage::plus);

        Usage recorded = new Usage(capture.inputTokens(), capture.outputTokens(), capture.thinkingTokens(),
                capture.cacheReadTokens());
        assertThat(recorded).as("capture totals").isEqualTo(result);
        assertThat(oracleSum).as("oracle self-check: the vendor's per-call usage adds up to its result").isEqualTo(result);

        Usage recordedCalls = capture.calls().stream()
                .map(c -> new Usage(c.inputTokens(), c.outputTokens(), c.thinkingTokens(), c.cacheReadTokens()))
                .reduce(new Usage(0, 0, 0, 0), Usage::plus);
        assertThat(recordedCalls).as("sum of the recorded per-call usage").isEqualTo(recorded);
        assertThat(capture.calls()).as("one recorded call per usage-bearing step").hasSize(perStep.size());
        assertThat(capture.calls()).extracting(AntigravityCallUsage::stepIndex)
                .containsExactlyElementsOf(perStep.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"read-three-files-a", "read-three-files-b"})
    @DisplayName("4: a stream cut off before its result is incomplete and keeps the usage already delivered")
    void cutOffStreamKeepsDeliveredUsage(String fixture) throws Exception {
        List<String> all = lines(fixture);
        List<String> cut = all.stream().filter(l -> !l.contains("\"event\":\"result\"")).toList();
        assertThat(cut).hasSize(all.size() - 1);

        AntigravityPhaseCapture capture = parse(cut);

        Usage delivered = new Usage(0, 0, 0, 0);
        for (String line : cut) {
            JsonNode su = MAPPER.readTree(line).path("step_update");
            if (su.path("usage").isObject()) {
                delivered = delivered.plus(Usage.of(su.path("usage")));
            }
        }
        assertThat(capture.isError()).isTrue();
        assertThat(capture.status()).isEqualTo("INCOMPLETE");
        assertThat(new Usage(capture.inputTokens(), capture.outputTokens(), capture.thinkingTokens(),
                capture.cacheReadTokens())).isEqualTo(delivered);
    }

    private static AntigravityPhaseCapture parse(List<String> lines) throws Exception {
        return AntigravitySessionParser.parse(new BufferedReader(new StringReader(String.join("\n", lines))), "run",
                "prompt");
    }

    private static List<String> lines(String fixture) throws Exception {
        Path path = Path.of(RealStreamTokenConformanceTest.class.getResource(ROOT + fixture + ".jsonl").toURI());
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
    }
}
