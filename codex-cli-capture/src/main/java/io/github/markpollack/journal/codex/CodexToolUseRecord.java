package io.github.markpollack.journal.codex;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One Codex rollout tool call, paired to its output by {@code call_id}. */
public record CodexToolUseRecord(
        String id,
        String name,
        String rawName,
        Map<String, Object> input,
        Object output,
        boolean isError,
        String errorMessage
) {

    public CodexToolUseRecord {
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /** The input-derived semantic state name; never the rollout's uninformative outer name alone. */
    public String classification() {
        return name;
    }
}
