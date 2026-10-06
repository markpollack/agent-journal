package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one sub-agent did during a Claude Code call: its own text, thinking, tool calls, tool
 * results and per-turn usage, kept apart from the main loop's. {@link SessionLogParser} builds
 * one for each sub-agent whose messages arrived on the stream, and {@link PhaseCapture#subagents()}
 * lists them. A {@link RunRecorder} writes each as its own run, linked to the run of the agent
 * that spawned it.
 *
 * <p>A sub-agent is identified by {@code spawnToolUseId}: the ID of the {@code Agent} or
 * {@code Task} tool call that started it, which Claude Code writes as {@code parent_tool_use_id}
 * on each of the sub-agent's messages. That ID is also the ID of the spawning
 * {@link ToolUseRecord}, in the main loop for a sub-agent of depth 1 and in the enclosing
 * sub-agent's capture for a nested one.
 *
 * <p>Claude Code always sends a sub-agent's tool calls, tool results, prompt and usage. It sends
 * the sub-agent's text and thinking only when started with {@code --forward-subagent-text}, so
 * an empty {@code textOutput} does not show that the sub-agent wrote nothing. It reports no cost
 * for a sub-agent; the cost is part of the call's total, {@link PhaseCapture#totalCostUsd()}.
 *
 * <p>The record copies its lists into unmodifiable ones.
 *
 * @param spawnToolUseId the ID of the tool call that started this sub-agent
 * @param parentSpawnToolUseId the {@code spawnToolUseId} of the sub-agent that started this one,
 *        or {@code null} if the main loop did, or if the spawning tool call was not seen
 * @param depth 1 for a sub-agent of the main loop, 2 for a sub-agent of that one, and so on, or
 *        -1 if it could not be established
 * @param agentId Claude Code's ID for the sub-agent, or {@code null} if not reported
 * @param subagentType the kind of agent, such as {@code "general-purpose"}, or {@code null}
 * @param description the short description given when it was started, or {@code null}
 * @param promptText the prompt the sub-agent was given, or {@code null} if not seen
 * @param status the final status Claude Code reported, such as {@code "completed"} or
 *        {@code "failed"}, or {@code null} if the stream reported none. It is never inferred: a
 *        result for the spawning tool call does not by itself show how the sub-agent ended
 * @param statusSource where {@code status} was read: {@code "task_notification"}, the
 *        sub-agent's own end message; {@code "tool_use_result"}, the summary on the result of
 *        the spawning tool call; or {@code "none"}
 * @param backgrounded whether Claude Code reported that the sub-agent was started in the
 *        background, so that it may still have been running when the call ended
 * @param reportedTotalTokens the sub-agent's token total as Claude Code reported it, or -1
 * @param reportedToolUses the number of tool calls Claude Code reported for it, or -1
 * @param reportedDurationMs the time Claude Code reported for it, in milliseconds, or -1
 * @param textOutput the sub-agent's text, joined; empty if none was forwarded
 * @param thinkingBlocks the sub-agent's thinking blocks; empty if none was forwarded
 * @param toolUses the sub-agent's tool calls, in order; a turn index counts the sub-agent's own
 *        turns from 0
 * @param toolResults the results of those tool calls
 * @param turns the sub-agent's usage, one entry per assistant message
 */
public record SubagentCapture(
        String spawnToolUseId,
        String parentSpawnToolUseId,
        int depth,
        String agentId,
        String subagentType,
        String description,
        String promptText,
        String status,
        String statusSource,
        boolean backgrounded,
        long reportedTotalTokens,
        long reportedToolUses,
        long reportedDurationMs,
        String textOutput,
        List<String> thinkingBlocks,
        List<ToolUseRecord> toolUses,
        List<ToolResultRecord> toolResults,
        List<TurnUsage> turns
) {

    /** Copies the lists; a {@code null} list becomes an empty one. */
    public SubagentCapture {
        textOutput = textOutput != null ? textOutput : "";
        statusSource = status == null || statusSource == null ? "none" : statusSource;
        thinkingBlocks = copy(thinkingBlocks);
        toolUses = copy(toolUses);
        toolResults = copy(toolResults);
        turns = copy(turns);
    }

    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * Returns the sub-agent's token usage: each token type added up over {@link #turns()}, with
     * the turns' thinking tokens as the thinking count. Thinking tokens are part of the output
     * tokens. The usage covers this sub-agent only, not sub-agents it started.
     *
     * @return the usage, all zeros if no per-turn usage was captured
     */
    public TokenUsage aggregateUsage() {
        long input = 0;
        long output = 0;
        long thinking = 0;
        long cacheCreation = 0;
        long cacheRead = 0;
        for (TurnUsage turn : turns) {
            input += turn.inputTokens();
            output += turn.outputTokens();
            thinking += turn.thinkingTokens();
            cacheCreation += turn.cacheCreationInputTokens();
            cacheRead += turn.cacheReadInputTokens();
        }
        return new TokenUsage((int) input, (int) output, (int) thinking, (int) cacheCreation, (int) cacheRead, 0);
    }

    /**
     * Returns the model of the sub-agent's first turn that names one.
     *
     * @return the model, or {@code null} if no turn names one
     */
    public String model() {
        for (TurnUsage turn : turns) {
            if (turn.model() != null) {
                return turn.model();
            }
        }
        return null;
    }

    /**
     * Returns whether Claude Code reported that the sub-agent failed or was killed. Any other
     * status, and no status, is not a reported failure.
     *
     * @return {@code true} for the status {@code "failed"} or {@code "killed"}
     */
    public boolean reportedFailed() {
        return "failed".equals(status) || "killed".equals(status);
    }
}
