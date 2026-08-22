package io.github.markpollack.journal.antigravity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One Antigravity tool step, paired across ACTIVE and terminal updates by step index. */
public record AntigravityToolUseRecord(
        String id,
        int stepIndex,
        String name,
        Map<String, Object> input,
        Object output,
        long durationMs,
        String state,
        boolean isError,
        String errorMessage
) {

    public AntigravityToolUseRecord {
        input = input == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    public String classification() {
        return name;
    }
}
