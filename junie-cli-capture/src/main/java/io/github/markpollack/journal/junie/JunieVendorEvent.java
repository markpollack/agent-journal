package io.github.markpollack.journal.junie;

import java.util.Map;

/**
 * One line of a Junie {@code events.jsonl} that the parser does not turn into a field of the
 * capture, kept whole so that nothing Junie recorded about the session is lost at level 1: status
 * narration, current-directory and environment-variable updates, plan updates, failure messages,
 * tips, history commits, the task-failed and cost-trajectory events, and any kind a later Junie
 * version adds.
 *
 * <p>The line's other fields, on the kinds the parser does consume (for example the agent identity
 * on every event, or the attachments of a user prompt), are not repeated here; they are in the
 * level-0 session archive.
 *
 * @param kind the event kind: the inner {@code agentEvent.kind} of a {@code SessionA2uxEvent}, or
 *        the line's own {@code kind}; {@code "unknown"} if the line has none
 * @param timestampMs the line's {@code timestampMs}, or -1 if it has none
 * @param taskId the line's {@code taskId}, or {@code null}
 * @param payload the whole line as parsed JSON (maps, lists, strings, numbers, booleans)
 */
public record JunieVendorEvent(String kind, long timestampMs, String taskId, Map<String, Object> payload) {

    public JunieVendorEvent {
        payload = payload == null ? Map.of() : payload;
    }
}
