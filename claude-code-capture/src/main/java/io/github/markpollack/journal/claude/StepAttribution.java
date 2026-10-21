package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single attribution core shared by capture-time and offline derivation ({@link JournalSteps}). One instance
 * holds what every step of one phase shares: the run, the vendor, the phase's reported total, its tool calls by id
 * and the error flag and observed duration of each tool result. Given the per-turn output tokens (the split
 * weights) and the per-turn tool grouping, it produces the per-step shares and folds the float residual into the
 * last step.
 */
final class StepAttribution {

    private final String runId;
    private final String vendor;
    private final double actualCost;
    private final List<ToolUseRecord> toolUses;
    private final Map<String, String> toolNames = new LinkedHashMap<>();
    private final Map<String, Boolean> toolErrors;
    private final Map<String, Long> toolDurations;

    /**
     * @param toolUses the phase's tool calls, or {@code null} for none
     * @param toolErrors the error flag of each tool result, by tool-use id
     * @param toolDurations the observed duration of each tool result, by tool-use id, or {@code null} for none
     * @param actualCost the phase's reported total
     * @param runId the run ID to put on each step, or {@code null}
     * @param vendor the vendor name to put on each step
     */
    StepAttribution(List<ToolUseRecord> toolUses, Map<String, Boolean> toolErrors, Map<String, Long> toolDurations,
            double actualCost, String runId, String vendor) {
        this.toolUses = toolUses != null ? toolUses : List.of();
        for (ToolUseRecord tu : this.toolUses) {
            toolNames.put(tu.id(), tu.name());
        }
        this.toolErrors = toolErrors;
        this.toolDurations = toolDurations;
        this.actualCost = actualCost;
        this.runId = runId;
        this.vendor = vendor;
    }

    /**
     * Splits the total across the steps: by output tokens over the turns when per-turn usage is there, else evenly
     * over the tool calls. The shares add up to the total exactly.
     */
    List<JournalStep> attribute(List<TurnUsage> turns) {
        List<JournalStep> steps = turns != null && !turns.isEmpty() ? proportionalSteps(turns) : evenSplitSteps();
        return foldResidualIntoLast(steps);
    }

    /**
     * Precise: split the run total across turns by output tokens; the within-turn even split among parallel tools
     * is part of this proportional method (A1), OUTPUT_TOKEN_PROPORTIONAL. That only holds when every turn's
     * output is a measured value. When any is not reported (-1: the stream's figure is a placeholder), weighting
     * by it would be noise, so the split is a flat even split across steps and is labelled EVEN_SPLIT.
     */
    private List<JournalStep> proportionalSteps(List<TurnUsage> turns) {
        final boolean outputKnown = turns.stream().allMatch(t -> t.outputTokens() >= 0);
        final AttributionMethod method = outputKnown ? AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL
                : AttributionMethod.EVEN_SPLIT;
        JournalSteps.warnUnlistedTools(turns, toolUses);
        long totalOutput = outputKnown ? turns.stream().mapToLong(TurnUsage::outputTokens).sum() : 0;
        int totalSlots = 0;
        for (TurnUsage turn : turns) {
            totalSlots += slotsOf(turn);
        }
        List<JournalStep> steps = new ArrayList<>();
        for (TurnUsage turn : turns) {
            double weight = weightOf(turn, outputKnown, totalOutput, totalSlots, turns.size());
            steps.addAll(turnSteps(turn, actualCost * weight, method));
        }
        return steps;
    }

    /** The steps a turn takes: one per tool call, with the turn's cost divided evenly among them. */
    private static int slotsOf(TurnUsage turn) {
        return Math.max(1, turn.toolUseIds() == null ? 0 : turn.toolUseIds().size());
    }

    /** The turn's share of the total: by its steps when any output is unmeasured, else by its output tokens. */
    private static double weightOf(TurnUsage turn, boolean outputKnown, long totalOutput, int totalSlots,
            int turnCount) {
        if (!outputKnown) {
            return (double) slotsOf(turn) / totalSlots;
        }
        return totalOutput > 0 ? (double) turn.outputTokens() / totalOutput : 1.0 / turnCount;
    }

    /**
     * The steps of one turn: a tool-less turn (e.g. final text answer) is one turn-level step, which has no tool
     * result and so no observed duration (-1, not 0); otherwise one step per tool call with an even share of the
     * turn's cost.
     */
    private List<JournalStep> turnSteps(TurnUsage turn, double turnCost, AttributionMethod method) {
        List<JournalStep> steps = new ArrayList<>();
        List<String> tools = turn.toolUseIds();
        if (tools == null || tools.isEmpty()) {
            steps.add(new JournalStep(runId, turn.messageId(), turn.messageId(), null,
                    turn.inputTokens(), turn.outputTokens(), turnCost, actualCost, method, false, null,
                    vendor, false, turn.thinkingTokens(), turn.cacheCreationInputTokens(),
                    turn.cacheReadInputTokens(), turn.turnIndex(), -1L));
            return steps;
        }
        double perTool = turnCost / tools.size();
        for (String toolId : tools) {
            String toolName = toolNames.get(toolId);
            steps.add(new JournalStep(runId, turn.messageId(), toolId, toolName,
                    turn.inputTokens(), turn.outputTokens(), perTool, actualCost, method,
                    Boolean.TRUE.equals(toolErrors.get(toolId)), null, vendor,
                    JournalSteps.isSubagentTool(toolName), turn.thinkingTokens(),
                    turn.cacheCreationInputTokens(), turn.cacheReadInputTokens(), turn.turnIndex(),
                    durationOf(toolId)));
        }
        return steps;
    }

    /**
     * Coarse fallback: no per-turn usage (rawJson absent / SDK &lt; 1.3.0 / programmatic), so the whole allocation
     * degrades to a flat even split across tool calls. Stamps the distinct EVEN_SPLIT (A1) so the coarseness is
     * readable from the data after the capture-time WARN is gone; turnId and per-turn tokens are unavailable.
     */
    private List<JournalStep> evenSplitSteps() {
        final AttributionMethod method = AttributionMethod.EVEN_SPLIT;
        List<JournalStep> steps = new ArrayList<>();
        if (toolUses.isEmpty()) {
            return steps;
        }
        JournalSteps.warnCoarsenedOnce();
        double perTool = actualCost / toolUses.size();
        for (ToolUseRecord tu : toolUses) {
            // No per-turn usage means no token vector to carry, but the tool's own turn
            // linkage and observed duration survive independently of the cost split.
            steps.add(new JournalStep(runId, tu.turnId(), tu.id(), tu.name(), 0, 0, perTool, actualCost,
                    method, Boolean.TRUE.equals(toolErrors.get(tu.id())), null, vendor,
                    JournalSteps.isSubagentTool(tu.name()), 0L, 0L, 0L, tu.turnIndex(),
                    durationOf(tu.id())));
        }
        return steps;
    }

    /**
     * One zero-cost step per tool call, marked {@link AttributionMethod#EVEN_SPLIT}, each carrying the tokens of
     * the turn that issued the call when that turn is known. Used both when recording a sub-agent and when
     * deriving steps again from a run whose LLM call has {@code costAvailable=false}, so the two give the same
     * steps. The total of this instance is not used: Claude Code reports no cost for a sub-agent.
     */
    List<JournalStep> withoutReportedCost(List<TurnUsage> turns) {
        Map<String, TurnUsage> turnByToolUseId = new LinkedHashMap<>();
        for (TurnUsage turn : JournalSteps.nullSafe(turns)) {
            for (String toolUseId : JournalSteps.nullSafe(turn.toolUseIds())) {
                turnByToolUseId.put(toolUseId, turn);
            }
        }
        List<JournalStep> steps = new ArrayList<>();
        for (ToolUseRecord use : toolUses) {
            steps.add(zeroCostStep(use, turnByToolUseId.get(use.id())));
        }
        return steps;
    }

    /** A zero-cost step for one tool call, with the tokens of its turn when that turn is known. */
    private JournalStep zeroCostStep(ToolUseRecord use, TurnUsage turn) {
        return new JournalStep(runId, use.turnId(), use.id(), use.name(),
                turn != null ? turn.inputTokens() : 0, turn != null ? turn.outputTokens() : 0, 0.0, 0.0,
                AttributionMethod.EVEN_SPLIT, Boolean.TRUE.equals(toolErrors.get(use.id())), null,
                vendor, JournalSteps.isSubagentTool(use.name()),
                turn != null ? turn.thinkingTokens() : 0, turn != null ? turn.cacheCreationInputTokens() : 0,
                turn != null ? turn.cacheReadInputTokens() : 0, use.turnIndex(),
                durationOf(use.id()));
    }

    /**
     * Keeps the per-step sum exactly equal to the run total by adding the float residual to
     * the last step, so "the total is true" survives the proportional split.
     */
    private List<JournalStep> foldResidualIntoLast(List<JournalStep> steps) {
        if (steps.isEmpty()) {
            return steps;
        }
        double sum = steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum();
        double residual = actualCost - sum;
        if (residual != 0.0) {
            int i = steps.size() - 1;
            JournalStep last = steps.get(i);
            steps.set(i, new JournalStep(last.runId(), last.turnId(), last.stepId(), last.toolName(),
                    last.inputTokens(), last.outputTokens(), last.attributedCostUsd() + residual,
                    last.actualRunCostUsd(), last.attributionMethod(), last.isError(), last.agentState(),
                    last.vendor(), last.isSubagentSpawn(), last.thinkingTokens(), last.cacheCreationTokens(),
                    last.cacheReadTokens(), last.turnIndex(), last.durationMs()));
        }
        return steps;
    }

    /** Observed duration for a step, or -1 when none was recorded (never 0 by default). */
    private long durationOf(String toolId) {
        if (toolDurations == null) {
            return -1L;
        }
        Long d = toolDurations.get(toolId);
        return d != null ? d : -1L;
    }
}
