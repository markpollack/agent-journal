package io.github.markpollack.journal.trace;

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
 * Token fields are the <em>turn's</em> full five-field vector (not split): {@code input},
 * {@code output}, {@code thinking}, {@code cacheCreation}, {@code cacheRead}. For the common
 * one-tool-per-turn case they are exact; for a multi-tool turn each step carries its turn's
 * shared tokens (documented — tokens are a turn property, cost is the thing that is split).
 * {@code thinkingTokens} is a <em>subset of</em> {@code outputTokens} (the provider bills
 * thinking inside output), so it is never added to a billed total; it is carried because the
 * cache and thinking components are what make a per-state cost priceable at all.
 *
 * <p>
 * {@code turnIndex} and {@code durationMs} restore the dwell-time half of the semi-Markov
 * question: without a turn ordinal a step cannot be placed in the trajectory, and without a
 * duration a state has no holding time. Both were carried by the v1 capture and lost by v3/v4.
 *
 * <p>
 * {@code agentState} is an unfilled slot — the Markov state classifier in
 * {@code agent-control-theory} populates it; journal never classifies. {@code raw} is not
 * duplicated here: the verbatim wire lives on the per-message {@code type:"raw"} trace line
 * (R2.1), joined by {@code turnId}. Vendor-neutral and owned by {@code journal-core} (R2.7);
 * per-vendor extractors project into it (Claude via {@code JournalSteps}; Gemini/Codex later).
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
 * @param thinkingTokens    the turn's extended-thinking tokens — a documented <em>subset</em> of
 *                          {@code outputTokens}, never additive to a billed total. 0 when the
 *                          provider reports none.
 * @param cacheCreationTokens the turn's tokens written to the prompt cache
 * @param cacheReadTokens   the turn's tokens read from the prompt cache
 * @param turnIndex         0-based ordinal of this step's turn within the capture, or -1 when
 *                          unknown (no per-turn usage available). Orders the trajectory.
 * @param durationMs        observed duration of this step in milliseconds, or -1 when unknown.
 *                          For a tool step this is the interval between the tool call being
 *                          issued and its result arriving; see {@code ToolResultRecord.durationMs}
 *                          for the measurement caveat.
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
     * Back-compat constructor for callers written before the full per-turn token vector,
     * turn ordinal and step duration were carried (1.9.0). The added fields default to
     * "not captured": zero tokens, {@code turnIndex = -1}, {@code durationMs = -1}.
     */
    public JournalStep(String runId, String turnId, String stepId, String toolName, long inputTokens,
            long outputTokens, double attributedCostUsd, double actualRunCostUsd,
            AttributionMethod attributionMethod, boolean isError, String agentState, String vendor,
            boolean isSubagentSpawn) {
        this(runId, turnId, stepId, toolName, inputTokens, outputTokens, attributedCostUsd, actualRunCostUsd,
                attributionMethod, isError, agentState, vendor, isSubagentSpawn, 0L, 0L, 0L, -1, -1L);
    }

    /**
     * The turn's total input including prompt-cache reads and cache creation — the input side
     * of what was actually billed, as opposed to the non-cached {@link #inputTokens()} alone.
     */
    public long totalInputTokens() {
        return inputTokens + cacheCreationTokens + cacheReadTokens;
    }

    /** Whether a step duration was actually observed (as opposed to never captured). */
    public boolean hasDuration() {
        return durationMs >= 0;
    }
}
