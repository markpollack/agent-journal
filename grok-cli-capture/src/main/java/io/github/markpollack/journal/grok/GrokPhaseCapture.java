package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.event.TokenUsage;

import java.util.List;

/**
 * The parsed record of one Grok CLI call: what the agent wrote, thought and did, the tokens it
 * used, what it cost, and why it stopped. {@link GrokSessionParser} builds one from the CLI's
 * {@code streaming-json} output. Pass it to a {@link GrokRunRecorder} to log it as events on a
 * {@link io.github.markpollack.journal.Run}, or read its fields when you only need usage and cost.
 *
 * <p>It is the Grok counterpart of Claude Code's {@code PhaseCapture}, with less detail. Grok
 * reports a real total cost, but no per-turn usage, no timing and no turn limit. The token counts
 * cover the whole call: the parser takes them from the CLI's final {@code end} line, which adds up
 * every turn. The stop reason is Grok's own string, not a
 * {@link io.github.markpollack.journal.event.StopReason}, and the thinking text is one string, not
 * a list of blocks.
 *
 * <p>The record copies {@code toolUses} into an unmodifiable list, so a capture can be shared
 * between threads.
 *
 * @param phaseName the caller's name for this phase, such as {@code "plan"} or {@code "execute"}
 * @param promptText the prompt sent for this phase, or {@code null} if it was not captured
 * @param model the model in the {@code end} line's {@code modelUsage} that used the most tokens
 *        (input, output and cache), the first named on a tie; {@code null} if none was named. The
 *        other models of a call that used several are not kept
 * @param inputTokens the input tokens, not counting cache reads; {@link #NOT_REPORTED} (-1) if Grok
 *        gave no figure
 * @param outputTokens the output tokens; {@link #NOT_REPORTED} if Grok gave no figure
 * @param thinkingTokens the reasoning tokens Grok reported; {@link #NOT_REPORTED} if it gave none
 * @param cacheCreationInputTokens the tokens written to the prompt cache; {@link #NOT_REPORTED} if
 *        Grok gave no figure
 * @param cacheReadInputTokens the tokens read from the prompt cache; {@link #NOT_REPORTED} if Grok
 *        gave no figure
 * @param totalCostUsd the total cost Grok reported, in US dollars; 0 when {@code costReported} is
 *        false, which then means "not reported", not "free"
 * @param costReported whether the {@code end} line carried {@code total_cost_usd}; false for a
 *        stream cut off before its {@code end} line and for an {@code end} line without a cost
 * @param sessionId the Grok session ID, or {@code null} if no {@code end} line arrived
 * @param numTurns the number of turns Grok reported, or 0 if none was reported
 * @param isError whether the call ended in error; the parser sets it when the stop reason is
 *        {@code error} or {@code cancelled}, ignoring case, or when the output has no {@code end}
 *        line
 * @param stopReason Grok's stop reason, such as {@code "end_turn"}; {@code "incomplete"} when the
 *        parser found no {@code end} line; or {@code null} if the {@code end} line reported none
 * @param textOutput the agent's text, joined in order
 * @param thinkingOutput the agent's thinking text, joined in order with nothing added between
 *        pieces, so the thinking of different turns runs together
 * @param toolUses the tool calls, in the order they first appeared; never {@code null}
 * @param vendorEvents the lines of the output that are not turned into fields above, whole and in order; see
 *        {@link GrokVendorEvent}; never {@code null}
 */
public record GrokPhaseCapture(
        String phaseName,
        String promptText,
        String model,
        int inputTokens,
        int outputTokens,
        int thinkingTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        double totalCostUsd,
        boolean costReported,
        String sessionId,
        int numTurns,
        boolean isError,
        String stopReason,
        String textOutput,
        String thinkingOutput,
        List<GrokToolUseRecord> toolUses,
        List<GrokVendorEvent> vendorEvents
) {

    /**
     * Creates a capture from all of its parts. A {@code null} {@code toolUses} becomes an empty
     * list; any other list is copied.
     */
    public GrokPhaseCapture {
        toolUses = toolUses == null ? List.of() : List.copyOf(toolUses);
        vendorEvents = vendorEvents == null ? List.of() : List.copyOf(vendorEvents);
    }

    /**
     * The value of a token count that Grok did not report. The token usage built by
     * {@link #tokenUsage()} shows 0 for such a count until the event model can hold a null, so a
     * 0 there can mean "none" or "not reported"; {@link #tokensNotReported()} tells them apart.
     */
    public static final int NOT_REPORTED = -1;

    /**
     * Names the token counts that Grok did not report, in the order {@code input}, {@code output},
     * {@code thinking}, {@code cacheCreation}, {@code cacheRead}.
     *
     * @return the names, empty if every count was reported; never {@code null}
     */
    public List<String> tokensNotReported() {
        List<String> names = new java.util.ArrayList<>(5);
        if (inputTokens < 0) names.add("input");
        if (outputTokens < 0) names.add("output");
        if (thinkingTokens < 0) names.add("thinking");
        if (cacheCreationInputTokens < 0) names.add("cacheCreation");
        if (cacheReadInputTokens < 0) names.add("cacheRead");
        return List.copyOf(names);
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
     * Returns whether the agent wrote any text.
     *
     * @return {@code true} if {@code textOutput} is not {@code null} and not empty
     */
    public boolean hasOutput() {
        return textOutput != null && !textOutput.isEmpty();
    }

    /**
     * Returns the token counts as a {@link TokenUsage}. Because Grok's counts cover the whole call,
     * this usage can be priced directly, unlike the final-message snapshot of Claude Code's
     * {@code PhaseCapture}.
     *
     * @return the usage of this call; a count Grok did not report shows 0 (see
     *         {@link #tokensNotReported()}); its tool-use token count is 0
     */
    public TokenUsage tokenUsage() {
        return new TokenUsage(Math.max(inputTokens, 0), Math.max(outputTokens, 0), Math.max(thinkingTokens, 0),
                Math.max(cacheCreationInputTokens, 0), Math.max(cacheReadInputTokens, 0), 0);
    }
}
