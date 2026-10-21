package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunBuilder;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one child run per captured sub-agent thread of a phase, before the parent's
 * {@code llm_call}, and keeps the metadata entries that name them. A child of the root gets the
 * parent run as its {@code parentRunId}; a deeper child gets the run of the thread that spawned it,
 * or the parent run when that thread was not captured. A child whose recording fails is marked
 * failed and the exception is rethrown.
 *
 * <p>The state is the parent run and the map from each written child's {@code spawn_agent} call id
 * to its run id, which is how a deeper child finds its parent run. One instance serves one phase.
 */
final class CodexSubagentRunRecorder {

    private final Run parent;
    private final Map<String, String> runIdBySpawnCallId = new LinkedHashMap<>();
    private final List<Map<String, Object>> written = new ArrayList<>();

    CodexSubagentRunRecorder(Run parent) {
        this.parent = parent;
    }

    /**
     * Records every sub-agent of the phase, each after the thread that spawned it, and returns the
     * metadata entries in writing order. Empty when the phase has no sub-agents or their tracks
     * could not be captured.
     */
    List<Map<String, Object>> recordAll(CodexPhaseCapture phase) {
        if (!phase.hasSubagents() || !phase.subagentTracksAvailable()) {
            return List.of();
        }
        List<CodexSubagentCapture> pending = new ArrayList<>(phase.subagents());
        while (!pending.isEmpty()) {
            if (!recordWhoseParentIsWritten(phase, pending)) {
                // None is ready: their spawning threads were not captured, so they hang off the root.
                for (CodexSubagentCapture subagent : pending) {
                    written.add(record(phase, subagent, parent.id()));
                }
                pending.clear();
            }
        }
        return written;
    }

    /**
     * Records, in order, each pending sub-agent whose spawning thread's run is already written (or
     * which the root spawned) and removes it from {@code pending}. Returns whether any was.
     */
    private boolean recordWhoseParentIsWritten(CodexPhaseCapture phase, List<CodexSubagentCapture> pending) {
        boolean progressed = false;
        for (CodexSubagentCapture subagent : new ArrayList<>(pending)) {
            String parentSpawn = subagent.parentSpawnCallId();
            if (parentSpawn == null || runIdBySpawnCallId.containsKey(parentSpawn)) {
                String parentRunId = parentSpawn == null ? parent.id() : runIdBySpawnCallId.get(parentSpawn);
                written.add(record(phase, subagent, parentRunId));
                pending.remove(subagent);
                progressed = true;
            }
        }
        return progressed;
    }

    private Map<String, Object> record(CodexPhaseCapture phase, CodexSubagentCapture subagent, String parentRunId) {
        RunBuilder builder = Journal.run(parent.experiment().id())
                .parentRun(parentRunId)
                .tag(CodexRunRecorder.TAG_TRACK, CodexRunRecorder.TRACK_SUBAGENT)
                .config("subagent.threadId", subagent.threadId())
                .config("subagent.depth", subagent.depth())
                .config("subagent.forked", subagent.forked());
        if (subagent.spawnCallId() != null) {
            builder.config(CodexRunRecorder.CONFIG_SPAWN_TOOL_USE_ID, subagent.spawnCallId());
        }
        if (subagent.agentPath() != null) {
            builder.config("subagent.agentPath", subagent.agentPath());
            builder.agent(subagent.agentPath());
        }
        if (phase.sessionId() != null) {
            builder.config("subagent.sessionId", phase.sessionId()); // the root thread id
        }
        if (subagent.model() != null) {
            builder.config("model", subagent.model());
        }
        Run child = builder.start();
        if (subagent.spawnCallId() != null) {
            runIdBySpawnCallId.put(subagent.spawnCallId(), child.id());
        }
        try {
            if (subagent.promptText() != null && !subagent.promptText().isEmpty()) {
                child.logEvent(CustomEvent.of("prompt",
                        CodexRunEvents.phaseAttributes(phase.phaseName(), "text", subagent.promptText())));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("phaseName", phase.phaseName());
            metadata.put("isError", !"completed".equals(subagent.status()));
            metadata.put("costAvailable", false);
            metadata.put("costSource", "unreported");
            if (phase.sessionId() != null) {
                metadata.put("sessionId", phase.sessionId());
            }
            if (subagent.cliVersion() != null) {
                metadata.put("cliVersion", subagent.cliVersion());
            }
            child.logEvent(LLMCallEvent.builder()
                    .provider("openai")
                    .model(subagent.model() != null ? subagent.model() : "unknown")
                    .tokenUsage(subagent.tokenUsage())
                    .cost(CostBreakdown.of(0.0))
                    .timing(TimingInfo.of(Math.max(0L, subagent.durationMs())))
                    .metadata(metadata)
                    .build());
            for (CodexToolUseRecord tool : subagent.toolUses()) {
                child.logEvent(CodexRunEvents.toolCallEvent(tool));
            }
            CodexRunEvents.logVendorEvents(child, phase.phaseName(), subagent.vendorEvents());
            Instant analyzedAt = Instant.now();
            for (JournalStep step : CodexJournalSteps.forSubagent(subagent, child.id())) {
                child.logDerivedEvent(StepCostEvent.fromStep(step, analyzedAt));
            }
            child.setSummary("subagent.status", subagent.status());
            child.setSummary("subagent.statusSource", subagent.statusSource());
            child.setSummary("subagent.statusMeaning", CodexRunRecorder.STATUS_MEANING_LAST_OBSERVED_TURN);
            child.setSummary("subagent.replayedRecordsSkipped", subagent.replayedRecordsSkipped());
            child.finish(switch (subagent.status()) {
                case "completed" -> RunStatus.FINISHED;
                case "interrupted" -> RunStatus.FAILED;
                default -> RunStatus.CRASHED; // end not observed; the status text is in the summary
            });
        } catch (RuntimeException e) {
            child.fail(e);
            throw e;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("spawnToolUseId", subagent.spawnCallId());
        entry.put("runId", child.id());
        if (subagent.agentPath() != null) {
            entry.put("agentId", subagent.agentPath());
        }
        entry.put("depth", subagent.depth());
        entry.put("status", subagent.status());
        return entry;
    }
}
