package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.StopReason;

/**
 * Maps Claude Code's two stop vocabularies onto the vendor-neutral {@link StopReason}.
 *
 * <p>
 * Claude Code reports "why did this stop" in two different places, at two different scopes, and
 * they are not the same vocabulary:
 * <ul>
 *   <li><strong>Per turn</strong> — {@code message.stop_reason} on each assistant wire message
 *       ({@code end_turn}, {@code tool_use}, {@code max_tokens}, {@code stop_sequence},
 *       {@code refusal}). This is the Anthropic API's own field and it rides on the raw wire that
 *       {@code SessionLogParser} already reads for per-turn usage.</li>
 *   <li><strong>Per run</strong> — the terminal {@code ResultMessage.subtype}
 *       ({@code success}, {@code error_max_turns}, {@code error_during_execution}). This is the
 *       CLI's own harness-level outcome, and it is the only place the turn ceiling being hit is
 *       ever reported.</li>
 * </ul>
 *
 * <p>
 * The run-level subtype wins when both are present: a run whose final turn ended
 * {@code end_turn} but whose harness reports {@code error_max_turns} was cut off, and recording
 * it as a natural finish would be exactly the misreading this field exists to prevent.
 *
 * <p>
 * Unrecognized values map to {@link StopReason#UNKNOWN} rather than to a plausible-looking
 * guess. A vocabulary this class has not been taught must read as "not understood", never as
 * "finished normally".
 */
public final class ClaudeStopReasons {

    private ClaudeStopReasons() {
    }

    /**
     * Maps an assistant message's {@code message.stop_reason} onto the portable enum.
     *
     * @param wireStopReason the raw wire value, or null when absent
     * @return the normalized reason; {@link StopReason#UNKNOWN} for null or unrecognized input
     */
    public static StopReason fromTurnStopReason(String wireStopReason) {
        if (wireStopReason == null || wireStopReason.isBlank()) {
            return StopReason.UNKNOWN;
        }
        return switch (wireStopReason) {
            // A stop sequence is still the model choosing to stop: nothing was truncated.
            case "end_turn", "stop_sequence" -> StopReason.NATURAL_DONE;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            case "refusal" -> StopReason.REFUSAL;
            default -> StopReason.UNKNOWN;
        };
    }

    /**
     * Maps a terminal {@code ResultMessage.subtype} onto the portable enum.
     *
     * @param subtype the result subtype, or null when absent
     * @param isError the result's {@code is_error} flag, used only to classify an unrecognized
     *                subtype — a run flagged as errored is {@link StopReason#ERROR}, never
     *                {@link StopReason#UNKNOWN}
     * @return the normalized run-level reason
     */
    public static StopReason fromResultSubtype(String subtype, boolean isError) {
        if (subtype != null && !subtype.isBlank()) {
            switch (subtype) {
                case "success":
                    // is_error can still be set on a "success" subtype; trust the error flag.
                    return isError ? StopReason.ERROR : StopReason.NATURAL_DONE;
                case "error_max_turns":
                    return StopReason.MAX_TURNS;
                case "error_during_execution":
                    return StopReason.ERROR;
                default:
                    break;
            }
        }
        return isError ? StopReason.ERROR : StopReason.UNKNOWN;
    }

    /**
     * Resolves the run's stop reason from both scopes, preferring the run-level harness outcome
     * and falling back to the final turn's own stop reason.
     *
     * <p>
     * A trailing {@link StopReason#TOOL_USE} is <em>not</em> a run outcome — a run cannot end
     * waiting on a tool — so it degrades to {@link StopReason#UNKNOWN} rather than being
     * recorded as if the run stopped to call a tool.
     *
     * @param resultSubtype  the terminal result's subtype, or null when no result was seen
     * @param isError        the terminal result's error flag
     * @param sawResult      whether a terminal {@code ResultMessage} was observed at all
     * @param lastTurnReason the last assistant turn's wire {@code stop_reason}, or null
     * @return the run's normalized stop reason, never null
     */
    public static StopReason resolveRunStopReason(String resultSubtype, boolean isError, boolean sawResult,
            String lastTurnReason) {
        if (sawResult) {
            StopReason fromResult = fromResultSubtype(resultSubtype, isError);
            if (fromResult != StopReason.UNKNOWN) {
                return fromResult;
            }
        }
        StopReason fromTurn = fromTurnStopReason(lastTurnReason);
        return fromTurn == StopReason.TOOL_USE ? StopReason.UNKNOWN : fromTurn;
    }
}
