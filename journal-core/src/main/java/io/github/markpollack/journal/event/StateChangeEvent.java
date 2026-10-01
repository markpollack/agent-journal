package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.Map;

/**
 * Records a move from one named state to another, with the reason, such as an agent moving from
 * its {@code "plan"} phase to its {@code "execute"} phase. Log one with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}, built with
 * {@link #of(String, String, String)}. Claude Code's run recorder logs one at the start of each
 * phase, from the previous phase's name ({@code "init"} for the first) to the new one, with the
 * reason {@code "phase transition"}; the other recorders do not log state changes. Read it back
 * with {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)};
 * {@link io.github.markpollack.journal.eval.EvalSubjectSources} turns it into a
 * {@code STATE_CHANGE} evaluation subject, with the old and new states as its input and output.
 *
 * <p>The states and the reason are free text chosen by the caller. The record accepts
 * {@code null} for any of them, and such an event can be logged and stored, but
 * {@link #toMap()} then throws {@link NullPointerException}. File storage writes it with
 * {@code @type} {@code "state_change"}. The record is immutable.
 *
 * @param timestamp when the state changed
 * @param fromState the previous state
 * @param toState the new state
 * @param reason why the state changed
 */
public record StateChangeEvent(
        Instant timestamp,
        String fromState,
        String toState,
        String reason
) implements JournalEvent {

    @Override
    public String type() {
        return "state_change";
    }

    /**
     * Creates a state change event with the current time.
     *
     * @param from the previous state
     * @param to the new state
     * @param reason why the state changed
     * @return the new event
     */
    public static StateChangeEvent of(String from, String to, String reason) {
        return new StateChangeEvent(Instant.now(), from, to, reason);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code type}, {@code timestamp}, {@code from}, {@code to} and
     * {@code reason}. The map is immutable.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    @Override
    public Map<String, Object> toMap() {
        return Map.of(
                "type", type(),
                "timestamp", timestamp.toString(),
                "from", fromState,
                "to", toState,
                "reason", reason
        );
    }
}
