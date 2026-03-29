package io.github.markpollack.journal.event;

import io.github.markpollack.journal.metric.Tags;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a metric data point.
 *
 * @param timestamp when the metric was recorded
 * @param name metric name (e.g., "tokens.total", "cost.usd")
 * @param value metric value
 * @param tags additional classification tags
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

    /** Creates a metric event with current timestamp and no tags. */
    public static MetricEvent of(String name, double value) {
        return new MetricEvent(Instant.now(), name, value, Tags.empty());
    }

    /** Creates a metric event with current timestamp and tags. */
    public static MetricEvent of(String name, double value, Tags tags) {
        return new MetricEvent(Instant.now(), name, value, tags);
    }

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
