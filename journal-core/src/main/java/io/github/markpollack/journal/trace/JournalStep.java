package io.github.markpollack.journal.trace;

/**
 * One step of an agent's trajectory, with its tokens and its share of the cost: a tool call, or a
 * model turn that made no tool call. Each capture module builds steps from its phase capture with
 * its own {@code *JournalSteps} class, such as Claude Code's {@code JournalSteps}. The run
 * recorders store them as {@link io.github.markpollack.journal.derived.StepCostEvent}s, and the
 * session parsers that write traces add them as {@code step_cost} lines with
 * {@link TraceWriter#writeStepCost(JournalStep)}. Use steps to see where in a trajectory the cost
 * built up.
 *
 * <p>Agents report the cost of a whole call, not of each step, so a step's cost is an allocation,
 * not a measurement. {@link #actualRunCostUsd()} is the reported total that was split, the same on
 * every step it was split across; {@link #attributedCostUsd()} is this step's share; and
 * {@link #attributionMethod()} says how the share was worked out. The shares of the steps built
 * from one phase add up to that phase's total. Despite its name, {@code actualRunCostUsd} is the
 * total of one phase (one agent call), so it differs between the phases of a run. It is 0 when
 * the agent reports no cost.
 *
 * <p>The token counts belong to the step's turn and are not split: when one turn makes several
 * tool calls, each of their steps carries the whole turn's tokens, so do not add up tokens over
 * steps. {@code thinkingTokens} is part of {@code outputTokens}. Steps split with
 * {@link AttributionMethod#EVEN_SPLIT} have zero token counts.
 *
 * <p>This library leaves {@code agentState} {@code null}, for an analysis tool to fill in. The
 * record does not check its values.
 *
 * @param runId the ID of the run the step belongs to, or {@code null} when it was built without
 *        a run
 * @param turnId the ID of the step's model turn (the assistant message ID, such as
 *        {@code msg_...}), or {@code null} if not known
 * @param stepId the step's ID: the vendor's tool-call ID for a tool call, otherwise the turn ID or
 *        an ID made by the capture module
 * @param toolName the tool's name, or {@code null} for a turn without a tool call
 * @param inputTokens the turn's input tokens, without cache reads and writes
 * @param outputTokens the turn's output tokens
 * @param attributedCostUsd this step's share of the cost, in US dollars
 * @param actualRunCostUsd the reported total cost that was split, in US dollars
 * @param attributionMethod how {@code attributedCostUsd} was worked out
 * @param isError whether the step's tool call failed; {@code false} for a turn without a tool call
 * @param agentState a state label for analysis tools; {@code null} from this library
 * @param vendor the agent the step came from, such as {@code "claude-code"}
 * @param isSubagentSpawn whether the tool call started a sub-agent; the sub-agent's own steps are
 *        not included
 * @param thinkingTokens the turn's thinking tokens, which are part of {@code outputTokens}
 * @param cacheCreationTokens the turn's tokens written to the prompt cache
 * @param cacheReadTokens the turn's tokens read from the prompt cache
 * @param turnIndex the 0-based number of the step's turn, or -1 if not known
 * @param durationMs the time from the tool call to its result, in milliseconds, or -1 if not
 *        measured
 */
public record JournalStep(
        String runId,
        String turnId,
        String stepId,
        String toolName,
        long inputTokens,
        long outputTokens,
        double attributedCostUsd,
        double actualRunCostUsd,
        AttributionMethod attributionMethod,
        boolean isError,
        String agentState,
        String vendor,
        boolean isSubagentSpawn,
        long thinkingTokens,
        long cacheCreationTokens,
        long cacheReadTokens,
        int turnIndex,
        long durationMs
) {

    /**
     * Creates a step without thinking and cache tokens, turn number or duration, for code written
     * before 1.9.0. Those tokens are 0, and {@code turnIndex} and {@code durationMs} are -1.
     *
     * @param runId the run ID, or {@code null}
     * @param turnId the turn ID, or {@code null}
     * @param stepId the step ID
     * @param toolName the tool's name, or {@code null}
     * @param inputTokens the turn's input tokens
     * @param outputTokens the turn's output tokens
     * @param attributedCostUsd this step's share of the cost
     * @param actualRunCostUsd the total that was split
     * @param attributionMethod how the share was worked out
     * @param isError whether the tool call failed
     * @param agentState a state label, or {@code null}
     * @param vendor the agent the step came from
     * @param isSubagentSpawn whether the tool call started a sub-agent
     */
    public JournalStep(String runId, String turnId, String stepId, String toolName, long inputTokens,
            long outputTokens, double attributedCostUsd, double actualRunCostUsd,
            AttributionMethod attributionMethod, boolean isError, String agentState, String vendor,
            boolean isSubagentSpawn) {
        this(runId, turnId, stepId, toolName, inputTokens, outputTokens, attributedCostUsd, actualRunCostUsd,
                attributionMethod, isError, agentState, vendor, isSubagentSpawn, 0L, 0L, 0L, -1, -1L);
    }

    /**
     * Returns all input tokens of the step's turn: input plus cache writes plus cache reads.
     *
     * @return the sum of {@code inputTokens}, {@code cacheCreationTokens} and
     *         {@code cacheReadTokens}
     */
    public long totalInputTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens;
    }

    /**
     * Returns whether the step's duration was measured.
     *
     * @return {@code true} if {@code durationMs} is 0 or more
     */
    public boolean hasDuration() {
        return durationMs >= 0;
    }
}
