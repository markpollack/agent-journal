/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.metric;

/**
 * A gauge metric that can be set to any value.
 *
 * <p>Gauges are useful for tracking current values like:
 * <ul>
 *   <li>Current memory usage</li>
 *   <li>Active connections</li>
 *   <li>Queue depth</li>
 *   <li>Cost accumulator</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * Gauge costGauge = registry.gauge("run.cost_usd", Tags.of("model", "claude-opus"));
 * costGauge.set(0.023);
 * }</pre>
 */
public interface Gauge {

    /**
     * Sets the gauge value.
     *
     * @param value the new value
     */
    void set(double value);

    /**
     * Returns the current value.
     *
     * @return the current gauge value
     */
    double value();
}
