package io.github.markpollack.journal.claude;

import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.claude.agent.sdk.types.AssistantMessage;
import io.github.markpollack.claude.agent.sdk.types.ResultMessage;
import io.github.markpollack.claude.agent.sdk.types.TextBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Claude Code reports {@code total_cost_usd}, {@code modelUsage} and {@code duration_api_ms} on
 * each result line as the session's running totals, while {@code usage}, {@code duration_ms} and
 * {@code num_turns} cover the query alone (observed live on Claude Code 2.1.292, bidirectional
 * mode, two queries in one session; also across {@code --resume}). These tests use synthetic
 * figures with the same shape: query 1 costs 0.0400, query 2 costs 0.0100, so the session total
 * after query 2 is 0.0500.
 */
class SessionCumulativeCostTest {

    private static final String SESSION = "sess-A";

    // Query 1: per-query usage in=2 out=10 cc=12000 cr=10000; running totals equal the query's own.
    private static final String RESULT_1 = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":1500,"duration_api_ms":1400,
             "num_turns":1,"session_id":"sess-A","total_cost_usd":0.0400,
             "usage":{"input_tokens":2,"output_tokens":10,"cache_creation_input_tokens":12000,"cache_read_input_tokens":10000},
             "modelUsage":{"sonnet-x":{"inputTokens":2,"outputTokens":10,"cacheReadInputTokens":10000,
                                       "cacheCreationInputTokens":12000,"costUSD":0.0400}}}
            """;

    // Query 2: per-query usage in=2 out=6 cc=2000 cr=22000; running totals = query 1 + query 2.
    private static final String RESULT_2 = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":1600,"duration_api_ms":2900,
             "num_turns":1,"session_id":"sess-A","total_cost_usd":0.0500,
             "usage":{"input_tokens":2,"output_tokens":6,"cache_creation_input_tokens":2000,"cache_read_input_tokens":22000},
             "modelUsage":{"sonnet-x":{"inputTokens":4,"outputTokens":16,"cacheReadInputTokens":32000,
                                       "cacheCreationInputTokens":14000,"costUSD":0.0500}}}
            """;

    // Query 3: a second model does the work (0.0060); sonnet-x's running total does not move.
    private static final String RESULT_3 = """
            {"type":"result","subtype":"success","is_error":false,"duration_ms":900,"duration_api_ms":3700,
             "num_turns":1,"session_id":"sess-A","total_cost_usd":0.0560,
             "usage":{"input_tokens":3,"output_tokens":4,"cache_creation_input_tokens":500,"cache_read_input_tokens":34000},
             "modelUsage":{"sonnet-x":{"inputTokens":4,"outputTokens":16,"cacheReadInputTokens":32000,
                                       "cacheCreationInputTokens":14000,"costUSD":0.0500},
                           "haiku-y":{"inputTokens":3,"outputTokens":4,"cacheReadInputTokens":34000,
                                      "cacheCreationInputTokens":500,"costUSD":0.0060}}}
            """;

    private static PhaseCapture parseQuery(String phase, String rawResult, double cost, long durationMs,
            long apiMs, Map<String, Object> usage) {
        List<ParsedMessage> messages = List.of(
                ParsedMessage.RegularMessage.of(new AssistantMessage(List.of(new TextBlock("ok"))),
                        "{\"type\":\"assistant\",\"session_id\":\"sess-A\",\"message\":{\"id\":\"m-" + phase
                                + "\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],"
                                + "\"usage\":" + usageJson(usage) + "}}"),
                ParsedMessage.RegularMessage.of(ResultMessage.builder()
                        .subtype("success").durationMs((int) durationMs).durationApiMs((int) apiMs).numTurns(1)
                        .sessionId(SESSION).totalCostUsd(cost).usage(usage).build(), rawResult));
        return SessionLogParser.parse(messages.iterator(), phase, "prompt-" + phase);
    }

    private static String usageJson(Map<String, Object> usage) {
        return "{\"input_tokens\":" + usage.get("input_tokens") + ",\"output_tokens\":" + usage.get("output_tokens")
                + ",\"cache_creation_input_tokens\":" + usage.get("cache_creation_input_tokens")
                + ",\"cache_read_input_tokens\":" + usage.get("cache_read_input_tokens") + "}";
    }

    private static PhaseCapture query1() {
        return parseQuery("q1", RESULT_1, 0.0400, 1500, 1400, Map.of("input_tokens", 2, "output_tokens", 10,
                "cache_creation_input_tokens", 12000, "cache_read_input_tokens", 10000));
    }

    private static PhaseCapture query2() {
        return parseQuery("q2", RESULT_2, 0.0500, 1600, 2900, Map.of("input_tokens", 2, "output_tokens", 6,
                "cache_creation_input_tokens", 2000, "cache_read_input_tokens", 22000));
    }

    private static PhaseCapture query3() {
        return parseQuery("q3", RESULT_3, 0.0560, 900, 3700, Map.of("input_tokens", 3, "output_tokens", 4,
                "cache_creation_input_tokens", 500, "cache_read_input_tokens", 34000));
    }

    @Test
    @DisplayName("parse alone: the second query's capture carries the session's running total, and summing phases over-counts")
    void parseAloneCarriesTheRunningTotal() {
        PhaseCapture p1 = query1();
        PhaseCapture p2 = query2();

        // The result line's figures, verbatim: this is what every record written so far carries.
        assertThat(p2.totalCostUsd()).isEqualTo(0.0500);
        assertThat(p2.modelCostSum()).isCloseTo(0.0500, within(1e-9));
        assertThat(p2.modelCosts()).singleElement().satisfies(m -> {
            assertThat(m.outputTokens()).isEqualTo(16); // 10 + 6: cumulative
            assertThat(m.cacheReadInputTokens()).isEqualTo(32000); // 10000 + 22000: cumulative
        });
        assertThat(p2.apiDurationMs()).isEqualTo(2900); // 1400 + 1500: cumulative
        assertThat(p2.costBasis()).isEqualTo(SessionCost.CostBasis.RESULT_LINE);
        assertThat(p2.sessionCost()).isNull();

        // The usage block is the query's own, so token figures are already per query.
        assertThat(p2.outputTokens()).isEqualTo(6);
        assertThat(p2.cacheReadInputTokens()).isEqualTo(22000);
        assertThat(p2.aggregateUsage().outputTokens()).isEqualTo(6);
        assertThat(p2.durationMs()).isEqualTo(1600);
        assertThat(p2.numTurns()).isEqualTo(1);

        // A consumer summing phases records 0.0900 for a session that cost 0.0500: over by query 1.
        double summed = p1.totalCostUsd() + p2.totalCostUsd();
        assertThat(summed).isCloseTo(0.0900, within(1e-9));
        assertThat(summed - 0.0500).isCloseTo(p1.totalCostUsd(), within(1e-9));
    }

    @Test
    @DisplayName("withSessionBaseline: the second query's cost, per-model costs and API time become this query's own")
    void withSessionBaselineRebasesOntoTheQuery() {
        PhaseCapture p1 = query1();
        PhaseCapture p2 = SessionLogParser.withSessionBaseline(query2(), p1);

        assertThat(p2.totalCostUsd()).isCloseTo(0.0100, within(1e-9));
        assertThat(p2.apiDurationMs()).isEqualTo(1500);
        assertThat(p2.modelCosts()).singleElement().satisfies(m -> {
            assertThat(m.model()).isEqualTo("sonnet-x");
            assertThat(m.inputTokens()).isEqualTo(2);
            assertThat(m.outputTokens()).isEqualTo(6);
            assertThat(m.cacheReadInputTokens()).isEqualTo(22000);
            assertThat(m.cacheCreationInputTokens()).isEqualTo(2000);
            assertThat(m.costUsd()).isCloseTo(0.0100, within(1e-9));
        });
        assertThat(p2.reconcilesToModelCosts()).isTrue();

        // The running totals are kept, not lost.
        assertThat(p2.costBasis()).isEqualTo(SessionCost.CostBasis.SESSION_DELTA);
        assertThat(p2.sessionCost().cumulativeCostUsd()).isEqualTo(0.0500);
        assertThat(p2.sessionCost().baselineCostUsd()).isEqualTo(0.0400);
        assertThat(p2.sessionCost().cumulativeApiDurationMs()).isEqualTo(2900);
        assertThat(p2.sessionCost().cumulativeModelCosts()).singleElement()
                .extracting(ModelCost::costUsd).isEqualTo(0.0500);

        // Per-query figures are untouched.
        assertThat(p2.outputTokens()).isEqualTo(6);
        assertThat(p2.cacheReadInputTokens()).isEqualTo(22000);
        assertThat(p2.durationMs()).isEqualTo(1600);
        assertThat(p2.numTurns()).isEqualTo(1);
        assertThat(p2.textOutput()).isEqualTo("ok");
        assertThat(p2.phaseName()).isEqualTo("q2");
        assertThat(p2.sessionId()).isEqualTo(SESSION);

        // Summing the rebased phases gives the true session total.
        assertThat(p1.totalCostUsd() + p2.totalCostUsd()).isCloseTo(0.0500, within(1e-9));
    }

    @Test
    @DisplayName("a chain rebases each query on the one before, using the previous running total; an unused model is dropped")
    void chainUsesThePreviousRunningTotal() {
        PhaseCapture p1 = query1();
        PhaseCapture p2 = SessionLogParser.withSessionBaseline(query2(), p1);
        PhaseCapture p3 = SessionLogParser.withSessionBaseline(query3(), p2);

        assertThat(p3.totalCostUsd()).isCloseTo(0.0060, within(1e-9));
        assertThat(p3.apiDurationMs()).isEqualTo(800);
        assertThat(p3.sessionCost().baselineCostUsd()).isEqualTo(0.0500);
        assertThat(p3.sessionCost().cumulativeCostUsd()).isEqualTo(0.0560);
        assertThat(p3.modelCosts()).singleElement().satisfies(m -> {
            assertThat(m.model()).isEqualTo("haiku-y");
            assertThat(m.costUsd()).isCloseTo(0.0060, within(1e-9));
            assertThat(m.outputTokens()).isEqualTo(4);
        });
        assertThat(p3.reconcilesToModelCosts()).isTrue();
        assertThat(p1.totalCostUsd() + p2.totalCostUsd() + p3.totalCostUsd()).isCloseTo(0.0560, within(1e-9));
    }

    @Test
    @DisplayName("no baseline applies without a previous capture, across sessions, or after the counters restart")
    void baselineGuards() {
        PhaseCapture p2 = query2();
        assertThat(SessionLogParser.withSessionBaseline(p2, null)).isSameAs(p2);

        PhaseCapture otherSession = new PhaseCapture("other", "p", 1, 1, 0, 0, 0, 10, 10, 0.0300, "sess-B", 1, false,
                "", List.of(), List.of(), null, List.of(), List.of(), List.of(), null, -1);
        assertThat(SessionLogParser.withSessionBaseline(p2, otherSession)).isSameAs(p2);

        PhaseCapture noSession = new PhaseCapture("none", "p", 1, 1, 0, 0, 0, 10, 10, 0.0300, null, 1, false,
                "", List.of(), List.of(), null, List.of(), List.of(), List.of(), null, -1);
        assertThat(SessionLogParser.withSessionBaseline(p2, noSession)).isSameAs(p2);

        // Counters restarted: the result line is below the previous running total, so it already covers this query.
        PhaseCapture restarted = SessionLogParser.withSessionBaseline(query1(), p2);
        assertThat(restarted.totalCostUsd()).isEqualTo(0.0400);
        assertThat(restarted.costBasis()).isEqualTo(SessionCost.CostBasis.RESULT_LINE);
    }

    @Test
    @DisplayName("single-query behaviour is unchanged: parse yields the result line's figures with no session cost")
    void singleQueryUnchanged() {
        PhaseCapture p1 = query1();
        assertThat(p1.totalCostUsd()).isEqualTo(0.0400);
        assertThat(p1.apiDurationMs()).isEqualTo(1400);
        assertThat(p1.sessionCost()).isNull();
        assertThat(p1.costBasis()).isEqualTo(SessionCost.CostBasis.RESULT_LINE);
        // Rebasing the first query on itself-as-previous is a no-op in value: delta is zero only
        // because the running total equals its own; callers pass null for the first query.
        assertThat(p1.modelCostSum()).isCloseTo(p1.totalCostUsd(), within(1e-9));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a rebased capture is recorded with its per-query cost, its basis and the session's running total")
    void rebasedCaptureIsRecordedWithItsBasis(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        io.github.markpollack.journal.Journal.configure(new io.github.markpollack.journal.storage.JsonFileStorage(dir));
        PhaseCapture p1 = query1();
        PhaseCapture p2 = SessionLogParser.withSessionBaseline(query2(), p1);
        io.github.markpollack.journal.Run run = io.github.markpollack.journal.Journal.run("exp").start();
        try (RunRecorder recorder = new RunRecorder(run)) {
            recorder.recordPhase(p1);
            recorder.recordPhase(p2);
            recorder.finish();
        }
        io.github.markpollack.journal.Journal.reset();

        java.util.List<io.github.markpollack.journal.event.LLMCallEvent> calls =
                new io.github.markpollack.journal.storage.JsonFileStorage(dir).loadEvents("exp", run.id()).stream()
                        .filter(io.github.markpollack.journal.event.LLMCallEvent.class::isInstance)
                        .map(io.github.markpollack.journal.event.LLMCallEvent.class::cast).toList();
        org.assertj.core.api.Assertions.assertThat(calls).hasSize(2);
        // The first prompt of a session is the result line's own figure and carries no basis key.
        org.assertj.core.api.Assertions.assertThat(calls.get(0).totalCostUsd()).isEqualTo(p1.totalCostUsd());
        org.assertj.core.api.Assertions.assertThat(calls.get(0).metadata()).doesNotContainKeys(
                SessionCost.COST_BASIS_KEY, SessionCost.SESSION_CUMULATIVE_COST_KEY);
        // The second is the query's own cost, labelled, with the running total kept.
        org.assertj.core.api.Assertions.assertThat(calls.get(1).totalCostUsd()).isEqualTo(p2.totalCostUsd());
        org.assertj.core.api.Assertions.assertThat(calls.get(1).metadata())
                .containsEntry(SessionCost.COST_BASIS_KEY, "session_delta")
                .containsEntry(SessionCost.SESSION_CUMULATIVE_COST_KEY, p2.sessionCost().cumulativeCostUsd());
        org.assertj.core.api.Assertions.assertThat(calls.get(0).totalCostUsd() + calls.get(1).totalCostUsd())
                .isEqualTo(p2.sessionCost().cumulativeCostUsd());
    }
}
