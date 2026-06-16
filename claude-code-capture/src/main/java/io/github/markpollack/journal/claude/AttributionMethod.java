package io.github.markpollack.journal.claude;

/**
 * How a per-step {@code attributedCostUsd} was derived from the run's actual cost.
 *
 * <p>
 * Claude Code reports cost only as a run total ({@code total_cost_usd}) plus a per-model
 * decomposition — never per step. So per-step cost is <strong>attributed</strong> (a
 * fair-share split), not measured. The total stays ground truth; the per-step figure is an
 * allocation. This enum records which allocation was used so the two are never confused and
 * the result is auditable forever (see {@link JournalStep#actualRunCostUsd()}).
 */
public enum AttributionMethod {

    /**
     * The run's actual cost is split across steps in proportion to each step's output
     * tokens — the dominant cost driver — and shared evenly among parallel tool calls in a
     * turn. The per-step costs sum to the run total. The control-theory reading: "where is
     * cost accumulating in the trajectory", not "what did this step literally cost".
     */
    OUTPUT_TOKEN_PROPORTIONAL,

    /**
     * Reserved (not implemented): reconstruct each step's cost from published per-model
     * token rates, normalized to the native total. Needed for vendors with no native cost
     * (Gemini/Codex); for Claude Code the native total makes {@link #OUTPUT_TOKEN_PROPORTIONAL}
     * preferable (no pricing table to maintain).
     */
    PRICING_TABLE

}
