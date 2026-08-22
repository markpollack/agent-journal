package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Projects Antigravity tool steps into portable journal steps. */
public final class AntigravityJournalSteps {

    public static final String VENDOR_ANTIGRAVITY_CLI = "antigravity-cli";

    private static final Set<String> SUBAGENT_TOOLS = Set.of(
            "define_subagent", "invoke_subagent", "browser_subagent");

    private AntigravityJournalSteps() {
    }

    public static List<JournalStep> fromPhaseCapture(AntigravityPhaseCapture phase, String runId) {
        if (phase.toolUses().isEmpty()) {
            return List.of();
        }
        List<JournalStep> steps = new ArrayList<>(phase.toolUses().size());
        for (AntigravityToolUseRecord tool : phase.toolUses()) {
            steps.add(new JournalStep(runId, null, tool.id(), tool.name(), 0, 0,
                    0.0, 0.0, AttributionMethod.EVEN_SPLIT, tool.isError(), null,
                    VENDOR_ANTIGRAVITY_CLI, SUBAGENT_TOOLS.contains(tool.name())));
        }
        return List.copyOf(steps);
    }
}
