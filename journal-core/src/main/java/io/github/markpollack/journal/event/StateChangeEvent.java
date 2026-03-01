package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.Map;

/**
 * Records a state transition.
 *
 * @param timestamp when the state change occurred
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

    /** Creates a state change event with current timestamp. */
    public static StateChangeEvent of(String from, String to, String reason) {
        return new StateChangeEvent(Instant.now(), from, to, reason);
    }

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
