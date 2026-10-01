package io.github.markpollack.journal.event;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How long an LLM call took, in milliseconds: the total wall-clock time, the part spent in the
 * model API, and the time until the first token arrived. It is part of an {@link LLMCallEvent}.
 * The run recorders fill it from the durations the agent reports. Claude Code's recorder records
 * the total and the API time; the Codex, Antigravity and Junie recorders record only the total;
 * the Gemini recorder records the total with an API time of 0; and the Grok recorder records no
 * timing.
 *
 * <p>{@link #of(long)} sets the API time equal to the total, so {@link #overheadRatio()} is 0 when
 * only the total is known. A time to first token of 0 means it was not measured; no recorder sets
 * it. The values are not checked. The record is immutable.
 *
 * @param totalDurationMs the total wall-clock time
 * @param apiDurationMs the time spent in model API calls; equal to the total when only the total
 *        is known
 * @param timeToFirstTokenMs the time until the first token arrived, or 0 if not measured
 */
public record TimingInfo(
        long totalDurationMs,
        long apiDurationMs,
        long timeToFirstTokenMs
) {
    /**
     * Creates a timing from the total duration alone. The API time is set to the same value, and
     * the time to first token to 0.
     *
     * @param totalMs the total duration in milliseconds
     * @return the new timing
     */
    public static TimingInfo of(long totalMs) {
        return new TimingInfo(totalMs, totalMs, 0);
    }

    /**
     * Creates a timing from the total and API durations; the time to first token is 0.
     *
     * @param totalMs the total duration in milliseconds
     * @param apiMs the time spent in model API calls, in milliseconds
     * @return the new timing
     */
    public static TimingInfo of(long totalMs, long apiMs) {
        return new TimingInfo(totalMs, apiMs, 0);
    }

    /**
     * Creates a timing with all three durations.
     *
     * @param totalMs the total duration in milliseconds
     * @param apiMs the time spent in model API calls, in milliseconds
     * @param ttftMs the time until the first token arrived, in milliseconds
     * @return the new timing
     */
    public static TimingInfo of(long totalMs, long apiMs, long ttftMs) {
        return new TimingInfo(totalMs, apiMs, ttftMs);
    }

    /**
     * Returns the share of the total time not spent in the model API: 1 minus the API time
     * divided by the total. It is 0 when the two are equal, 1 when the API time is 0, and
     * negative when the API time is longer than the total.
     *
     * @return the overhead ratio, or 0 if the total is 0
     */
    public double overheadRatio() {
        if (totalDurationMs == 0) return 0.0;
        return 1.0 - ((double) apiDurationMs / totalDurationMs);
    }

    /**
     * Returns the durations as a map with snake_case keys: {@code total_duration_ms} always,
     * {@code api_duration_ms} when it differs from the total, and {@code time_to_first_token_ms}
     * when it is above 0. {@link LLMCallEvent#toMap()} nests this map under {@code timing}. File
     * storage does not use it.
     *
     * @return a new map of the durations
     */
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("total_duration_ms", totalDurationMs);
        if (apiDurationMs != totalDurationMs) map.put("api_duration_ms", apiDurationMs);
        if (timeToFirstTokenMs > 0) map.put("time_to_first_token_ms", timeToFirstTokenMs);
        return map;
    }
}
