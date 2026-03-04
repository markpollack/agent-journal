/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.metric;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable set of key-value tags for metric classification.
 *
 * <p>Tags are used to categorize and filter metrics. They are immutable;
 * all modification operations return new instances.
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

    /** Returns an empty tag set. */
    public static Tags empty() {
        return EMPTY;
    }

    /** Creates a tag set from a map. Used by Jackson for deserialization. */
    @JsonCreator
    public static Tags fromMap(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY;
        }
        return new Tags(new LinkedHashMap<>(values));
    }

    /** Creates a tag set with one tag. */
    public static Tags of(String k1, String v1) {
        return new Tags(Map.of(k1, v1));
    }

    /** Creates a tag set with two tags. */
    public static Tags of(String k1, String v1, String k2, String v2) {
        return new Tags(Map.of(k1, v1, k2, v2));
    }

    /** Creates a tag set with three tags. */
    public static Tags of(String k1, String v1, String k2, String v2, String k3, String v3) {
        return new Tags(Map.of(k1, v1, k2, v2, k3, v3));
    }

    /** Returns a new Tags with the additional tag. */
    public Tags and(String key, String value) {
        var newValues = new LinkedHashMap<>(values);
        newValues.put(key, value);
        return new Tags(newValues);
    }

    /** Returns a new Tags merged with another. */
    public Tags merge(Tags other) {
        var newValues = new LinkedHashMap<>(values);
        newValues.putAll(other.values);
        return new Tags(newValues);
    }

    /** Gets a tag value by key. */
    public String get(String key) {
        return values.get(key);
    }

    /** Checks if tags contain a key. */
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    /** Returns true if there are no tags. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Returns the number of tags. */
    public int size() {
        return values.size();
    }

    /** Returns an unmodifiable copy of the tag map. Used by Jackson for serialization. */
    @JsonValue
    public Map<String, String> toMap() {
        return Map.copyOf(values);
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
