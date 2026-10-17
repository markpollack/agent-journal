package io.github.markpollack.journal.claude;

/**
 * One message of a Claude Code stream that is not an ordinary assistant or user message, kept
 * whole so that nothing the stream carried is lost at level 1: {@code system} messages
 * ({@code init}, task lifecycle, thinking tokens), the {@code result} line with the billed cost,
 * {@code modelUsage}, {@code permission_denials}, {@code terminal_reason} and
 * {@code subagent_stats}, and any type a later Claude Code adds.
 *
 * <p>The stream is not stored as a file of its own; the vendor's session files are the level-0
 * copy (see the session archive). The fields of assistant and user messages that the typed layer
 * does not model are not repeated here.
 *
 * @param messageNumber the message's position in the call's stream, counting from 1; every
 *        message counts, whether or not it is kept here
 * @param type the wire {@code type} (for example {@code system}, {@code result}), or, when the
 *        SDK gave no original text, the SDK's name for the message
 * @param subtype the wire {@code subtype}, or {@code null}
 * @param rawJson the message exactly as the SDK received it, or {@code null} when the SDK kept no
 *        original text for it
 * @param reason why {@code rawJson} is {@code null}, or {@code null} when it is not
 */
public record ClaudeVendorEvent(int messageNumber, String type, String subtype, String rawJson, String reason) {
}
