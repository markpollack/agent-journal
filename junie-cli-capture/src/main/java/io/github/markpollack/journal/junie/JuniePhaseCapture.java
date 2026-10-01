package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/**
 * The parsed record of one Junie CLI session: the agent's final result and patch, the tool steps
 * it ran, its thinking, the tokens it used, what it cost, and why it stopped.
 * {@link JunieSessionParser} builds one from the session's {@code events.jsonl} file. Pass it to a
 * {@link JunieRunRecorder} to log it as events on a {@link io.github.markpollack.journal.Run}, or
 * read its fields when you only need usage and cost.
 *
 * <p>It is the Junie counterpart of Claude Code's {@code PhaseCapture}, with a real cost but no
 * turns. Junie prices every model call and reports a total for the session:
 * {@link #modelCosts()} holds the cost of each call, and {@link #reconcilesToModelCosts()} checks
 * that they add up to {@code totalCostUsd}. In place of a turn count, {@code numLlmCalls} counts
 * model calls, including calls to the helper models Junie uses besides the main model. Junie sets
 * and reports no turn limit, so the parser sets {@code maxTurns} to -1. Junie reports no
 * thinking-token count, so the record has no field for one, though it keeps the thinking text
 * when the session file has it.
 *
 * <p>What the session file holds depends on how Junie was run. A run over the Agent Client
 * Protocol ({@code junie --acp true}) records the prompt, a task state and thinking text. A plain
 * CLI run records none of these, so {@code taskState} is {@code null}, {@code thinkingBlocks} is
 * empty, and {@code promptText} is the prompt the caller passed to the parser.
 *
 * <p>The record copies its lists into unmodifiable lists, so a capture can be shared between
 * threads.
 *
 * @param phaseName the caller's name for this phase, such as {@code "plan"} or {@code "execute"}
 * @param promptText the prompt Junie ran: the one recorded in the session file, else the one the
 *        caller passed, or {@code null} if neither is known
 * @param model the model the task was launched with, as recorded with the prompt; else the model
 *        whose calls cost the most; or {@code null} if neither is known
 * @param taskId Junie's task ID, such as {@code task-260826-171831-hx9l}, or {@code null} if none
 *        was recorded
 * @param taskName the name Junie gave the task, or {@code null} if none was recorded
 * @param inputTokens the input tokens summed over every model call, not counting cache reads
 * @param outputTokens the output tokens summed over every model call
 * @param cacheReadTokens the tokens read from the prompt cache, summed over every model call
 * @param cacheCreationTokens the tokens written to the prompt cache, summed over every model call
 * @param totalCostUsd the total cost Junie reported for the session, in US dollars, or 0 if none
 *        was reported
 * @param durationMs the time from the session's recorded start to its recorded end, in
 *        milliseconds, or 0 if either is missing
 * @param numLlmCalls the number of model calls, counted from Junie's per-call usage reports
 * @param isError whether the session was cancelled, ended in an error state, or was never
 *        finished (the file has neither a completion record nor a result)
 * @param taskState the task state Junie recorded, such as {@code "COMPLETED"}, or {@code null}
 *        for a plain CLI run
 * @param errorCode the error code of the final result, which is {@code "Submit"} when the agent
 *        finished by handing back a result, or {@code null} if no result was recorded
 * @param cancelled whether the final result was marked as cancelled
 * @param textOutput the agent's final result, in Markdown, or {@code null} if none was recorded
 * @param patch the unified diff Junie made for the session, or {@code null} if it made none
 * @param thinkingBlocks the text of each thinking block, in order; empty for a plain CLI run
 * @param contextWindowUsed the context-window use last reported, in tokens, or -1 if none was
 *        reported
 * @param contextWindowSize the size of the model's context window, in tokens, or -1 if none was
 *        reported
 * @param stopReason why the session stopped; never {@code null}, because a missing reason becomes
 *        {@link StopReason#UNKNOWN}
 * @param maxTurns the turn limit the session ran against; always -1 from the parser
 * @param modelCosts the cost and tokens of each model call, in order; never {@code null}
 * @param toolUses the tool steps, one per Junie step ID, in the order they first appeared; never
 *        {@code null}
 */
public record JuniePhaseCapture(
        String phaseName,
        String promptText,
        String model,
        String taskId,
        String taskName,
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens,
        double totalCostUsd,
        long durationMs,
        int numLlmCalls,
        boolean isError,
        String taskState,
        String errorCode,
        boolean cancelled,
        String textOutput,
        String patch,
        List<String> thinkingBlocks,
        long contextWindowUsed,
        long contextWindowSize,
        StopReason stopReason,
        int maxTurns,
        List<JunieModelCost> modelCosts,
        List<JunieToolUseRecord> toolUses
) {

    /**
     * The largest difference, in US dollars, that {@link #reconcilesToModelCosts()} accepts
     * between the sum of the per-call costs and {@code totalCostUsd}. The value is 0.0001.
     */
    public static final double COST_RECONCILIATION_TOLERANCE_USD = 1e-4;

    /**
     * Creates a capture from all of its parts. A {@code null} {@code stopReason} becomes
     * {@link StopReason#UNKNOWN}. A {@code null} list becomes an empty list; any other list is
     * copied.
     */
    public JuniePhaseCapture {
        stopReason = stopReason != null ? stopReason : StopReason.UNKNOWN;
        thinkingBlocks = thinkingBlocks == null ? List.of() : List.copyOf(thinkingBlocks);
        modelCosts = modelCosts == null ? List.of() : List.copyOf(modelCosts);
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
    }

    /**
     * Returns whether the agent ran any tool steps.
     *
     * @return {@code true} if {@code toolUses} is not empty
     */
    public boolean hasToolUses() {
        return !toolUses.isEmpty();
    }

    /**
     * Returns whether any thinking text was captured.
     *
     * @return {@code true} if {@code thinkingBlocks} is not empty
     */
    public boolean hasThinking() {
        return !thinkingBlocks.isEmpty();
    }

    /**
     * Returns whether per-call costs were captured.
     *
     * @return {@code true} if {@code modelCosts} is not empty
     */
    public boolean hasModelCosts() {
        return !modelCosts.isEmpty();
    }

    /**
     * Returns the token counts as a {@link TokenUsage}. Junie reports no thinking tokens, so the
     * thinking count is 0, which here means "not reported".
     *
     * <p>{@link TokenUsage#total()} leaves out cache reads. The total that Junie itself reports
     * over the Agent Client Protocol includes them, so the two totals differ.
     *
     * @return the usage of this session; its thinking and tool-use token counts are 0
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(inputTokens, outputTokens, 0, cacheCreationTokens, cacheReadTokens, 0);
    }

    /**
     * Returns the sum of the per-call costs in {@link #modelCosts()}. When Junie reported per-call
     * costs, this equals {@code totalCostUsd} up to rounding.
     *
     * @return the summed cost in US dollars, or 0 if there are no per-call costs
     */
    public double modelCostSum() {
        return modelCosts.stream().mapToDouble(JunieModelCost::costUsd).sum();
    }

    /**
     * Returns whether the per-call costs add up to the session's total cost, within
     * {@link #COST_RECONCILIATION_TOLERANCE_USD}.
     *
     * @return {@code true} if the costs agree; {@code false} if they disagree or if no per-call
     *         costs were captured (use {@link #hasModelCosts()} to tell these apart)
     */
    public boolean reconcilesToModelCosts() {
        if (!hasModelCosts()) {
            return false;
        }
        return Math.abs(modelCostSum() - totalCostUsd) <= COST_RECONCILIATION_TOLERANCE_USD;
    }

    /**
     * Returns whether the session was cut short instead of finishing on its own: it failed or was
     * cancelled.
     *
     * @return {@code true} if the stop reason is one that {@link StopReason#isTruncatedRun()}
     *         treats as a cut-off
     */
    public boolean wasTruncated() {
        return stopReason != null && stopReason.isTruncatedRun();
    }
}
