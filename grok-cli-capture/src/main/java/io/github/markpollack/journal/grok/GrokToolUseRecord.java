package io.github.markpollack.journal.grok;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Grok ACP-shaped tool call, paired with its updates by {@code toolCallId}.
 *
 * @param id stable ACP tool-call identity
 * @param name Grok's concrete tool name
 * @param kind ACP's semantic tool kind (read, edit, execute, and so on)
 * @param input raw tool input
 * @param output final raw tool output, when present
 * @param status final ACP tool-call status
 * @param isError whether the final status is failed
 * @param errorMessage provider error detail, when present
 */
public record GrokToolUseRecord(
        String id,
        String name,
        String kind,
        Map<String, Object> input,
        Object output,
        String status,
        boolean isError,
        String errorMessage
) {

    public GrokToolUseRecord {
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** Semantic classification supplied by ACP, falling back to the concrete tool name. */
    public String classification() {
        return kind != null && !kind.isBlank() ? kind : name;
    }
}
