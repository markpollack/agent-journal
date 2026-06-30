package io.github.markpollack.journal.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.journal.event.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CM.3 — the permanent, hermetic, <strong>tokens-only</strong> regression. The test generates a
 * synthetic-but-realistic raw session JSONL in a temp dir (per-turn {@code message.usage} shaped like
 * the real wire: {@code input_tokens:2}, growing {@code cache_read}, a final snapshot that
 * under-counts), reads it back through the <strong>real</strong> {@link SessionLogParser}, and asserts:
 *
 * <ul>
 *   <li><b>exact</b>: the journal's {@link PhaseCapture#aggregateUsage()} equals an independent raw
 *       per-turn sum by type (oracle parsed with a plain {@link ObjectMapper}, not the parser →
 *       non-tautological);</li>
 *   <li><b>guard</b>: the {@link PhaseCapture#snapshotUsage()} under-counts (output + cache_read
 *       strictly below the aggregate) — the bug this fix removes.</li>
 * </ul>
 *
 * No dollars: all raw data is recorded and asserted in tokens (any pricing is a downstream/ACT concern).
 */
@DisplayName("CM cost-metering: conformance (Σ-per-turn == aggregate, tokens only)")
class CostMeteringConformanceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** One assistant turn's billed usage. */
    private record Turn(long in, long out, long cacheCreate, long cacheRead) {
    }

    @Test
    @DisplayName("aggregateUsage equals the independent raw per-turn sum; snapshot under-counts")
    void aggregateEqualsRawSumAndBeatsSnapshot(@TempDir Path dir) throws IOException {
        // Realistic deployer-shaped session: input_tokens:2, cache_read grows as the prefix grows.
        List<Turn> turns = List.of(
                new Turn(2, 200, 20_000, 10_000),
                new Turn(2, 150, 5_000, 16_000),
                new Turn(2, 300, 0, 22_000),
                new Turn(2, 100, 8_000, 30_000),
                new Turn(2, 250, 0, 40_000));
        // Σ: in=10, out=1000, cacheCreate=33000, cacheRead=118000.

        // 1) Write the raw session JSONL anew in the temp dir (one assistant line per turn).
        Path session = dir.resolve("session.jsonl");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < turns.size(); i++) {
            lines.add(assistantLine("msg_" + (i + 1), turns.get(i)));
        }
        Files.write(session, lines);

        // 2) Production: run the REAL SessionLogParser over the file (each line as rawJson), plus a
        //    final ResultMessage whose usage is the under-counting snapshot (a single point-in-time).
        List<ParsedMessage> messages = new ArrayList<>();
        for (String line : Files.readAllLines(session)) {
            messages.add(ParsedMessage.RegularMessage.of(new AssistantMessage(List.of()), line));
        }
        messages.add(ParsedMessage.RegularMessage.of(ResultMessage.builder()
                .durationMs(1000).durationApiMs(900).numTurns(turns.size()).sessionId("sess").totalCostUsd(0.0)
                .usage(Map.of("input_tokens", 1, "output_tokens", 250,
                        "cache_creation_input_tokens", 0, "cache_read_input_tokens", 40_000))
                .build()));
        PhaseCapture phase = SessionLogParser.parse(messages.iterator(), "run", "explore");
        TokenUsage production = phase.aggregateUsage();

        // 3) Oracle: sum the file's per-turn usage by type independently (plain ObjectMapper).
        TokenUsage oracle = rawPerTurnSum(session);

        // Exact, in tokens — the adapter-boundary identity.
        assertThat(production.inputTokens()).isEqualTo((int) oracle.inputTokens()).isEqualTo(10);
        assertThat(production.outputTokens()).isEqualTo((int) oracle.outputTokens()).isEqualTo(1000);
        assertThat(production.cacheCreationTokens()).isEqualTo((int) oracle.cacheCreationTokens()).isEqualTo(33_000);
        assertThat(production.cacheReadTokens()).isEqualTo((int) oracle.cacheReadTokens()).isEqualTo(118_000);

        // Guard: the snapshot under-counts the cost-driving types (the bug).
        TokenUsage snapshot = phase.snapshotUsage();
        assertThat(snapshot.outputTokens()).isLessThan(production.outputTokens());
        assertThat(snapshot.cacheReadTokens()).isLessThan(production.cacheReadTokens());
    }

    /** Independent oracle — reads the raw file and sums {@code message.usage} by type (not via the parser). */
    private TokenUsage rawPerTurnSum(Path session) throws IOException {
        long in = 0, out = 0, cc = 0, cr = 0;
        for (String line : Files.readAllLines(session)) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode usage = mapper.readTree(line).path("message").path("usage");
            if (!usage.isObject()) {
                continue;
            }
            in += usage.path("input_tokens").asLong();
            out += usage.path("output_tokens").asLong();
            cc += usage.path("cache_creation_input_tokens").asLong();
            cr += usage.path("cache_read_input_tokens").asLong();
        }
        return new TokenUsage((int) in, (int) out, 0, (int) cc, (int) cr, 0);
    }

    private String assistantLine(String id, Turn t) throws IOException {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", t.in());
        usage.put("cache_creation_input_tokens", t.cacheCreate());
        usage.put("cache_read_input_tokens", t.cacheRead());
        usage.put("output_tokens", t.out());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("model", "claude-opus-4-8");
        message.put("usage", usage);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "assistant");
        line.put("message", message);
        return mapper.writeValueAsString(line);
    }
}
