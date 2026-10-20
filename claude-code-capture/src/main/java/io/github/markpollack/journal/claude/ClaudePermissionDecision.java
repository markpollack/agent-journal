package io.github.markpollack.journal.claude;

/**
 * The permission decision Claude Code reported for one tool result ({@code tool_result_meta[].permission_decision}
 * on a user line of the stream, since CLI 2.1.299): an observation the vendor made, kept as such.
 *
 * @param messageNumber the stream line it arrived on
 * @param toolUseId the tool call it belongs to
 * @param decision the vendor's decision, such as {@code accept} or {@code reject}
 * @param source where the decision came from as the vendor reports it, such as {@code config} or {@code user}
 * @param reasonType the vendor's reason type, such as {@code rule}, or {@code null}
 * @param parentToolUseId the spawning tool call when the line belongs to a sub-agent, or {@code null}
 */
public record ClaudePermissionDecision(int messageNumber, String toolUseId, String decision, String source,
        String reasonType, String parentToolUseId) {
}
