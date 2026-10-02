package io.github.markpollack.journal.metric;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An immutable set of string labels, each a name and a value, used to group and filter what a
 * journal records. A {@link io.github.markpollack.journal.Run} carries the tags set on its
 * {@link io.github.markpollack.journal.RunBuilder}; metrics in a {@link MetricRegistry},
 * {@link io.github.markpollack.journal.event.MetricEvent}s and
 * {@link io.github.markpollack.journal.call.Call}s carry their own. Methods that add tags
 * return a new instance and leave this one unchanged, so tags are safe to share between threads.
 *
 * <p>Names and values must not be {@code null}. Every method that makes a tag set checks this
 * and throws {@link NullPointerException}, so a bad tag fails where it is added, not later when
 * the tags are written to storage.
 *
 * <p>Two tag sets are equal when they hold the same names and values, in any order.
 *
 * <p>Example:
 * <pre>{@code
 * Tags tags = Tags.of("model", "claude-opus-4.5")
 *     .and("provider", "anthropic")
 *     .and("agent", "code-gen");
 * }</pre>
 */
public final class Tags {

    private static final Tags EMPTY = new Tags(Map.of());

    private final Map<String, String> values;

    private Tags(Map<String, String> values) {
        this.values = values;
    }

    /**
     * Returns a tag set with no tags.
     *
     * @return the empty tag set
     */
    public static Tags empty() {
        return EMPTY;
    }

    /**
     * Creates a tag set from a copy of a map; JSON reading uses it.
     *
     * @param values the tags by name, or {@code null} for no tags; the names and values in it
     *        must not be {@code null}
     * @return the tag set
     * @throws NullPointerException if the map holds a {@code null} name or value
     */
    @JsonCreator
    public static Tags fromMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        var copy = new LinkedHashMap<String, String>();
        values.forEach((k, v) -> copy.put(requireName(k), requireValue(k, v)));
        return new Tags(copy);
    }

    /**
     * Creates a tag set with one tag.
     *
     * @param k1 the name
     * @param v1 the value
     * @return the tag set
     * @throws NullPointerException if a name or value is {@code null}
     */
    public static Tags of(String k1, String v1) {
        return new Tags(Map.of(k1, v1));
    }

    /**
     * Creates a tag set with two tags.
     *
     * @param k1 the first name
     * @param v1 the first value
     * @param k2 the second name
     * @param v2 the second value
     * @return the tag set
     * @throws NullPointerException if a name or value is {@code null}
     * @throws IllegalArgumentException if two names are equal
     */
    public static Tags of(String k1, String v1, String k2, String v2) {
        return new Tags(Map.of(k1, v1, k2, v2));
    }

    /**
     * Creates a tag set with three tags.
     *
     * @param k1 the first name
     * @param v1 the first value
     * @param k2 the second name
     * @param v2 the second value
     * @param k3 the third name
     * @param v3 the third value
     * @return the tag set
     * @throws NullPointerException if a name or value is {@code null}
     * @throws IllegalArgumentException if two names are equal
     */
    public static Tags of(String k1, String v1, String k2, String v2, String k3, String v3) {
        return new Tags(Map.of(k1, v1, k2, v2, k3, v3));
    }

    /**
     * Returns a copy of this tag set with one tag added, replacing any earlier value for the
     * name.
     *
     * @param key the name; must not be {@code null}
     * @param value the value; must not be {@code null}
     * @return the new tag set
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public Tags and(String key, String value) {
        var newValues = new LinkedHashMap<>(values);
        newValues.put(requireName(key), requireValue(key, value));
        return new Tags(newValues);
    }

    /**
     * Returns a copy of this tag set with all tags of another added. Where both have a name, the
     * other's value wins.
     *
     * @param other the tags to add; must not be {@code null}
     * @return the new tag set
     */
    public Tags merge(Tags other) {
        var newValues = new LinkedHashMap<>(values);
        newValues.putAll(other.values);
        return new Tags(newValues);
    }

    /**
     * Returns the value of a tag.
     *
     * @param key the name
     * @return the value, or {@code null} if there is no tag with that name
     */
    public String get(String key) {
        return values.get(key);
    }

    /**
     * Returns whether there is a tag with the given name.
     *
     * @param key the name
     * @return {@code true} if the tag is present
     */
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /**
     * Returns whether this set has no tags.
     *
     * @return {@code true} if there are no tags
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns the number of tags.
     *
     * @return the number of tags
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns the tags as a map; JSON writing uses it. The map is an immutable copy in no set
     * order.
     *
     * @return the tags by name
     */
    @JsonValue
    public Map<String, String> toMap() {
        return Map.copyOf(values);
    }

    private static String requireName(String key) {
        return Objects.requireNonNull(key, "tag name must not be null");
    }

    private static String requireValue(String key, String value) {
        return Objects.requireNonNull(value, () -> "value of tag '" + key + "' must not be null");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Tags tags = (Tags) o;
        return values.equals(tags.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        if (isEmpty()) return "Tags{}";
        return values.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", ", "Tags{", "}"));
    }
}
