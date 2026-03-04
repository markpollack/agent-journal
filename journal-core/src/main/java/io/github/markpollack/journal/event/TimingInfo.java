/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Timing breakdown for LLM calls.
 *
 * @param totalDurationMs total wall-clock time
 * @param apiDurationMs time spent in API call (network + inference)
 * @param timeToFirstTokenMs time to first token (streaming)
 */
public record TimingInfo(
        long totalDurationMs,
        long apiDurationMs,
        long timeToFirstTokenMs
) {
    /** Creates timing info from total duration. */
    public static TimingInfo of(long totalMs) {
        return new TimingInfo(totalMs, totalMs, 0);
    }

    /** Creates timing info with total and API duration. */
    public static TimingInfo of(long totalMs, long apiMs) {
        return new TimingInfo(totalMs, apiMs, 0);
    }

    /** Creates timing info with all durations. */
    public static TimingInfo of(long totalMs, long apiMs, long ttftMs) {
        return new TimingInfo(totalMs, apiMs, ttftMs);
    }

    /** Overhead ratio (non-API time / total time). */
    public double overheadRatio() {
        if (totalDurationMs == 0) return 0.0;
        return 1.0 - ((double) apiDurationMs / totalDurationMs);
    }

    /** Converts to map for serialization. */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("total_duration_ms", totalDurationMs);
        if (apiDurationMs != totalDurationMs) map.put("api_duration_ms", apiDurationMs);
        if (timeToFirstTokenMs > 0) map.put("time_to_first_token_ms", timeToFirstTokenMs);
        return map;
    }
}
