package io.github.markpollack.journal.event;

import io.github.markpollack.journal.metric.Tags;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records one value of a named metric at a point in time, such as {@code "tokens.total"}, with
 * optional {@link Tags}. {@link io.github.markpollack.journal.Run#logMetric(String, double, Tags)}
 * logs one each time it is called, besides setting the gauge in the run's
 * {@link io.github.markpollack.journal.metric.MetricRegistry}; you can also log one yourself with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}. Read it back with
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)};
 * {@link io.github.markpollack.journal.eval.EvalSubjectSources} skips metric events. The run
 * recorders do not log metric events.
 *
 * <p>Use {@link Tags#empty()}, not {@code null}, when there are no tags: the record accepts
 * {@code null}, but {@link #toMap()} then throws {@link NullPointerException}. File storage writes
 * it with {@code @type} {@code "metric"}. The record is immutable.
 *
 * @param timestamp when the value was recorded
 * @param name the metric name, such as {@code "tokens.total"} or {@code "cost.usd"}
 * @param value the value
 * @param tags the tags that classify the value
 */
public record MetricEvent(
        Instant timestamp,
        String name,
        double value,
        Tags tags
) implements JournalEvent {

    @Override
    public String type() {
        return "metric";
    }

    /**
     * Creates a metric event with the current time and no tags.
     *
     * @param name the metric name
     * @param value the value
     * @return the new event
     */
    public static MetricEvent of(String name, double value) {
        return new MetricEvent(Instant.now(), name, value, Tags.empty());
    }

    /**
     * Creates a metric event with the current time and tags.
     *
     * @param name the metric name
     * @param value the value
     * @param tags the tags that classify the value
     * @return the new event
     */
    public static MetricEvent of(String name, double value, Tags tags) {
        return new MetricEvent(Instant.now(), name, value, tags);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code type}, {@code timestamp}, {@code name} and {@code value}, then
     * {@code tags} when there are any.
     *
     * @throws NullPointerException if {@link #timestamp()} or {@link #tags()} is {@code null}
     */
    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", type());
        map.put("timestamp", timestamp.toString());
        map.put("name", name);
        map.put("value", value);
        if (!tags.isEmpty()) {
            map.put("tags", tags.toMap());
        }
        return map;
    }
}
