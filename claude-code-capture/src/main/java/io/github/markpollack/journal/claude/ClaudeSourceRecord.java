package io.github.markpollack.journal.claude;

/**
 * One assistant or user line of Claude Code's stream, kept whole at its source occurrence (option C, conservative
 * retention): the typed events take what they model from the line, and the line itself stays in level 1 so every
 * field the typed layer does not model (ids, request ids, per-message usage, timestamps, thinking display, structured
 * tool results, and anything a later CLI adds) is preserved and addressable. Lines that are not assistant or user
 * messages are {@link ClaudeVendorEvent}s; the two share one occurrence counter, {@code messageNumber}.
 *
 * @param messageNumber the line's position in the stream, counting from 1 over every message the SDK handed over
 * @param type the line's {@code type}: {@code assistant} or {@code user}
 * @param parentToolUseId the spawning tool call when the line belongs to a sub-agent, or {@code null}
 * @param rawJson the line as the SDK gave it, or {@code null} when the SDK kept no original text
 * @param reason why {@code rawJson} is {@code null}, or {@code null}
 */
public record ClaudeSourceRecord(int messageNumber, String type, String parentToolUseId, String rawJson, String reason) {
}
