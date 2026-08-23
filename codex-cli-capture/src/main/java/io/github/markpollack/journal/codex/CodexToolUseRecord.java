package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.event.ToolKind;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One Codex rollout tool call, paired to its output by {@code call_id}. */
public record CodexToolUseRecord(
        String id,
        ToolKind kind,
        String name,
        Map<String, Object> input,
        Object output,
        boolean isError,
        String errorMessage
) {

    public CodexToolUseRecord {
        kind = kind != null ? kind : ToolKind.OTHER;
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** Compatibility accessor for the rollout's raw outer name. */
    public String rawName() {
        return name;
    }

    /** ACP wire spelling of the input-derived semantic category. */
    public String classification() {
        return kind.wireValue();
    }
}
