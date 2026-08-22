package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.List;

/** Projects Codex rollout tool calls into portable steps. */
public final class CodexJournalSteps {

    public static final String VENDOR_CODEX_CLI = "codex-cli";

    private CodexJournalSteps() {
    }

    public static List<JournalStep> fromPhaseCapture(CodexPhaseCapture phase, String runId) {
        if (phase.toolUses().isEmpty()) {
            return List.of();
        }
        List<JournalStep> steps = new ArrayList<>(phase.toolUses().size());
        for (CodexToolUseRecord tool : phase.toolUses()) {
            steps.add(new JournalStep(runId, null, tool.id(), tool.name(), 0, 0,
                    0.0, 0.0, AttributionMethod.EVEN_SPLIT, tool.isError(), null,
                    VENDOR_CODEX_CLI, false));
        }
        return List.copyOf(steps);
    }
}
