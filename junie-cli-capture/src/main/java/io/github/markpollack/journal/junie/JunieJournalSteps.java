package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.List;

/** Projects Junie steps into the portable per-step analysis shape. */
public final class JunieJournalSteps {

    public static final String VENDOR_JUNIE_CLI = "junie-cli";

    private JunieJournalSteps() {
    }

    /**
     * Attributes the session's real cost across its steps.
     *
     * <p>
     * Junie reports a true session total ({@code completion.taskCostUsd}) but no per-step cost — it
     * prices LLM calls, and an LLM call is not a step. So {@code actualRunCostUsd} is ground truth
     * and {@code attributedCostUsd} is an even split of it, labelled
     * {@link AttributionMethod#EVEN_SPLIT} so nobody mistakes the share for a measurement. The
     * float residual is folded into the last step so the shares sum to the total exactly.
     *
     * <p>
     * Token fields stay zero: Junie's usage is per-LLM-call and carries no join back to the step
     * that provoked it, so any per-step token figure would be invented. Zero-because-unjoinable is
     * the honest record.
     */
    public static List<JournalStep> fromPhaseCapture(JuniePhaseCapture phase, String runId) {
        List<JunieToolUseRecord> tools = phase.toolUses();
        if (tools.isEmpty()) {
            return List.of();
        }

        double total = phase.totalCostUsd();
        double perTool = total / tools.size();
        List<JournalStep> steps = new ArrayList<>(tools.size());
        for (JunieToolUseRecord tool : tools) {
            steps.add(new JournalStep(runId, null, tool.id(), tool.name(), 0, 0,
                    perTool, total, AttributionMethod.EVEN_SPLIT, tool.isError(), null,
                    VENDOR_JUNIE_CLI, false));
        }

        double residual = total - steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum();
        if (residual != 0.0) {
            int lastIndex = steps.size() - 1;
            JournalStep last = steps.get(lastIndex);
            steps.set(lastIndex, new JournalStep(last.runId(), last.turnId(), last.stepId(), last.toolName(),
                    last.inputTokens(), last.outputTokens(), last.attributedCostUsd() + residual,
                    last.actualRunCostUsd(), last.attributionMethod(), last.isError(), last.agentState(),
                    last.vendor(), last.isSubagentSpawn()));
        }
        return List.copyOf(steps);
    }
}
