package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.StopReason;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.trace.JournalStep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The parsed record of one Claude Code call: what the agent wrote and did, the tokens it used,
 * what it cost, and why it stopped. {@link SessionLogParser} builds one from the Claude SDK's
 * message stream. Pass it to a {@link RunRecorder} to log it as events on a
 * {@link io.github.markpollack.journal.Run}, or read its fields when you only need usage and cost.
 *
 * <p>A phase is one call to the agent with one prompt. The caller names it, for example
 * {@code "plan"} or {@code "execute"}, and one run can record several phases. Other agent CLIs
 * have their own records of the same kind, such as {@code GrokPhaseCapture}.
 *
 * <p>The record offers two token views, for different questions:
 * <ul>
 *   <li>{@link #aggregateUsage()} adds up each token type over every turn, including cache
 *       writes and reads. Use it for anything that prices tokens.
 *   <li>{@link #snapshotUsage()} and the token accessors such as {@link #inputTokens()} hold the
 *       usage reported on the final result message. It is a point-in-time figure that
 *       under-counts long runs, so do not use it as a cost basis.
 * </ul>
 *
 * <p>The turn limit ({@code maxTurns}) means something only next to the stop reason: 55 turns
 * alone does not say whether the agent finished or was cut off at its limit. Claude Code does not
 * report the limit, so it comes from the caller through
 * {@link SessionLogParser#parse(java.util.Iterator, String, String, java.nio.file.Path,
 * io.github.markpollack.journal.trace.TraceContentMode,
 * io.github.markpollack.journal.trace.TraceRawMode, int)}.
 *
 * <p>The capture keeps unmodifiable copies of the lists it is given: later changes to those lists
 * do not reach it, and its own lists cannot be changed. A capture can be shared between threads.
 *
 * @param phaseName the caller's name for this phase, such as {@code "plan"} or {@code "execute"}
 * @param promptText the prompt sent for this phase, or {@code null} if it was not captured
 * @param inputTokens the non-cached input tokens on the final result message
 * @param outputTokens the output tokens on the final result message
 * @param thinkingTokens the extended-thinking tokens, which are part of {@code outputTokens}; the
 *        parser uses the result's own count, else the sum over turns, else an estimate of one
 *        token per four characters of {@code thinkingBlocks}
 * @param cacheCreationInputTokens the tokens written to the prompt cache, on the final result
 *        message
 * @param cacheReadInputTokens the tokens read from the prompt cache, on the final result message
 * @param durationMs the wall-clock time of the call reported by the SDK, in milliseconds
 * @param apiDurationMs the time spent in API requests reported by the SDK, in milliseconds
 * @param totalCostUsd the total cost reported by the SDK, in US dollars, or 0 if none was reported
 * @param sessionId the Claude Code session ID, or {@code null} if no result message arrived
 * @param numTurns the number of turns reported by the SDK
 * @param isError whether the SDK reported the call as an error
 * @param textOutput the text of all assistant messages, joined in order
 * @param thinkingBlocks the text of each thinking block, in order
 * @param toolUses the tool calls the agent made, in order
 * @param rawResult the final result text, or {@code null} if there was none
 * @param toolResults the tool results sent back to the agent, or {@code null} for captures made
 *        before tool results were recorded
 * @param turns the usage of each turn (one per assistant message), or an empty list when the raw
 *        messages were not available (claude-code-sdk before 1.3.0, or a capture built in code)
 * @param modelCosts the cost of each model used, which adds up to {@code totalCostUsd}, or an empty
 *        list when the SDK did not report it
 * @param stopReason why the phase stopped; never {@code null}, because a missing reason becomes
 *        {@link StopReason#UNKNOWN}
 * @param maxTurns the turn limit the phase ran against, or -1 if none was reported
 */
public record PhaseCapture(
        String phaseName,
        String promptText,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        long durationMs,
        long apiDurationMs,
        double totalCostUsd,
        String sessionId,
        int numTurns,
        boolean isError,
        String textOutput,
        List<String> thinkingBlocks,
        List<ToolUseRecord> toolUses,
        String rawResult,
        List<ToolResultRecord> toolResults,
        List<TurnUsage> turns,
        List<ModelCost> modelCosts,
        StopReason stopReason,
        int maxTurns
) {

    /**
     * Creates a capture from all of its parts. A {@code null} {@code stopReason} becomes
     * {@link StopReason#UNKNOWN}. Each list is copied into an unmodifiable list, so later changes
     * to the lists passed in do not reach the capture; a {@code null} list stays {@code null}.
     */
    public PhaseCapture {
        stopReason = stopReason != null ? stopReason : StopReason.UNKNOWN;
        thinkingBlocks = copy(thinkingBlocks);
        toolUses = copy(toolUses);
        toolResults = copy(toolResults);
        turns = copy(turns);
        modelCosts = copy(modelCosts);
    }

    // Unlike List.copyOf, keeps null elements and a null list as they are
    private static <T> List<T> copy(List<T> list) {
        return list == null ? null : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * Creates a capture without a stop reason or turn limit, for code written before 1.9.0. The
     * stop reason becomes {@link StopReason#UNKNOWN} and {@code maxTurns} becomes -1.
     *
     * @param phaseName the caller's name for this phase
     * @param promptText the prompt, or {@code null}
     * @param inputTokens the non-cached input tokens
     * @param outputTokens the output tokens
     * @param thinkingTokens the thinking tokens
     * @param cacheCreationInputTokens the tokens written to the prompt cache
     * @param cacheReadInputTokens the tokens read from the prompt cache
     * @param durationMs the wall-clock time, in milliseconds
     * @param apiDurationMs the API time, in milliseconds
     * @param totalCostUsd the total cost, in US dollars
     * @param sessionId the session ID, or {@code null}
     * @param numTurns the number of turns
     * @param isError whether the SDK reported an error
     * @param textOutput the assistant text
     * @param thinkingBlocks the thinking blocks
     * @param toolUses the tool calls
     * @param rawResult the final result text, or {@code null}
     * @param toolResults the tool results, or {@code null}
     * @param turns the usage of each turn
     * @param modelCosts the cost of each model
     */
    public PhaseCapture(String phaseName, String promptText, int inputTokens, int outputTokens,
            int thinkingTokens, int cacheCreationInputTokens, int cacheReadInputTokens, long durationMs,
            long apiDurationMs, double totalCostUsd, String sessionId, int numTurns, boolean isError,
            String textOutput, List<String> thinkingBlocks, List<ToolUseRecord> toolUses, String rawResult,
            List<ToolResultRecord> toolResults, List<TurnUsage> turns, List<ModelCost> modelCosts) {
        this(phaseName, promptText, inputTokens, outputTokens, thinkingTokens, cacheCreationInputTokens,
                cacheReadInputTokens, durationMs, apiDurationMs, totalCostUsd, sessionId, numTurns, isError,
                textOutput, thinkingBlocks, toolUses, rawResult, toolResults, turns, modelCosts,
                StopReason.UNKNOWN, -1);
    }
    /**
     * Creates a capture without cache tokens, tool results, per-turn usage or per-model costs, for
     * older code. Cache tokens are 0, {@code toolResults} is {@code null}, {@code turns} and
     * {@code modelCosts} are empty, the stop reason is {@link StopReason#UNKNOWN} and
     * {@code maxTurns} is -1.
     *
     * @param phaseName the caller's name for this phase
     * @param promptText the prompt, or {@code null}
     * @param inputTokens the non-cached input tokens
     * @param outputTokens the output tokens
     * @param thinkingTokens the thinking tokens
     * @param durationMs the wall-clock time, in milliseconds
     * @param apiDurationMs the API time, in milliseconds
     * @param totalCostUsd the total cost, in US dollars
     * @param sessionId the session ID, or {@code null}
     * @param numTurns the number of turns
     * @param isError whether the SDK reported an error
     * @param textOutput the assistant text
     * @param thinkingBlocks the thinking blocks
     * @param toolUses the tool calls
     * @param rawResult the final result text, or {@code null}
     */
    public PhaseCapture(String phaseName, String promptText, int inputTokens, int outputTokens,
            int thinkingTokens, long durationMs, long apiDurationMs, double totalCostUsd,
            String sessionId, int numTurns, boolean isError, String textOutput,
            List<String> thinkingBlocks, List<ToolUseRecord> toolUses, String rawResult) {
        this(phaseName, promptText, inputTokens, outputTokens, thinkingTokens, 0, 0, durationMs,
                apiDurationMs, totalCostUsd, sessionId, numTurns, isError, textOutput,
                thinkingBlocks, toolUses, rawResult, null);
    }

    /**
     * Creates a capture without per-turn usage or per-model costs, for older code.
     * {@code turns} and {@code modelCosts} are empty, the stop reason is
     * {@link StopReason#UNKNOWN} and {@code maxTurns} is -1.
     *
     * @param phaseName the caller's name for this phase
     * @param promptText the prompt, or {@code null}
     * @param inputTokens the non-cached input tokens
     * @param outputTokens the output tokens
     * @param thinkingTokens the thinking tokens
     * @param cacheCreationInputTokens the tokens written to the prompt cache
     * @param cacheReadInputTokens the tokens read from the prompt cache
     * @param durationMs the wall-clock time, in milliseconds
     * @param apiDurationMs the API time, in milliseconds
     * @param totalCostUsd the total cost, in US dollars
     * @param sessionId the session ID, or {@code null}
     * @param numTurns the number of turns
     * @param isError whether the SDK reported an error
     * @param textOutput the assistant text
     * @param thinkingBlocks the thinking blocks
     * @param toolUses the tool calls
     * @param rawResult the final result text, or {@code null}
     * @param toolResults the tool results, or {@code null}
     */
    public PhaseCapture(String phaseName, String promptText, int inputTokens, int outputTokens,
            int thinkingTokens, int cacheCreationInputTokens, int cacheReadInputTokens, long durationMs,
            long apiDurationMs, double totalCostUsd, String sessionId, int numTurns, boolean isError,
            String textOutput, List<String> thinkingBlocks, List<ToolUseRecord> toolUses, String rawResult,
            List<ToolResultRecord> toolResults) {
        this(phaseName, promptText, inputTokens, outputTokens, thinkingTokens, cacheCreationInputTokens,
                cacheReadInputTokens, durationMs, apiDurationMs, totalCostUsd, sessionId, numTurns, isError,
                textOutput, thinkingBlocks, toolUses, rawResult, toolResults, List.of(), List.of());
    }

    /**
     * Returns all input tokens in the snapshot view: non-cached input plus cache writes and cache
     * reads.
     *
     * @return the sum of {@code inputTokens}, {@code cacheCreationInputTokens} and
     *         {@code cacheReadInputTokens}
     */
    public int totalInputTokens() {
        return inputTokens + cacheCreationInputTokens + cacheReadInputTokens;
    }

    /**
     * Returns {@link #totalInputTokens()} plus {@code outputTokens} and {@code thinkingTokens}.
     * This is a snapshot figure; for cost, use {@link #aggregateUsage()}.
     *
     * @return the sum of the snapshot input, output and thinking tokens
     */
    public int totalTokens() {
        return totalInputTokens() + outputTokens + thinkingTokens;
    }

    /**
     * Returns whether any thinking blocks were captured.
     *
     * @return {@code true} if {@code thinkingBlocks} is not {@code null} and not empty
     */
    public boolean hasThinking() {
        return thinkingBlocks != null && !thinkingBlocks.isEmpty();
    }

    /**
     * Returns whether the agent made any tool calls.
     *
     * @return {@code true} if {@code toolUses} is not {@code null} and not empty
     */
    public boolean hasToolUses() {
        return toolUses != null && !toolUses.isEmpty();
    }

    /**
     * Returns whether any tool results were captured.
     *
     * @return {@code true} if {@code toolResults} is not {@code null} and not empty
     */
    public boolean hasToolResults() {
        return toolResults != null && !toolResults.isEmpty();
    }

    /**
     * Returns whether per-turn usage was captured.
     *
     * @return {@code true} if {@code turns} is not {@code null} and not empty
     */
    public boolean hasTurns() {
        return turns != null && !turns.isEmpty();
    }

    /**
     * Returns whether per-model costs were captured.
     *
     * @return {@code true} if {@code modelCosts} is not {@code null} and not empty
     */
    public boolean hasModelCosts() {
        return modelCosts != null && !modelCosts.isEmpty();
    }

    /**
     * Splits {@code totalCostUsd} across the steps of this phase and returns one
     * {@link JournalStep} per step. Claude Code reports only a total cost, so each turn gets a
     * share in proportion to its output tokens, divided evenly among that turn's tool calls; the
     * shares add up to the total. The steps record this as
     * {@link io.github.markpollack.journal.trace.AttributionMethod#OUTPUT_TOKEN_PROPORTIONAL}.
     *
     * <p>No run, storage or clock is needed, so code that never records a run still gets the cost
     * of each step. The steps have a {@code null} run ID; a {@link RunRecorder} computes them again
     * with the real run ID.
     *
     * @return the steps of this phase with their share of the cost, in order
     */
    public List<JournalStep> stepCosts() {
        return JournalSteps.fromPhaseCapture(this, null);
    }

    /**
     * Returns the token usage to price: each token type (input, output, cache writes, cache reads)
     * added up over all {@link #turns()}. Every turn re-reads the cache and is billed for it, so
     * this sum, not the snapshot, is the one that matches what was billed.
     *
     * <p>The result carries {@code thinkingTokens} as captured, but thinking is billed as part of
     * output, so do not add it to a billed total.
     *
     * <p>If no per-turn usage was captured, returns the same values as {@link #snapshotUsage()}.
     *
     * @return the usage of this phase, added up by token type
     */
    public TokenUsage aggregateUsage() {
        if (turns == null || turns.isEmpty()) {
            return new TokenUsage(inputTokens, outputTokens, thinkingTokens,
                    cacheCreationInputTokens, cacheReadInputTokens, 0);
        }
        List<TokenUsage> perTurn = new ArrayList<>(turns.size());
        for (TurnUsage t : turns) {
            perTurn.add(new TokenUsage((int) t.inputTokens(), (int) t.outputTokens(), 0,
                    (int) t.cacheCreationInputTokens(), (int) t.cacheReadInputTokens(), 0));
        }
        TokenUsage summed = TokenUsage.sum(perTurn);
        // Carry the available run-level thinking (subset of output), recorded but not in the billed sum.
        return new TokenUsage(summed.inputTokens(), summed.outputTokens(), thinkingTokens,
                summed.cacheCreationTokens(), summed.cacheReadTokens(), summed.toolUseTokens());
    }

    /**
     * Returns the token usage reported on the final result message, including the cache fields.
     * This is a point-in-time view, not a billed total; for cost, use {@link #aggregateUsage()}.
     * The token accessors such as {@link #inputTokens()} return the same values.
     *
     * @return the usage on the final result message
     */
    public TokenUsage snapshotUsage() {
        return new TokenUsage(inputTokens, outputTokens, thinkingTokens,
                cacheCreationInputTokens, cacheReadInputTokens, 0);
    }

    /**
     * Returns the sum of the per-model costs in {@link #modelCosts()}. When the SDK reported
     * per-model costs, this equals {@code totalCostUsd} up to rounding.
     *
     * @return the summed cost in US dollars, or 0 if there are no per-model costs
     */
    public double modelCostSum() {
        if (modelCosts == null) {
            return 0.0;
        }
        return modelCosts.stream().mapToDouble(ModelCost::costUsd).sum();
    }

    /**
     * The largest difference, in US dollars, that {@link #reconcilesToModelCosts()} accepts
     * between the sum of the per-model costs and {@code totalCostUsd}. The value is 0.0001.
     */
    public static final double COST_RECONCILIATION_TOLERANCE_USD = 1e-4;

    /**
     * Returns whether the per-model costs add up to {@code totalCostUsd}, within
     * {@link #COST_RECONCILIATION_TOLERANCE_USD}. Use it to check that a capture is complete.
     *
     * <p>The check compares costs, not tokens. Per-turn token counts are not expected to add up to
     * the totals on the result message, because each turn re-reads a growing context; see
     * {@link TurnUsage}.
     *
     * @return {@code true} if the costs agree; {@code false} if they disagree or if no per-model
     *         costs were captured (use {@link #hasModelCosts()} to tell these apart)
     */
    public boolean reconcilesToModelCosts() {
        if (!hasModelCosts()) {
            return false;
        }
        return Math.abs(modelCostSum() - totalCostUsd) <= COST_RECONCILIATION_TOLERANCE_USD;
    }

    /**
     * Returns the thinking tokens added up over all turns, as the API reported them for each turn.
     * Unlike {@code thinkingTokens}, this is never an estimate. Thinking tokens are part of output
     * tokens, so do not add them to a billed total.
     *
     * @return the thinking tokens of all turns, or 0 if no per-turn usage was captured
     */
    public long thinkingTokensFromTurns() {
        if (turns == null) {
            return 0L;
        }
        return turns.stream().mapToLong(TurnUsage::thinkingTokens).sum();
    }

    /**
     * Returns whether this phase was cut off instead of finishing on its own: it hit its turn or
     * token limit, failed, or was cancelled. For such a phase, counts such as {@code numTurns} are
     * lower bounds, not measurements.
     *
     * @return {@code true} if the stop reason is one that {@link StopReason#isTruncatedRun()}
     *         treats as a cut-off
     */
    public boolean wasTruncated() {
        return stopReason != null && stopReason.isTruncatedRun();
    }
}
