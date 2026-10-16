package io.github.markpollack.journal.antigravity;

import java.util.Map;

/**
 * One line of the Antigravity CLI's {@code stream-json} output that is kept whole at level 1,
 * because the capture's fields do not hold everything it says: the {@code init} event (working
 * directory, permission mode, the tools on offer, the model), every {@code user_input} and
 * {@code agent_response} step update (the text the agent streamed in {@code text_delta}, the
 * per-call usage including {@code total_tokens}), the {@code result} event, any other step type and
 * any event type a later CLI version adds. Tool step updates are not kept here: the tool record
 * holds all of their fields.
 *
 * @param kind the event type, with the step type after a slash for step updates, such as
 *        {@code "init"} or {@code "step_update/agent_response"}
 * @param line the line's number in the stream, starting at 1
 * @param payload the whole line as parsed JSON (maps, lists, strings, numbers, booleans)
 */
public record AntigravityVendorEvent(String kind, int line, Map<String, Object> payload) {

    public AntigravityVendorEvent {
        payload = payload == null ? Map.of() : payload;
    }
}
