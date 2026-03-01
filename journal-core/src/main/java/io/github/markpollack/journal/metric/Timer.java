package io.github.markpollack.journal.metric;

import java.time.Duration;

/**
 * A timer metric for measuring durations.
 *
 * <p>Timers are useful for tracking:
 * <ul>
 *   <li>API call latencies</li>
 *   <li>Tool execution times</li>
 *   <li>Processing durations</li>
 * </ul>
 *
 * <p>Example using record():
 * <pre>{@code
 * Timer llmTimer = registry.timer("llm.latency", Tags.of("model", "claude-opus"));
 * llmTimer.record(Duration.ofMillis(2500));
 * }</pre>
 *
 * <p>Example using Sample:
 * <pre>{@code
 * Timer.Sample sample = timer.start();
 * // ... perform operation ...
 * Duration elapsed = sample.stop();
 * }</pre>
 */
public interface Timer {

    /**
     * Records a duration.
     *
     * @param duration the duration to record
     */
    void record(Duration duration);

    /**
     * Starts a timing sample.
     *
     * @return a sample that can be stopped to record the duration
     */
    Sample start();

    /**
     * A timing sample that can be stopped.
     */
    interface Sample {
        /**
         * Stops the sample and records the duration to the timer.
         *
         * @return the recorded duration
         */
        Duration stop();
    }
}
