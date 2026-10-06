package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.event.TokenUsage;
import java.util.List;

/**
 * What one Codex sub-agent thread did, read from its own rollout file.
 *
 * <p>The identity is the child's {@code threadId}: {@code session_meta.payload.id} of the first
 * {@code session_meta} in its file. {@code spawnCallId} is the parent's {@code spawn_agent} call id,
 * joined through the parent's {@code SubAgentActivity{kind:"started"}} record whose
 * {@code agent_thread_id} is this thread; it is {@code null} when no such record names the thread.
 * {@code parentSpawnCallId} is the spawn call id of the thread that spawned this one, {@code null} for
 * a child of the root. {@code depth} is 1 for a child of the root, one more for each level below, and
 * -1 when the parent chain cannot be resolved from the supplied rollouts.
 *
 * <p>{@code status} is {@code completed}, {@code interrupted} or {@code unknown}. Codex writes no
 * thread-terminal status, so it means the last observed turn: the parent's last
 * {@code SubAgentActivity} of kind {@code completed} or {@code interrupted} for this thread
 * ({@code statusSource=parent_sub_agent_activity_last}), else the child's own last turn-terminal
 * record, {@code task_complete} or {@code turn_aborted} ({@code statusSource=child_last_turn}), else
 * {@code statusSource=none}. Tokens and tool uses come from the child's file only. A forked child
 * replays its parent's history; those records are skipped and counted in
 * {@code replayedRecordsSkipped}. {@code durationMs} is -1 when the child's end was not observed.
 */
public record CodexSubagentCapture(
        String threadId,
        String spawnCallId,
        String parentSpawnCallId,
        int depth,
        String agentPath,
        String status,
        String statusSource,
        boolean forked,
        int replayedRecordsSkipped,
        String cliVersion,
        String model,
        String promptText,
        String textOutput,
        List<CodexToolUseRecord> toolUses,
        int inputTokens,
        int outputTokens,
        int reasoningOutputTokens,
        int cacheWriteInputTokens,
        int cachedInputTokens,
        long durationMs
) {

    public CodexSubagentCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        status = status == null ? "unknown" : status;
        statusSource = statusSource == null ? "none" : statusSource;
    }

    /** Returns the token usage of this thread only, mapped as {@link CodexPhaseCapture#tokenUsage()}. */
    public TokenUsage tokenUsage() {
        return new TokenUsage(Math.max(0, inputTokens - cachedInputTokens), outputTokens,
                reasoningOutputTokens, cacheWriteInputTokens, cachedInputTokens, 0);
    }
}
