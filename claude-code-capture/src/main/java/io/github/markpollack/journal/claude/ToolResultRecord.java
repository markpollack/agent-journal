package io.github.markpollack.journal.claude;

/**
 * Captures a single tool result from a Claude SDK response (carried in UserMessage).
 *
 * @param toolUseId  the tool use ID this result corresponds to
 * @param content    the result content (string representation)
 * @param isError    whether the tool execution reported an error
 * @param durationMs interval in milliseconds between the tool call being issued and this result
 *                   arriving, or -1 when it was not measured.
 *                   <p>
 *                   <strong>Measurement caveat — read before using this as a tool latency.</strong>
 *                   The Claude Code SDK stream carries no tool timing of its own, so this is the
 *                   <em>observed</em> interval between the {@code tool_use} block and its matching
 *                   {@code tool_result} block arriving on the stream. For a live capture that is
 *                   the tool's wall-clock execution time as seen by the consumer, including stream
 *                   latency. For a replay of an already-recorded stream it measures how fast the
 *                   replay ran, which is not a tool duration at all — such a capture records -1
 *                   only if no start was observed, so a replayed run yields near-zero durations
 *                   rather than an explicit "unknown". Treat near-zero durations across an entire
 *                   run as a replay signature, not as instant tools. This is the same quantity v1
 *                   recorded as {@code phase_duration_ms}; v3/v4 computed it, logged it, and threw
 *                   it away.
 */
public record ToolResultRecord(
		String toolUseId,
		String content,
		boolean isError,
		long durationMs
) {

	/** Back-compat constructor for captures written before step duration was persisted (1.9.0). */
	public ToolResultRecord(String toolUseId, String content, boolean isError) {
		this(toolUseId, content, isError, -1L);
	}

	/** Whether a duration was actually observed for this step. */
	public boolean hasDuration() {
		return durationMs >= 0;
	}
}
