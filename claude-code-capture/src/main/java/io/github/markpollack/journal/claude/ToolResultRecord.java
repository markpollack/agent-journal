/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

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
