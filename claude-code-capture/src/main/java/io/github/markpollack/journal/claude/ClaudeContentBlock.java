package io.github.markpollack.journal.claude;

/**
 * One content block of an assistant message, in the order the stream carried it, so the sequence of text, thinking
 * and tool use within and across messages is recoverable from level 1. Text and thinking carry their content here;
 * a tool use is listed for its position only (its input and result are on the {@code tool_call} event with the same
 * id).
 *
 * @param messageNumber the stream line the block arrived on (see {@link ClaudeSourceRecord})
 * @param turnId the assistant message id the block belongs to, or {@code null} if the line carried none
 * @param turnIndex the ordinal of that message within its track
 * @param blockIndex the block's position within the line's {@code message.content} array
 * @param kind {@code text}, {@code thinking}, {@code tool_use}, or the block's own type for any other kind
 * @param text the block's text or thinking text (may be empty), or {@code null} for a tool use
 * @param signature the thinking block's opaque signature, verbatim, or {@code null}
 * @param toolUseId the tool use id for a {@code tool_use} block, or {@code null}
 * @param parentToolUseId the spawning tool call when the block belongs to a sub-agent, or {@code null}
 */
public record ClaudeContentBlock(int messageNumber, String turnId, int turnIndex, int blockIndex, String kind,
        String text, String signature, String toolUseId, String parentToolUseId) {
}
