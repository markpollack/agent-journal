package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Map;

/**
 * Captures a single tool use from the Claude SDK response.
 *
 * <p>
 * {@code turnId} and {@code turnIndex} (1.9.0) tie the call back to the assistant turn that
 * issued it. Without them a tool call cannot be placed in the trajectory or joined to the token
 * vector that paid for it — the v1 capture carried {@code phase_turns}, v3/v4 dropped it, and the
 * dwell-time half of the semi-Markov question went with it.
 *
 * @param id        Tool use identifier
 * @param kind      canonical ACP-aligned tool category
 * @param name      raw Claude tool name (e.g., "Read", "Write", "Bash")
 * @param input     Tool input parameters
 * @param turnId    the assistant message id ({@code msg_…}) whose turn issued this call, or null
 *                  when unknown (no raw wire available)
 * @param turnIndex 0-based ordinal of that turn within the capture, or -1 when unknown. -1 rather
 *                  than 0 so "never captured" is never mistaken for "the first turn".
 */
public record ToolUseRecord(
        String id,
        ToolKind kind,
        String name,
        Map<String, Object> input,
        String turnId,
        int turnIndex
) {

    /** Back-compatible constructor that classifies the raw Claude tool name. */
    public ToolUseRecord(String id, String name, Map<String, Object> input) {
        this(id, ClaudeToolClassifier.classify(name), name, input);
    }

    /** Back-compat constructor for callers written before turn linkage was captured (1.9.0). */
    public ToolUseRecord(String id, ToolKind kind, String name, Map<String, Object> input) {
        this(id, kind, name, input, null, -1);
    }
}
