package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Projects Grok tool calls into the portable per-step analysis shape. */
public final class GrokJournalSteps {

    public static final String VENDOR_GROK_CLI = "grok-cli";

    private static final Set<String> SUBAGENT_TOOLS = Set.of("spawn_subagent");

    private GrokJournalSteps() {
    }

    /**
     * Grok reports a real run total but no cost per tool. Without a durable turn-to-tool join,
     * allocation is deliberately labelled {@link AttributionMethod#EVEN_SPLIT}.
     */
    public static List<JournalStep> fromPhaseCapture(GrokPhaseCapture phase, String runId) {
        List<GrokToolUseRecord> tools = phase.toolUses();
        if (tools.isEmpty()) {
            return List.of();
        }

        List<JournalStep> steps = new ArrayList<>(tools.size());
        double perTool = phase.totalCostUsd() / tools.size();
        for (GrokToolUseRecord tool : tools) {
            steps.add(new JournalStep(
                    runId,
                    null,
                    tool.id(),
                    tool.name(),
                    0,
                    0,
                    perTool,
                    phase.totalCostUsd(),
                    AttributionMethod.EVEN_SPLIT,
                    tool.isError(),
                    null,
                    VENDOR_GROK_CLI,
                    SUBAGENT_TOOLS.contains(tool.name())));
        }

        double sum = steps.stream().mapToDouble(JournalStep::attributedCostUsd).sum();
        double residual = phase.totalCostUsd() - sum;
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
