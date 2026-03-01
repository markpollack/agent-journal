package io.github.markpollack.journal.metric;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory implementation of {@link MetricRegistry}.
 *
 * <p>Captures all metrics in memory for later analysis. Useful for:
 * <ul>
 *   <li>Testing</li>
 *   <li>Run comparison</li>
 *   <li>JSON/CSV export</li>
 * </ul>
 *
 * <p>Thread-safe: all operations are safe for concurrent access.
 *
 * <p>Example:
 * <pre>{@code
 * InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
 *
 * registry.counter("tokens.total").increment(1500);
 * registry.timer("llm.latency").record(Duration.ofMillis(2500));
 * registry.gauge("cost.usd").set(0.023);
 *
 * // Get snapshot for export
 * MetricSnapshot snapshot = registry.snapshot();
 * }</pre>
 */
public class InMemoryMetricRegistry implements MetricRegistry {

    private final Map<String, InMemoryCounter> counters = new ConcurrentHashMap<>();
    private final Map<String, InMemoryTimer> timers = new ConcurrentHashMap<>();
    private final Map<String, InMemoryGauge> gauges = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name, Tags tags) {
        String key = metricKey(name, tags);
        return counters.computeIfAbsent(key, k -> new InMemoryCounter(name, tags));
    }

    @Override
    public Timer timer(String name, Tags tags) {
        String key = metricKey(name, tags);
        return timers.computeIfAbsent(key, k -> new InMemoryTimer(name, tags));
    }

    @Override
    public Gauge gauge(String name, Tags tags) {
        String key = metricKey(name, tags);
        return gauges.computeIfAbsent(key, k -> new InMemoryGauge(name, tags));
    }

    /**
     * Returns a snapshot of all metrics for export.
     * <p>
     * Note: If multiple metrics have the same name but different tags,
     * only the last one will appear in the snapshot by name. Use the
     * internal key (name + tags) for full differentiation.
     *
     * @return the current metric snapshot
     */
    public MetricSnapshot snapshot() {
        Map<String, CounterSnapshot> counterSnapshots = new LinkedHashMap<>();
        counters.forEach((key, counter) ->
                counterSnapshots.put(counter.name, new CounterSnapshot(counter.name, counter.tags, counter.count())));

        Map<String, TimerSnapshot> timerSnapshots = new LinkedHashMap<>();
        timers.forEach((key, timer) ->
                timerSnapshots.put(timer.name, new TimerSnapshot(
                        timer.name, timer.tags, timer.count.get(), timer.totalNanos.get(), timer.maxNanos.get())));

        Map<String, GaugeSnapshot> gaugeSnapshots = new LinkedHashMap<>();
        gauges.forEach((key, gauge) ->
                gaugeSnapshots.put(gauge.name, new GaugeSnapshot(gauge.name, gauge.tags, gauge.value())));

        return new MetricSnapshot(counterSnapshots, timerSnapshots, gaugeSnapshots);
    }

    /**
     * Clears all recorded metrics.
     */
    public void reset() {
        counters.clear();
        timers.clear();
        gauges.clear();
    }

    private String metricKey(String name, Tags tags) {
        return name + tags.toString();
    }

    // Snapshot records

    /** Snapshot of a counter metric. */
    public record CounterSnapshot(String name, Tags tags, long count) {}

    /** Snapshot of a timer metric. */
    public record TimerSnapshot(String name, Tags tags, long count, long totalNanos, long maxNanos) {
        /** Returns total recorded duration. */
        public Duration total() {
            return Duration.ofNanos(totalNanos);
        }

        /** Returns maximum recorded duration. */
        public Duration max() {
            return Duration.ofNanos(maxNanos);
        }

        /** Returns average recorded duration. */
        public Duration average() {
            return count > 0 ? Duration.ofNanos(totalNanos / count) : Duration.ZERO;
        }
    }

    /** Snapshot of a gauge metric. */
    public record GaugeSnapshot(String name, Tags tags, double value) {}

    /** Snapshot of all metrics at a point in time. */
    public record MetricSnapshot(
            Map<String, CounterSnapshot> counters,
            Map<String, TimerSnapshot> timers,
            Map<String, GaugeSnapshot> gauges
    ) {
        /**
         * Returns counter value by name.
         *
         * @param name the counter name
         * @return the count, or 0 if not found
         */
        public long counterValue(String name) {
            CounterSnapshot counter = counters.get(name);
            return counter != null ? counter.count() : 0;
        }

        /**
         * Returns gauge value by name.
         *
         * @param name the gauge name
         * @return the value, or 0.0 if not found
         */
        public double gaugeValue(String name) {
            GaugeSnapshot gauge = gauges.get(name);
            return gauge != null ? gauge.value() : 0.0;
        }

        /**
         * Converts snapshot to a map for JSON export.
         *
         * @return map representation of all metrics
         */
        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();

            counters.forEach((name, snap) ->
                    result.put("counter." + name, snap.count()));

            timers.forEach((name, snap) -> {
                result.put("timer." + name + ".count", snap.count());
                result.put("timer." + name + ".totalMs", snap.total().toMillis());
                result.put("timer." + name + ".avgMs", snap.average().toMillis());
                result.put("timer." + name + ".maxMs", snap.max().toMillis());
            });

            gauges.forEach((name, snap) ->
                    result.put("gauge." + name, snap.value()));

            return result;
        }
    }

    // In-memory implementations

    private static class InMemoryCounter implements Counter {
        final String name;
        final Tags tags;
        private final AtomicLong count = new AtomicLong();

        InMemoryCounter(String name, Tags tags) {
            this.name = name;
            this.tags = tags;
        }

        @Override
        public void increment() {
            count.incrementAndGet();
        }

        @Override
        public void increment(long amount) {
            count.addAndGet(amount);
        }

        @Override
        public long count() {
            return count.get();
        }
    }

    private static class InMemoryTimer implements Timer {
        final String name;
        final Tags tags;
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();

        InMemoryTimer(String name, Tags tags) {
            this.name = name;
            this.tags = tags;
        }

        @Override
        public void record(Duration duration) {
            long nanos = duration.toNanos();
            count.incrementAndGet();
            totalNanos.addAndGet(nanos);
            maxNanos.updateAndGet(current -> Math.max(current, nanos));
        }

        @Override
        public Sample start() {
            Instant startTime = Instant.now();
            return () -> {
                Duration duration = Duration.between(startTime, Instant.now());
                record(duration);
                return duration;
            };
        }
    }

    private static class InMemoryGauge implements Gauge {
        final String name;
        final Tags tags;
        private final AtomicReference<Double> value = new AtomicReference<>(0.0);

        InMemoryGauge(String name, Tags tags) {
            this.name = name;
            this.tags = tags;
        }

        @Override
        public void set(double value) {
            this.value.set(value);
        }

        @Override
        public double value() {
            return value.get();
        }
    }
}
