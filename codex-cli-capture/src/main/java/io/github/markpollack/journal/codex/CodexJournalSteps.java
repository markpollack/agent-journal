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
        return toolSteps(phase.toolUses(), runId);
    }

    /**
     * Builds one step per tool call of a sub-agent track, as {@link #fromPhaseCapture} does for the
     * parent: zero tokens and cost, {@link AttributionMethod#EVEN_SPLIT}.
     *
     * @param subagent the captured sub-agent thread
     * @param runId the child run the steps belong to
     * @return the steps, in tool order; empty if the thread made no tool calls
     */
    public static List<JournalStep> forSubagent(CodexSubagentCapture subagent, String runId) {
        return toolSteps(subagent.toolUses(), runId);
    }

    /**
     * Returns whether a tool name starts a sub-agent thread, so that its step is marked
     * {@code isSubagentSpawn}.
     */
    public static boolean isSubagentSpawn(String toolName) {
        return CodexPhaseCapture.SPAWN_AGENT_TOOL.equals(toolName);
    }

    private static List<JournalStep> toolSteps(List<CodexToolUseRecord> toolUses, String runId) {
        if (toolUses.isEmpty()) {
            return List.of();
        }
        List<JournalStep> steps = new ArrayList<>(toolUses.size());
        for (CodexToolUseRecord tool : toolUses) {
            steps.add(new JournalStep(runId, null, tool.id(), tool.name(), 0, 0,
                    0.0, 0.0, AttributionMethod.EVEN_SPLIT, tool.isError(), null,
                    VENDOR_CODEX_CLI, isSubagentSpawn(tool.name())));
        }
        return List.copyOf(steps);
    }
}
