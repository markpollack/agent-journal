package io.github.markpollack.journal.codex;

import java.util.Map;

/**
 * One record of a Codex rollout that the parser does not turn into a field of the capture, kept
 * whole so that nothing Codex recorded about the session is lost at level 1: reasoning records,
 * agent and user messages, rate-limit and other event messages, task-start records, turn-abort
 * details, and any record type a later Codex version adds.
 *
 * <p>A record the parser does consume (for example the tool calls and their outputs, or the token
 * counts) is not repeated here, and neither are the fields of a consumed record that the capture
 * has no place for (for example the run-mode fields of {@code turn_context}); those are in the
 * level-0 session archive. In a sub-agent that was forked from its parent, the records replayed
 * from the parent's history are not repeated either; they are counted in
 * {@link CodexSubagentCapture#replayedRecordsSkipped()} and are in the parent's own rollout.
 *
 * @param envelopeType the record's top-level {@code type}, such as {@code response_item},
 *        {@code event_msg}; {@code "unknown"} if it has none
 * @param kind the record's {@code payload.type}, or the envelope type if the payload has none
 * @param ordinal the record's {@code ordinal} in its rollout, or -1 if it has none
 * @param timestamp the record's {@code timestamp} as Codex wrote it, or {@code null}
 * @param payload the whole record as parsed JSON (maps, lists, strings, numbers, booleans)
 */
public record CodexVendorEvent(String envelopeType, String kind, long ordinal, String timestamp,
        Map<String, Object> payload) {

    public CodexVendorEvent {
        envelopeType = envelopeType == null || envelopeType.isBlank() ? "unknown" : envelopeType;
        kind = kind == null || kind.isBlank() ? envelopeType : kind;
        payload = payload == null ? Map.of() : payload;
    }
}
