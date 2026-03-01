package io.github.markpollack.journal.metric;

/**
 * Registry for creating and accessing metrics.
 *
 * <p>Provides factory methods for creating counters, timers, and gauges.
 * Implementations should cache and return the same metric instance for
 * the same name and tags combination.
 *
 * <p>Example:
 * <pre>{@code
 * MetricRegistry registry = new InMemoryMetricRegistry();
 *
 * Counter tokens = registry.counter("tokens.total", Tags.of("model", "claude-opus"));
 * Timer latency = registry.timer("llm.latency");
 * Gauge cost = registry.gauge("run.cost_usd");
 * }</pre>
 */
public interface MetricRegistry {

    /**
     * Returns a counter with the given name and tags.
     *
     * @param name the metric name
     * @param tags the metric tags
     * @return a counter instance
     */
    Counter counter(String name, Tags tags);

    /**
     * Returns a counter with the given name (no tags).
     *
     * @param name the metric name
     * @return a counter instance
     */
    default Counter counter(String name) {
        return counter(name, Tags.empty());
    }

    /**
     * Returns a timer with the given name and tags.
     *
     * @param name the metric name
     * @param tags the metric tags
     * @return a timer instance
     */
    Timer timer(String name, Tags tags);

    /**
     * Returns a timer with the given name (no tags).
     *
     * @param name the metric name
     * @return a timer instance
     */
    default Timer timer(String name) {
        return timer(name, Tags.empty());
    }

    /**
     * Returns a gauge with the given name and tags.
     *
     * @param name the metric name
     * @param tags the metric tags
     * @return a gauge instance
     */
    Gauge gauge(String name, Tags tags);

    /**
     * Returns a gauge with the given name (no tags).
     *
     * @param name the metric name
     * @return a gauge instance
     */
    default Gauge gauge(String name) {
        return gauge(name, Tags.empty());
    }
}
