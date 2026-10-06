package io.github.markpollack.journal.claude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The session-level running totals that Claude Code reported on a result message, kept beside a
 * {@link PhaseCapture} whose own cost figures have been rebased to this query alone.
 *
 * <p>Claude Code's result line reports {@code total_cost_usd}, {@code modelUsage} and
 * {@code duration_api_ms} as the <em>session's</em> running totals: when several prompts are sent
 * into one session (and across {@code --resume}, which carries the totals forward under the same
 * session ID), the second result line's cost includes the first. The {@code usage} block,
 * {@code duration_ms} and {@code num_turns} cover the query alone. A {@link PhaseCapture} produced
 * by {@link SessionLogParser#parse} carries the result line's figures verbatim, basis
 * {@link CostBasis#RESULT_LINE}; {@link SessionLogParser#withSessionBaseline} subtracts the previous
 * capture of the same session and records the totals it subtracted from here, basis
 * {@link CostBasis#SESSION_DELTA}.
 *
 * @param cumulativeCostUsd the session's running total cost on this result line, in US dollars
 * @param cumulativeApiDurationMs the session's running total API time on this result line
 * @param cumulativeModelCosts the session's running per-model totals on this result line, or an
 * empty list when the result line had no {@code modelUsage}
 * @param baselineCostUsd the previous capture's cumulative cost that was subtracted
 * @since 1.11.0
 */
public record SessionCost(
        double cumulativeCostUsd,
        long cumulativeApiDurationMs,
        List<ModelCost> cumulativeModelCosts,
        double baselineCostUsd
) {

    /** How {@link PhaseCapture#totalCostUsd()} relates to the result line it was read from. */
    public enum CostBasis {
        /** The result line's {@code total_cost_usd} verbatim: the session's running total. */
        RESULT_LINE,
        /** The result line's running total minus the previous capture's, in the same session. */
        SESSION_DELTA
    }

    /** Copies the list so later changes to it do not reach this record. */
    public SessionCost {
        cumulativeModelCosts = cumulativeModelCosts == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(cumulativeModelCosts));
    }

    /** The metadata key under which the basis is reported, value {@code session_delta} or {@code result_line}. */
    public static final String COST_BASIS_KEY = "costBasis";

    /** The metadata key under which the session's running total cost is reported. */
    public static final String SESSION_CUMULATIVE_COST_KEY = "sessionCumulativeCostUsd";
}
