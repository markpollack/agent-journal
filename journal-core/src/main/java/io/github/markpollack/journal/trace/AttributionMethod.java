package io.github.markpollack.journal.trace;

/**
 * How a step's share of the cost ({@link JournalStep#attributedCostUsd()}) was worked out from the
 * total that the agent reported ({@link JournalStep#actualRunCostUsd()}). Agents report the cost
 * of a whole call, not of each step, so every per-step cost is an allocation, and each
 * {@link JournalStep} records which one was used. Read it to tell a precise split from a coarse
 * one: an analysis may want to leave out or down-weight {@link #EVEN_SPLIT} steps.
 *
 * <p>The capture modules choose the method, depending on what each agent reports; each module's
 * {@code *JournalSteps} class says which one it uses.
 */
public enum AttributionMethod {

    /**
     * Each turn gets a share of the total in proportion to its output tokens, and a turn with
     * several tool calls divides its share evenly among them. The shares add up to the total.
     * This needs the usage of each turn.
     */
    OUTPUT_TOKEN_PROPORTIONAL,

    /**
     * The total is divided equally among the tool calls, whatever their tokens. It is used when
     * there is no per-turn usage to weight the split by, so it is coarser than
     * {@link #OUTPUT_TOKEN_PROPORTIONAL}. Only tool calls get steps.
     */
    EVEN_SPLIT,

    /**
     * Reserved for a cost computed from per-model token prices. Nothing in this library produces
     * it.
     */
    PRICING_TABLE

}
