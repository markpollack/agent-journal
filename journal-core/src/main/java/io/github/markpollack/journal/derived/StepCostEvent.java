package io.github.markpollack.journal.derived;

import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cost and token use of one step of a run, a {@link DerivedEvent}. A step is one tool call, or
 * a model turn that called no tool. The capture modules' run recorders log one per step, made
 * with {@link #fromStep(JournalStep, Instant)}; read them back with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadDerivedEvents} and match each
 * to its {@link io.github.markpollack.journal.event.ToolCallEvent} by {@link #stepId()}.
 *
 * <p>The agent CLIs do not report a cost for each step, so the per-step cost is a share of a
 * reported total. Despite its name, {@link #actualRunCostUsd()} is the total of the phase (one
 * agent call) the step belongs to, the same in every step of that phase; it is the run's total
 * only when the run has one phase. {@link #attributedCostUsd()} is this step's share, split as
 * {@link #attributionMethod()} says. Add up the shares, not the totals, to get a cost per tool or
 * per turn.
 *
 * <p>Token counts belong to the step's turn. When one turn makes several tool calls, every one of
 * its steps repeats the turn's counts, so do not add tokens up over steps. The counts are as the
 * capture module reports them; with Claude Code, {@link #inputTokens()} leaves out prompt-cache
 * tokens. {@link #thinkingTokens()} are part of {@link #outputTokens()}, not extra.
 *
 * <p>Records written before the token, turn and duration fields were added still load: missing
 * token counts read as 0, and a missing {@link #turnIndex()} or {@link #durationMs()} as -1.
 *
 * @param timestamp when the cost was split, not when the step ran; must not be {@code null}
 * @param runId the ID of the run
 * @param stepId the ID of the step: for a tool step, the tool call's ID; for a turn without a
 *        tool, an ID the capture module gives the turn
 * @param turnId the ID of the model turn the step belongs to, such as the assistant message ID
 * @param toolName the name of the tool called, or {@code null} for a turn without a tool
 * @param inputTokens the input tokens of the step's turn
 * @param outputTokens the output tokens of the step's turn
 * @param attributedCostUsd this step's share of the run's cost, in US dollars
 * @param actualRunCostUsd the phase's total cost as reported, in US dollars
 * @param attributionMethod how the run's cost was split into shares
 * @param vendor the capture module that made the step, such as {@code "claude-code"} or
 *        {@code "gemini-cli"}
 * @param thinkingTokens the thinking tokens of the step's turn, included in {@code outputTokens}
 * @param cacheCreationTokens the tokens the step's turn wrote to the prompt cache
 * @param cacheReadTokens the tokens the step's turn read from the prompt cache
 * @param turnIndex the position of the step's turn in the run, counting from 0, or -1 if unknown
 * @param durationMs how long the step took in milliseconds, or -1 if unknown
 */
public record StepCostEvent(
        Instant timestamp,
        String runId,
        String stepId,
        String turnId,
        String toolName,
        long inputTokens,
        long outputTokens,
        double attributedCostUsd,
        double actualRunCostUsd,
        AttributionMethod attributionMethod,
        String vendor,
        long thinkingTokens,
        long cacheCreationTokens,
        long cacheReadTokens,
        int turnIndex,
        long durationMs
) implements DerivedEvent {

    /** The type name of this event, {@value}, used as its {@code @type} name in JSON. */
    public static final String TYPE = "step_cost";

    /**
     * Creates a step cost without thinking, cache, turn position or duration details: the three
     * token counts are 0, and {@code turnIndex} and {@code durationMs} are -1 (unknown). See the
     * class comment for the meaning of each value.
     *
     * @param timestamp when the cost was split; must not be {@code null}
     * @param runId the run ID
     * @param stepId the step ID
     * @param turnId the turn ID
     * @param toolName the tool name, or {@code null}
     * @param inputTokens the turn's input tokens
     * @param outputTokens the turn's output tokens
     * @param attributedCostUsd this step's share of the cost
     * @param actualRunCostUsd the phase's total cost
     * @param attributionMethod how the cost was split
     * @param vendor the capture module
     */
    public StepCostEvent(Instant timestamp, String runId, String stepId, String turnId, String toolName,
            long inputTokens, long outputTokens, double attributedCostUsd, double actualRunCostUsd,
            AttributionMethod attributionMethod, String vendor) {
        this(timestamp, runId, stepId, turnId, toolName, inputTokens, outputTokens, attributedCostUsd,
                actualRunCostUsd, attributionMethod, vendor, 0L, 0L, 0L, -1, -1L);
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * Creates a step cost from a {@link JournalStep}, the per-step record that the capture modules
     * build. It copies the step's IDs, tool name, token counts, costs, method, vendor, turn
     * position and duration; the step's error flag, agent state and sub-agent flag are not
     * carried over.
     *
     * @param step the step to copy; must not be {@code null}
     * @param timestamp when the cost was split, usually now; must not be {@code null}
     * @return the step cost
     */
    public static StepCostEvent fromStep(JournalStep step, Instant timestamp) {
        return new StepCostEvent(timestamp, step.runId(), step.stepId(), step.turnId(), step.toolName(),
                step.inputTokens(), step.outputTokens(), step.attributedCostUsd(), step.actualRunCostUsd(),
                step.attributionMethod(), step.vendor(), step.thinkingTokens(), step.cacheCreationTokens(),
                step.cacheReadTokens(), step.turnIndex(), step.durationMs());
    }

    /**
     * Deserialization entry point, present so a <strong>pre-1.9.0 {@code analysis.jsonl} reads back
     * honestly</strong>. Those records carry neither {@code turnIndex} nor {@code durationMs};
     * Jackson's defaults for the primitives would resolve them to {@code 0}, which reads as "the
     * first turn" and "took no time" rather than "never captured". Both become -1 when absent.
     * Token fields legitimately default to 0 — a token count that was not recorded and one that
     * was zero are the same claim about volume.
     */
    @com.fasterxml.jackson.annotation.JsonCreator
    static StepCostEvent fromJson(
            @com.fasterxml.jackson.annotation.JsonProperty("timestamp") Instant timestamp,
            @com.fasterxml.jackson.annotation.JsonProperty("runId") String runId,
            @com.fasterxml.jackson.annotation.JsonProperty("stepId") String stepId,
            @com.fasterxml.jackson.annotation.JsonProperty("turnId") String turnId,
            @com.fasterxml.jackson.annotation.JsonProperty("toolName") String toolName,
            @com.fasterxml.jackson.annotation.JsonProperty("inputTokens") long inputTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("outputTokens") long outputTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("attributedCostUsd") double attributedCostUsd,
            @com.fasterxml.jackson.annotation.JsonProperty("actualRunCostUsd") double actualRunCostUsd,
            @com.fasterxml.jackson.annotation.JsonProperty("attributionMethod") AttributionMethod attributionMethod,
            @com.fasterxml.jackson.annotation.JsonProperty("vendor") String vendor,
            @com.fasterxml.jackson.annotation.JsonProperty("thinkingTokens") long thinkingTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("cacheCreationTokens") long cacheCreationTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("cacheReadTokens") long cacheReadTokens,
            @com.fasterxml.jackson.annotation.JsonProperty("turnIndex") Integer turnIndex,
            @com.fasterxml.jackson.annotation.JsonProperty("durationMs") Long durationMs) {
        return new StepCostEvent(timestamp, runId, stepId, turnId, toolName, inputTokens, outputTokens,
                attributedCostUsd, actualRunCostUsd, attributionMethod, vendor, thinkingTokens,
                cacheCreationTokens, cacheReadTokens, turnIndex != null ? turnIndex : -1,
                durationMs != null ? durationMs : -1L);
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("@type", TYPE);
        map.put("timestamp", timestamp.toString());
        map.put("runId", runId);
        if (stepId != null) {
            map.put("stepId", stepId);
        }
        if (turnId != null) {
            map.put("turnId", turnId);
        }
        if (toolName != null) {
            map.put("toolName", toolName);
        }
        map.put("inputTokens", inputTokens);
        map.put("outputTokens", outputTokens);
        map.put("thinkingTokens", thinkingTokens);
        map.put("cacheCreationTokens", cacheCreationTokens);
        map.put("cacheReadTokens", cacheReadTokens);
        map.put("turnIndex", turnIndex);
        map.put("durationMs", durationMs);
        map.put("attributedCostUsd", attributedCostUsd);
        map.put("actualRunCostUsd", actualRunCostUsd);
        if (attributionMethod != null) {
            map.put("attributionMethod", attributionMethod.name());
        }
        if (vendor != null) {
            map.put("vendor", vendor);
        }
        return map;
    }
}
