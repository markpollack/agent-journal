package io.github.markpollack.journal.tck.venn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproductions of false acceptance raised in the 2026-10-19 review of the Venn design
 * ({@code agent-journal-canonical-format-proposal.md}). Each test PINS TODAY'S WRONG ANSWER so that the limitation is
 * visible and the test must be flipped when the engine learns record ownership. They are not endorsements.
 */
class VennKnownLimitationsTest {

    private static Leaf nat(int line, String kind, String path, Object value) {
        return new Leaf(kind, path, value, "native:" + line, false);
    }

    private static Leaf can(int line, String kind, String path, Object value) {
        return new Leaf(kind, path, value, "events:" + line, false);
    }

    @Test
    void aQuotationInAnotherRecordSatisfiesAMissingToolResult() {
        String output = "public class Main { void run() { } }";
        VennReport r = Venn.compare(
                List.of(nat(1, "user", "message.content[].content", output)),
                // the tool result's own copy is missing; an assistant text event quotes the same text
                List.of(can(7, "assistant_text", "text", "As shown above: " + output + " is the class.")));

        // WRONG: the tool-result occurrence is reported as landed because an unrelated record contains the same text.
        assertThat(r.path("user", "message.content[].content").orElseThrow().landed()).isEqualTo(1);
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void aValueMovedToTheWrongToolCallStillLands() {
        VennReport r = Venn.compare(
                List.of(nat(1, "user", "tool_use_id", "toolu_AAAAAAAAAA"), nat(1, "user", "content", "result text of the first call"),
                        nat(2, "user", "tool_use_id", "toolu_BBBBBBBBBB"), nat(2, "user", "content", "result text of the second call")),
                // the two outputs are swapped between the two tool calls
                List.of(can(5, "tool_call", "id", "toolu_AAAAAAAAAA"), can(5, "tool_call", "output", "result text of the second call"),
                        can(6, "tool_call", "id", "toolu_BBBBBBBBBB"), can(6, "tool_call", "output", "result text of the first call")));

        // WRONG: both results are reported as landed although each sits on the other call.
        assertThat(r.path("user", "content").orElseThrow().landed()).isEqualTo(2);
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void anAggregateHidesThatTheComponentsAreGone() {
        VennReport r = Venn.compare(
                List.of(nat(1, "assistant", "usage.input_tokens", new java.math.BigDecimal("120")),
                        nat(2, "assistant", "usage.input_tokens", new java.math.BigDecimal("300"))),
                List.of(can(9, "llm_call", "tokenUsage.inputTokens", new java.math.BigDecimal("420"))));

        // CORRECT AS FAR AS IT GOES but incomplete: the sum 420 is not any native value, so both components read as lost,
        // and nothing says the total is correct. Needs a declared sum rule (contract), not value matching.
        assertThat(r.path("assistant", "usage.input_tokens").orElseThrow().neither()).isEqualTo(2);
        assertThat(r.unexplained()).containsKey("llm_call tokenUsage.inputTokens");
    }
}
