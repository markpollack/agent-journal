package io.github.markpollack.journal.claude;

/**
 * Portable per-step record — the unit the ACT analysis layer consumes for state-weighted
 * cost-to-go. One step is either a single tool call or a tool-less assistant turn.
 *
 * <p>
 * <strong>Cost fields are kept deliberately separate so ground truth and allocation are
 * never confused</strong> (per the R2.3 decision):
 * <ul>
 *   <li>{@code actualRunCostUsd} — the run's true cost ({@code total_cost_usd}); identical
 *       across every step of a run.</li>
 *   <li>{@code attributedCostUsd} — this step's fair share of the run cost under
 *       {@code attributionMethod}; the per-step costs sum to {@code actualRunCostUsd}.</li>
 *   <li>{@code attributionMethod} — how the split was computed.</li>
 * </ul>
 * Nobody should mistake an attributed share for a measured cost — Claude Code does not
 * report per-step cost; the share is an allocation, the total is real.
 *
 * <p>
 * Token fields are the <em>turn's</em> token vector (not split): for the common
 * one-tool-per-turn case they are exact; for a multi-tool turn each step carries its turn's
 * shared tokens (documented — tokens are a turn property, cost is the thing that is split).
 *
 * <p>
 * {@code agentState} is an unfilled slot — the Markov state classifier in
 * {@code agent-control-theory} populates it; journal never classifies. {@code raw} is not
 * duplicated here: the verbatim wire lives on the per-message {@code type:"raw"} trace line
 * (R2.1), joined by {@code turnId}. Lives in {@code claude-code-capture} for now; moves to
 * {@code journal-core} as the shared portable writer in R2.7.
 *
 * @param runId             the run this step belongs to
 * @param turnId            the assistant message id (e.g. {@code msg_…}); joins to the raw line
 * @param stepId            stable step identity: the tool_use id ({@code toolu_…}) for tool
 *                          steps, the turn id for tool-less turns
 * @param toolName          the tool invoked, or null for a tool-less turn step
 * @param inputTokens       the turn's non-cached input tokens
 * @param outputTokens      the turn's output tokens
 * @param attributedCostUsd this step's fair share of the run cost
 * @param actualRunCostUsd  the run's true total cost (ground truth, repeated per step)
 * @param attributionMethod how {@code attributedCostUsd} was derived
 * @param isError           whether this step errored (tool_result isError; false for turn steps)
 * @param agentState        Markov-state slot, filled by the analysis layer (null here)
 * @param vendor            capture vendor, e.g. {@code claude-code}
 * @param isSubagentSpawn   whether this step spawned a sub-agent (a {@code Task}/{@code Agent}
 *                          tool call). The sub-agent's interior steps are NOT in the SDK stream
 *                          — they live in {@code subagents/*.jsonl} and are captured by archival
 *                          (R2.5b, agent-client). This flag marks the boundary so a spawn is never
 *                          flattened into an ordinary tool call.
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
        boolean isSubagentSpawn
) {
}
