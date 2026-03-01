package io.github.markpollack.journal.claude;

import java.util.Map;

/**
 * Captures a single tool use from the Claude SDK response.
 *
 * @param id    Tool use identifier
 * @param name  Tool name (e.g., "Read", "Write", "Bash")
 * @param input Tool input parameters
 */
public record ToolUseRecord(
        String id,
        String name,
        Map<String, Object> input
) {
}
