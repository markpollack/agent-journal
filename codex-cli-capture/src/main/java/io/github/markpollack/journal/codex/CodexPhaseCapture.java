package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The parsed record of one Codex CLI session: the agent's final message, the tool calls it made,
 * the tokens it used, and how long it took. {@link CodexSessionParser} builds one from a Codex
 * rollout file, or from any run of its lines. Pass it to a {@link CodexRunRecorder} to log it as
 * events on a {@link io.github.markpollack.journal.Run}, or read its fields when you only need
 * usage.
 *
 * <p>It is the Codex counterpart of Claude Code's {@code PhaseCapture}, with less detail. Codex
 * reports no cost, so the record has no cost field, and the recorder logs a cost of 0 marked
 * {@code costAvailable=false}. It also has no turn count, no stop reason, no thinking text and no
 * per-turn usage. The token counts are Codex's running total for the session, taken from the last
 * {@code token_count} record the parser read. In the recorded output of Codex 0.148.0, the input
 * count includes the cached input and the output count includes the reasoning tokens.
 *
 * <p>The record copies {@code toolUses} into an unmodifiable list, so a capture can be shared
 * between threads.
 *
 * @param phaseName the caller's name for this session or part of it, such as {@code "execute"}
 * @param promptText the prompt that was sent, or {@code null} if it was not captured
 * @param model the model named in the last {@code turn_context} record, or {@code null} if none
 *        was named
 * @param cliVersion the Codex CLI version named in the {@code session_meta} record, or
 *        {@code null} if none was named
 * @param sessionId the Codex session ID, or {@code null} if none was recorded
 * @param inputTokens the input tokens Codex reported
 * @param outputTokens the output tokens Codex reported
 * @param reasoningOutputTokens the reasoning tokens Codex reported
 * @param cacheWriteInputTokens the tokens written to the prompt cache
 * @param cachedInputTokens the input tokens read from the prompt cache
 * @param durationMs the duration of the task that Codex reported, in milliseconds, or 0 if no
 *        {@code task_complete} record was read
 * @param isError whether the rollout has no {@code task_complete} record, or one with a status
 *        other than {@code completed}; a failed tool call does not set it
 * @param textOutput the agent's last message, from {@code task_complete}; empty if there was none
 * @param toolUses the tool calls, in the order they first appeared; never {@code null}
 * @param subagents the sub-agent threads captured from their own rollouts; empty for a single-reader
 *     parse
 * @param subagentTracksAvailable whether the phase was parsed from {@link CodexRollouts}, so that
 *     child tracks could have been captured
 * @param spawnedThreadIds the {@code spawn_agent} call ids that a {@code SubAgentActivity}
 *     {@code started} record followed, mapped to the child thread id it named
 */
public record CodexPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String cliVersion,
        String sessionId,
        int inputTokens,
        int outputTokens,
        int reasoningOutputTokens,
        int cacheWriteInputTokens,
        int cachedInputTokens,
        long durationMs,
        boolean isError,
        String textOutput,
        List<CodexToolUseRecord> toolUses,
        List<CodexSubagentCapture> subagents,
        boolean subagentTracksAvailable,
        Map<String, String> spawnedThreadIds
) {

    /** The tool name Codex uses to start a sub-agent thread. */
    public static final String SPAWN_AGENT_TOOL = "spawn_agent";

    /**
     * Creates a capture from all of its parts. A {@code null} list or map becomes empty; any other
     * is copied.
     */
    public CodexPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        subagents = subagents == null ? List.of() : List.copyOf(subagents);
        spawnedThreadIds = spawnedThreadIds == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(spawnedThreadIds));
    }

    /**
     * Creates a capture without sub-agent tracks, as the single-reader
     * {@link CodexSessionParser#parse(java.io.BufferedReader, String, String)} produces.
     */
    public CodexPhaseCapture(String phaseName, String promptText, String model, String cliVersion,
            String sessionId, int inputTokens, int outputTokens, int reasoningOutputTokens,
            int cacheWriteInputTokens, int cachedInputTokens, long durationMs, boolean isError,
            String textOutput, List<CodexToolUseRecord> toolUses) {
        this(phaseName, promptText, model, cliVersion, sessionId, inputTokens, outputTokens,
                reasoningOutputTokens, cacheWriteInputTokens, cachedInputTokens, durationMs, isError,
                textOutput, toolUses, List.of(), false, Map.of());
    }

    /** Creates a capture with sub-agent tracks and no record of which spawns were started. */
    public CodexPhaseCapture(String phaseName, String promptText, String model, String cliVersion,
            String sessionId, int inputTokens, int outputTokens, int reasoningOutputTokens,
            int cacheWriteInputTokens, int cachedInputTokens, long durationMs, boolean isError,
            String textOutput, List<CodexToolUseRecord> toolUses, List<CodexSubagentCapture> subagents,
            boolean subagentTracksAvailable) {
        this(phaseName, promptText, model, cliVersion, sessionId, inputTokens, outputTokens,
                reasoningOutputTokens, cacheWriteInputTokens, cachedInputTokens, durationMs, isError,
                textOutput, toolUses, subagents, subagentTracksAvailable, Map.of());
    }

    /** A spawn in this phase that has no sub-agent track, with the reason. */
    public record SpawnWithoutTrack(String callId, String reason) {
    }

    /** Returns whether at least one sub-agent track was captured. */
    public boolean hasSubagents() {
        return !subagents.isEmpty();
    }

    /**
     * Returns the {@code spawn_agent} calls of this phase that have no captured track. The reason is
     * {@code spawn_failed} for a spawn whose output is a plain string and that no {@code started}
     * activity followed, and {@code not_collected} for a spawn with a {@code started} activity whose
     * child rollout was not supplied. Empty for a capture without sub-agent tracks, which cannot
     * tell the two apart.
     */
    public List<SpawnWithoutTrack> subagentsWithoutTrack() {
        if (!subagentTracksAvailable) {
            return List.of();
        }
        Set<String> captured = new HashSet<>();
        for (CodexSubagentCapture subagent : subagents) {
            if (subagent.spawnCallId() != null) {
                captured.add(subagent.spawnCallId());
            }
        }
        List<SpawnWithoutTrack> missing = new ArrayList<>();
        for (CodexToolUseRecord tool : toolUses) {
            if (!SPAWN_AGENT_TOOL.equals(tool.name()) || captured.contains(tool.id())) {
                continue;
            }
            boolean started = spawnedThreadIds.containsKey(tool.id());
            missing.add(new SpawnWithoutTrack(tool.id(), started ? "not_collected" : "spawn_failed"));
        }
        return List.copyOf(missing);
    }

    /**
     * Returns whether the agent made any tool calls.
     *
     * @return {@code true} if {@code toolUses} is not empty
     */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    /**
     * Returns the token counts as a {@link TokenUsage}, with {@code reasoningOutputTokens} as the
     * thinking tokens and {@code cachedInputTokens} as the cache reads. Codex counts the cached
     * input inside its input count, and a {@code TokenUsage} keeps the two apart, so the usage's
     * input tokens are {@code inputTokens} minus {@code cachedInputTokens}, and never below 0.
     * The other counts are passed on as Codex reported them. Up to 1.10.1 the input tokens were
     * passed on with the cached input still included.
     *
     * @return the usage of this session; its tool-use token count is 0
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(Math.max(0, inputTokens - cachedInputTokens), outputTokens,
                reasoningOutputTokens, cacheWriteInputTokens, cachedInputTokens, 0);
    }
}
