package io.github.markpollack.journal.claude;

/**
 * Captures a single tool result from a Claude SDK response (carried in UserMessage).
 *
 * @param toolUseId the tool use ID this result corresponds to
 * @param content   the result content (string representation)
 * @param isError   whether the tool execution reported an error
 */
public record ToolResultRecord(
		String toolUseId,
		String content,
		boolean isError
) {
}
