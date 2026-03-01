package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * User-defined custom event.
 *
 * @param timestamp when the event occurred
 * @param name event name/type
 * @param attributes custom attributes
 */
public record CustomEvent(
        Instant timestamp,
        String name,
        Map<String, Object> attributes
) implements JournalEvent {

    @Override
    public String type() {
        return name;
    }

    /** Creates a custom event with current timestamp. */
    public static CustomEvent of(String name, Map<String, Object> attributes) {
        return new CustomEvent(Instant.now(), name, attributes);
    }

    /** Creates a custom event with no attributes. */
    public static CustomEvent of(String name) {
        return new CustomEvent(Instant.now(), name, Map.of());
    }

    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("type", name);
        map.put("timestamp", timestamp.toString());
        map.putAll(attributes);
        return map;
    }
}
