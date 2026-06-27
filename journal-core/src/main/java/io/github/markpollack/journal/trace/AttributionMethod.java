package io.github.markpollack.journal.trace;

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
     *
     * <p>
     * The within-turn even split among a turn's parallel tool calls is <em>part of</em> this
     * method (the turn's proportional share is divided among its tools) — it is <strong>not</strong>
     * the coarse {@link #EVEN_SPLIT} fallback.
     */
    OUTPUT_TOKEN_PROPORTIONAL,

    /**
     * Coarse fallback used <strong>only</strong> when per-turn token usage is absent (SDK
     * &lt; 1.3.0 / programmatic capture / no {@code turns[]}), so the run cost cannot be split
     * by output tokens at all and is divided <em>flatly</em> across the steps. This is a
     * distinct value (A1, 2026-06-27) so a consumer can tell a precise split from a coarse one
     * <em>from the data alone</em> — the capture-time WARN is gone by the time the ETL reads the
     * journal, so this label is the durable signal. Consumers (e.g. agent-control-theory) may
     * filter or down-weight runs whose steps carry {@code EVEN_SPLIT}.
     */
    EVEN_SPLIT,

    /**
     * Reserved (not implemented): reconstruct each step's cost from published per-model
     * token rates, normalized to the native total. Needed for vendors with no native cost
     * (Gemini/Codex); for Claude Code the native total makes {@link #OUTPUT_TOKEN_PROPORTIONAL}
     * preferable (no pricing table to maintain).
     */
    PRICING_TABLE

}
