package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.event.StopReason;

import java.util.Locale;

/**
 * Normalises Junie's terminal signals onto the vendor-neutral {@link StopReason}.
 *
 * <p>
 * Junie reports its outcome differently depending on how it was driven, and this adapter reads
 * both:
 * <ul>
 *   <li><strong>ACP</strong> ({@code junie --acp true}) emits a top-level
 *       {@code TaskState} line — {@code {"kind":"TaskState","state":"COMPLETED"}}.</li>
 *   <li><strong>Plain CLI</strong> ({@code --output-format json}) emits no {@code TaskState} at
 *       all; the outcome has to come from the {@code ResultBlockUpdatedEvent}'s
 *       {@code cancelled} flag and {@code errorCode}.</li>
 * </ul>
 * Both fixtures agree on {@code errorCode = "Submit"} for a run that finished by handing its
 * result back, which is the only terminal code observed so far.
 *
 * <p>
 * Anything unrecognised becomes {@link StopReason#UNKNOWN}. Junie enforces no turn ceiling and
 * reports none, so {@code maxTurns} is always -1 ("not reported") — recorded rather than omitted,
 * per the 1.9.0 rule that a stop reason and the ceiling it ran against travel together.
 */
final class JunieStopReasons {

    /** The {@code errorCode} Junie sets when the agent submitted a result and finished. */
    static final String SUBMIT = "Submit";

    private JunieStopReasons() {
    }

    static StopReason from(String taskState, String errorCode, boolean cancelled, boolean sawResult) {
        if (cancelled) {
            return StopReason.CANCELLED;
        }
        if (taskState != null) {
            return switch (taskState.toUpperCase(Locale.ROOT)) {
                case "COMPLETED" -> StopReason.NATURAL_DONE;
                case "FAILED", "ERROR" -> StopReason.ERROR;
                case "CANCELLED", "CANCELED" -> StopReason.CANCELLED;
                default -> StopReason.UNKNOWN;
            };
        }
        // No TaskState line: the plain-CLI path. A submitted result is the natural finish.
        if (sawResult && SUBMIT.equalsIgnoreCase(errorCode)) {
            return StopReason.NATURAL_DONE;
        }
        return StopReason.UNKNOWN;
    }
}
