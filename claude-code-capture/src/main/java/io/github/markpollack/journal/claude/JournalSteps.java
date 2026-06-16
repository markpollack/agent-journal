package io.github.markpollack.journal.claude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds attributed per-step {@link JournalStep}s from a {@link PhaseCapture}.
 *
 * <p>
 * Claude Code reports no per-step cost — only a run total. {@link AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL}
 * splits that real total across steps: each model turn gets a share proportional to its
 * output tokens (the dominant cost driver), and a turn with several parallel tool calls
 * splits its share evenly among them. A tool-less turn becomes one turn-level step. The
 * per-step shares sum to the run total exactly — the float residual is folded into the last
 * step — so {@code actualRunCostUsd} stays ground truth and the per-step number is an
 * auditable allocation, never mistaken for a measured cost.
 *
 * <p>
 * Single-pool by design: the run total is split across all turns regardless of model. This
 * sidesteps the model-id mismatch between the per-turn {@code message.model}
 * ({@code claude-opus-4-8}) and the {@code modelUsage} key ({@code claude-opus-4-8[1m]}) and
 * guarantees the sum stays true; the exact per-model decomposition is preserved separately
 * in {@link PhaseCapture#modelCosts()} for finer analysis.
 */
public final class JournalSteps {

    /** Vendor tag for Claude Code captures. */
    public static final String VENDOR_CLAUDE_CODE = "claude-code";

    /** Tools that spawn a sub-agent (whose interior steps live in {@code subagents/*.jsonl}). */
    private static final Set<String> SUBAGENT_TOOLS = Set.of("Task", "Agent");

    private JournalSteps() {
    }

    public static List<JournalStep> fromPhaseCapture(PhaseCapture phase, String runId) {
        return fromPhaseCapture(phase, runId, VENDOR_CLAUDE_CODE);
    }

    public static List<JournalStep> fromPhaseCapture(PhaseCapture phase, String runId, String vendor) {
        final double actualCost = phase.totalCostUsd();
        final AttributionMethod method = AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL;

        Map<String, String> toolNames = new LinkedHashMap<>();
        for (ToolUseRecord tu : nullSafe(phase.toolUses())) {
            toolNames.put(tu.id(), tu.name());
        }
        Map<String, Boolean> toolErrors = new LinkedHashMap<>();
        for (ToolResultRecord tr : nullSafe(phase.toolResults())) {
            toolErrors.put(tr.toolUseId(), tr.isError());
        }

        List<JournalStep> steps = new ArrayList<>();
        List<TurnUsage> turns = phase.turns();

        if (turns != null && !turns.isEmpty()) {
            long totalOutput = turns.stream().mapToLong(TurnUsage::outputTokens).sum();
            for (TurnUsage turn : turns) {
                double weight = totalOutput > 0 ? (double) turn.outputTokens() / totalOutput : 1.0 / turns.size();
                double turnCost = actualCost * weight;
                List<String> tools = turn.toolUseIds();
                if (tools == null || tools.isEmpty()) {
                    // Tool-less turn (e.g. final text answer) → one turn-level step.
                    steps.add(new JournalStep(runId, turn.messageId(), turn.messageId(), null,
                            turn.inputTokens(), turn.outputTokens(), turnCost, actualCost, method, false, null,
                            vendor, false));
                } else {
                    double perTool = turnCost / tools.size();
                    for (String toolId : tools) {
                        String toolName = toolNames.get(toolId);
                        steps.add(new JournalStep(runId, turn.messageId(), toolId, toolName,
                                turn.inputTokens(), turn.outputTokens(), perTool, actualCost, method,
                                Boolean.TRUE.equals(toolErrors.get(toolId)), null, vendor,
                                SUBAGENT_TOOLS.contains(toolName)));
                    }
                }
            }
        } else {
            // No per-turn usage (rawJson absent / SDK < 1.3.0): even-split across tool calls so
            // cost stays attributable; turnId and per-turn tokens are unavailable.
            List<ToolUseRecord> toolUses = nullSafe(phase.toolUses());
            if (!toolUses.isEmpty()) {
                double perTool = actualCost / toolUses.size();
                for (ToolUseRecord tu : toolUses) {
                    steps.add(new JournalStep(runId, null, tu.id(), tu.name(), 0, 0, perTool, actualCost, method,
                            Boolean.TRUE.equals(toolErrors.get(tu.id())), null, vendor,
                            SUBAGENT_TOOLS.contains(tu.name())));
                }
            }
        }

        return foldResidualIntoLast(steps, actualCost);
    }

    /**
     * Keeps the per-step sum exactly equal to the run total by adding the float residual to
     * the last step — so "the total is true" survives the proportional split.
     */
    private static List<JournalStep> foldResidualIntoLast(List<JournalStep> steps, double actualCost) {
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
                    last.vendor(), last.isSubagentSpawn()));
        }
        return steps;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }
}
