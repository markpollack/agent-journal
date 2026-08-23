package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Map;

/**
 * Captures a single tool use from the Claude SDK response.
 *
 * @param id    Tool use identifier
 * @param kind  canonical ACP-aligned tool category
 * @param name  raw Claude tool name (e.g., "Read", "Write", "Bash")
 * @param input Tool input parameters
 */
public record ToolUseRecord(
        String id,
        ToolKind kind,
        String name,
        Map<String, Object> input
) {

    /** Back-compatible constructor that classifies the raw Claude tool name. */
    public ToolUseRecord(String id, String name, Map<String, Object> input) {
        this(id, ClaudeToolClassifier.classify(name), name, input);
    }
}
