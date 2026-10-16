package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token and cost identities on real captured Claude Code streams (CLI 2.1.299, synthetic prompts, identifiers
 * scrubbed; see {@code fixtures/real-streams/README.md}).
 *
 * <p>Each fixture is a run's {@code stream-json} output plus an oracle: the same run's native transcript (main
 * file and sub-agent files) reduced to the fields read here. The oracle is computed independently of
 * {@link SessionLogParser} with plain Jackson. Hand-written lines are deliberately not used: an earlier test
 * whose fabricated per-turn output equalled the truth could not see that the stream carries placeholders.
 *
 * <p>Identities (all exact integers, over input, output, cache-read and cache-write tokens):
 * <ol>
 *   <li>the main thread's recorded usage equals the transcript's per-call final usage;</li>
 *   <li>main thread plus sub-agents equals the last {@code modelUsage};</li>
 *   <li>the phase's run totals equal the sum of {@code result.usage} over every result line;</li>
 *   <li>the recorded dollar total equals the last {@code total_cost_usd} and the sum of the per-model costs;</li>
 *   <li>no per-call output figure is a stream placeholder: it is the final value or {@code -1} (not
 *       reported);</li>
 *   <li>no step is weighted by output tokens unless every weight is the final value.</li>
 * </ol>
 */
@DisplayName("Token identities on real captured Claude Code streams")
class RealStreamTokenConformanceTest {

    private static final String ROOT = "/fixtures/real-streams/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long NOT_REPORTED = -1;

    private record Usage(long input, long output, long cacheRead, long cacheWrite) {
        static final Usage ZERO = new Usage(0, 0, 0, 0);

        Usage plus(Usage o) {
            return new Usage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite);
        }

        static Usage of(TokenUsage t) {
            return new Usage(t.inputTokens(), t.outputTokens(), t.cacheReadTokens(), t.cacheCreationTokens());
        }

        static Usage ofWire(JsonNode u) {
            return new Usage(u.path("input_tokens").asLong(), u.path("output_tokens").asLong(),
                    u.path("cache_read_input_tokens").asLong(), u.path("cache_creation_input_tokens").asLong());
        }
    }

    /** What the vendor's own numbers say about the run, computed without Journal. */
    private record Oracle(Usage mainThread, Usage subagents, Usage resultUsageSum, Usage allAgents,
            double totalCostUsd, double modelCostSum, Map<String, Long> finalOutputByMessage, int resultLines) {
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("1: the main thread's recorded usage is the transcript's per-call final usage")
    void mainThreadUsageEqualsTranscript(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        assertThat(Usage.of(capture.aggregateUsage())).as("main-thread aggregateUsage()").isEqualTo(oracle.mainThread());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("2: the all-agents total is the last modelUsage, and the transcripts reconcile with it")
    void allAgentsTotalIsTheLastModelUsage(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        Usage recorded = Usage.ZERO;
        for (ModelCost m : capture.modelCosts()) {
            recorded = recorded.plus(new Usage(m.inputTokens(), m.outputTokens(), m.cacheReadInputTokens(),
                    m.cacheCreationInputTokens()));
        }
        assertThat(recorded).as("sum of the per-model usage on the phase").isEqualTo(oracle.allAgents());
        assertThat(oracle.mainThread().plus(oracle.subagents())).as("oracle self-check: transcripts reconcile with modelUsage")
                .isEqualTo(oracle.allAgents());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("2b: where the sub-agent's interior arrived whole, main thread plus sub-agents equals all agents for input and cache")
    void subagentInputAndCacheReconcile(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        Usage recorded = Usage.of(capture.aggregateUsage());
        for (SubagentCapture sub : capture.subagents()) {
            assertThat(sub.outputReported()).as("a sub-agent's output is not on the stream").isFalse();
            recorded = recorded.plus(Usage.of(sub.aggregateUsage()));
        }
        assertThat(new Usage(recorded.input(), 0, recorded.cacheRead(), recorded.cacheWrite()))
                .isEqualTo(new Usage(oracle.allAgents().input(), 0, oracle.allAgents().cacheRead(),
                        oracle.allAgents().cacheWrite()));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Disabled("Step 12.6: the default stream of this foreground sub-agent run omits one of its "
            + "messages (input 38 recorded, 46 true). Journal must detect the shortfall against the vendor's own "
            + "counts and mark the run incomplete; the check does not exist yet")
    @DisplayName("2c: an incomplete sub-agent interior is detectable")
    void incompleteSubagentInteriorIsMarked() throws Exception {
        PhaseCapture capture = parse("foreground-subagent-one-result");
        assertThat(capture.subagentsWithoutTrack()).as("shortfall must be reported").isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("3: the phase's run totals equal the sum of result.usage over every result line")
    void phaseTotalsSumEveryResultLine(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        Usage recorded = new Usage(capture.inputTokens(), capture.outputTokens(), capture.cacheReadInputTokens(),
                capture.cacheCreationInputTokens());
        assertThat(recorded).as("phase totals over %d result line(s)", oracle.resultLines())
                .isEqualTo(oracle.resultUsageSum());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("4: the dollar total is the last total_cost_usd and the sum of the per-model costs")
    void costTotalIsTheVendorsTotal(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        assertThat(capture.totalCostUsd()).as("totalCostUsd").isCloseTo(oracle.totalCostUsd(),
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(oracle.modelCostSum()).as("oracle self-check: per-model costs").isCloseTo(oracle.totalCostUsd(),
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("5: no per-call output figure is a stream placeholder")
    void perCallOutputIsFinalOrNotReported(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        List<String> wrong = new ArrayList<>();
        List<TurnUsage> turns = new ArrayList<>(capture.turns());
        capture.subagents().forEach(s -> turns.addAll(s.turns()));
        for (TurnUsage turn : turns) {
            Long expected = oracle.finalOutputByMessage().get(turn.messageId());
            long recorded = turn.outputTokens();
            if (recorded != NOT_REPORTED && (expected == null || recorded != expected)) {
                wrong.add(turn.messageId() + ": recorded " + recorded + ", final " + expected);
            }
        }
        assertThat(wrong).as("per-call output must be the final value or -1 (not reported)").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"single-result-parallel-calls", "foreground-subagent-one-result",
            "background-subagent-two-results-a", "background-subagent-two-results-b"})
    @DisplayName("6: no step cost is weighted by output tokens that are not the final values")
    void noStepIsWeightedByPlaceholders(String fixture) throws Exception {
        PhaseCapture capture = parse(fixture);
        Oracle oracle = oracle(fixture);

        List<String> wrong = new ArrayList<>();
        for (JournalStep step : capture.stepCosts()) {
            if (step.attributionMethod() != AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL) {
                continue;
            }
            Long expected = oracle.finalOutputByMessage().get(step.turnId());
            if (expected == null || step.outputTokens() != expected) {
                wrong.add(step.stepId() + ": weight " + step.outputTokens() + ", final " + expected);
            }
        }
        assertThat(wrong).as("steps attributed by OUTPUT_TOKEN_PROPORTIONAL on non-final weights").isEmpty();
    }

    // --- plumbing: parse with Journal, compute the oracle without it -----------------------------------------

    private static PhaseCapture parse(String fixture) throws Exception {
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : lines(Path.of(RealStreamTokenConformanceTest.class
                .getResource(ROOT + fixture + "/stream.jsonl").toURI()))) {
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
            }
            catch (RuntimeException unknownType) {
                // the SDK skips message types it does not know; so does the stream reader
            }
        }
        return SessionLogParser.parse(messages.iterator(), "run", "prompt");
    }

    private static Oracle oracle(String fixture) throws Exception {
        Path dir = Path.of(RealStreamTokenConformanceTest.class.getResource(ROOT + fixture).toURI());

        Usage resultSum = Usage.ZERO;
        Usage allAgents = Usage.ZERO;
        double total = 0;
        double modelCosts = 0;
        int results = 0;
        for (String line : lines(dir.resolve("stream.jsonl"))) {
            JsonNode d = MAPPER.readTree(line);
            if (!"result".equals(d.path("type").asText())) {
                continue;
            }
            results++;
            resultSum = resultSum.plus(Usage.ofWire(d.path("usage")));
            // modelUsage and total_cost_usd are session running totals: the last result line holds the answer
            allAgents = Usage.ZERO;
            modelCosts = 0;
            for (JsonNode m : d.path("modelUsage")) {
                allAgents = allAgents.plus(new Usage(m.path("inputTokens").asLong(), m.path("outputTokens").asLong(),
                        m.path("cacheReadInputTokens").asLong(), m.path("cacheCreationInputTokens").asLong()));
                modelCosts += m.path("costUSD").asDouble();
            }
            total = d.path("total_cost_usd").asDouble();
        }

        Map<String, Long> finalOutput = new HashMap<>();
        Usage main = Usage.ZERO;
        Usage subs = Usage.ZERO;
        List<Path> files;
        try (Stream<Path> s = Files.list(dir.resolve("oracle"))) {
            files = s.sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path file : files) {
            boolean mainFile = file.getFileName().toString().equals("main.jsonl");
            Map<String, Usage> perMessage = new HashMap<>();
            for (String line : lines(file)) {
                JsonNode d = MAPPER.readTree(line);
                if (!"assistant".equals(d.path("type").asText())) {
                    continue;
                }
                JsonNode m = d.path("message");
                Usage u = Usage.ofWire(m.path("usage"));
                // one transcript line per content block; the figures repeat per message, take the maximum
                perMessage.merge(m.path("id").asText(), u, (a, b) -> new Usage(Math.max(a.input, b.input),
                        Math.max(a.output, b.output), Math.max(a.cacheRead, b.cacheRead),
                        Math.max(a.cacheWrite, b.cacheWrite)));
            }
            Usage fileSum = Usage.ZERO;
            for (Map.Entry<String, Usage> e : perMessage.entrySet()) {
                fileSum = fileSum.plus(e.getValue());
                finalOutput.put(e.getKey(), e.getValue().output());
            }
            if (mainFile) {
                main = main.plus(fileSum);
            }
            else {
                subs = subs.plus(fileSum);
            }
        }
        return new Oracle(main, subs, resultSum, allAgents, total, modelCosts, finalOutput, results);
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
    }
}
