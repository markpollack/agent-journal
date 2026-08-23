package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One Grok ACP-shaped tool call, paired with its updates by {@code toolCallId}.
 *
 * @param id stable ACP tool-call identity
 * @param name Grok's concrete tool name
 * @param kind ACP's semantic tool kind, passed through without vendor remapping
 * @param input raw tool input
 * @param output final raw tool output, when present
 * @param status final ACP tool-call status
 * @param isError whether the final status is failed
 * @param errorMessage provider error detail, when present
 */
public record GrokToolUseRecord(
        String id,
        String name,
        ToolKind kind,
        Map<String, Object> input,
        Object output,
        String status,
        boolean isError,
        String errorMessage
) {

    public GrokToolUseRecord {
        kind = kind != null ? kind : ToolKind.OTHER;
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** ACP wire spelling retained for compatibility with the original string classification API. */
    public String classification() {
        return kind.wireValue();
    }
}
