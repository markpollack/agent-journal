package io.github.markpollack.journal.metric;

/**
 * Gives out the named {@link Counter}s, {@link Timer}s and {@link Gauge}s of one run, each
 * identified by a name and optional {@link Tags}. Get a run's registry from
 * {@link io.github.markpollack.journal.Run#metrics()} and update its metrics as the work goes on.
 * The metrics are kept in memory only: nothing in the registry is written to storage. To record a
 * value in the run's event log, call {@link io.github.markpollack.journal.Run#logMetric}, which
 * also sets the gauge of that name here.
 *
 * <p>Counters, timers and gauges are kept apart, so a counter and a gauge may share a name. The
 * registry a run returns, {@link InMemoryMetricRegistry}, is safe for use from several threads.
 *
 * <p>Implementations must return the same metric each time a method is called with the same name
 * and equal tags, and create it on first use. The interface does not require thread safety. The
 * one-argument methods have defaults that pass {@link Tags#empty()}.
 *
 * <p>Example:
 * <pre>{@code
 * MetricRegistry registry = run.metrics();
 *
 * registry.counter("tool.calls", Tags.of("tool", "bash")).increment();
 * Timer.Sample sample = registry.timer("llm.latency").start();
 * // ... call the model ...
 * sample.stop();
 * registry.gauge("context.tokens").set(41_216);
 * }</pre>
 */
public interface MetricRegistry {

    /**
     * Returns the counter with the given name and tags, creating it on first use.
     *
     * @param name the metric name, such as {@code "tool.calls"}
     * @param tags the tags that tell this counter apart from others of the same name; must not be
     *        {@code null}, use {@link Tags#empty()} for none
     * @return the counter
     */
    Counter counter(String name, Tags tags);

    /**
     * Returns the counter with the given name and no tags, creating it on first use.
     * The default calls {@link #counter(String, Tags)} with {@link Tags#empty()}.
     *
     * @param name the metric name
     * @return the counter
     */
    default Counter counter(String name) {
        return counter(name, Tags.empty());
    }

    /**
     * Returns the timer with the given name and tags, creating it on first use.
     *
     * @param name the metric name, such as {@code "llm.latency"}
     * @param tags the tags that tell this timer apart from others of the same name; must not be
     *        {@code null}, use {@link Tags#empty()} for none
     * @return the timer
     */
    Timer timer(String name, Tags tags);

    /**
     * Returns the timer with the given name and no tags, creating it on first use.
     * The default calls {@link #timer(String, Tags)} with {@link Tags#empty()}.
     *
     * @param name the metric name
     * @return the timer
     */
    default Timer timer(String name) {
        return timer(name, Tags.empty());
    }

    /**
     * Returns the gauge with the given name and tags, creating it on first use.
     *
     * @param name the metric name, such as {@code "context.tokens"}
     * @param tags the tags that tell this gauge apart from others of the same name; must not be
     *        {@code null}, use {@link Tags#empty()} for none
     * @return the gauge
     */
    Gauge gauge(String name, Tags tags);

    /**
     * Returns the gauge with the given name and no tags, creating it on first use.
     * The default calls {@link #gauge(String, Tags)} with {@link Tags#empty()}.
     *
     * @param name the metric name
     * @return the gauge
     */
    default Gauge gauge(String name) {
        return gauge(name, Tags.empty());
    }
}
