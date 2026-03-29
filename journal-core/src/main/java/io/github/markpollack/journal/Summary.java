package io.github.markpollack.journal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Final output metrics for a run.
 *
 * <p>Unlike {@link Config} which is immutable after run start, Summary
 * can be overwritten during execution and represents the final state
 * of the run. Each call to {@code run.summary(key, value)} overwrites
 * the previous value for that key.
 *
 * <p>Common summary metrics:
 * <ul>
 *   <li>{@code success} - whether the run achieved its goal</li>
 *   <li>{@code error} - error message if failed</li>
 *   <li>{@code filesChanged} - number of files modified</li>
 *   <li>{@code testsPass} - whether tests passed</li>
 *   <li>{@code totalTokens} - cumulative token usage</li>
 *   <li>{@code totalCostUsd} - cumulative cost</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * Summary summary = Summary.builder()
 *     .set("success", true)
 *     .set("filesChanged", 5)
 *     .set("testsPass", true)
 *     .set("totalCostUsd", 0.15)
 *     .build();
 * }</pre>
 *
 * @param values the summary key-value pairs
 */
public record Summary(@JsonValue Map<String, Object> values) {

    /** Creates an immutable copy of values. */
    public Summary {
        values = Map.copyOf(values);
    }

    /** Creates Summary from a map (for Jackson deserialization). */
    @JsonCreator
    public static Summary fromMap(Map<String, Object> values) {
        return new Summary(values != null ? values : Map.of());
    }

    /** Returns an empty summary. */
    public static Summary empty() {
        return new Summary(Map.of());
    }

    /** Creates a summary with one key-value pair. */
    public static Summary of(String k1, Object v1) {
        return new Summary(Map.of(k1, v1));
    }

    /** Creates a summary with two key-value pairs. */
    public static Summary of(String k1, Object v1, String k2, Object v2) {
        return new Summary(Map.of(k1, v1, k2, v2));
    }

    /** Creates a summary with three key-value pairs. */
    public static Summary of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return new Summary(Map.of(k1, v1, k2, v2, k3, v3));
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a new Summary with the given key-value pair added/updated.
     * This is the primary way to update summary values during a run.
     */
    public Summary with(String key, Object value) {
        var newValues = new LinkedHashMap<>(values);
        newValues.put(key, value);
        return new Summary(newValues);
    }

    /**
     * Returns a new Summary with all values from the given map merged in.
     * Existing keys are overwritten.
     */
    public Summary merge(Map<String, Object> additional) {
        var newValues = new LinkedHashMap<>(values);
        newValues.putAll(additional);
        return new Summary(newValues);
    }

    /** Gets a typed value from the summary. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException("Summary value '" + key + "' is not of type " + type.getName());
        }
        return (T) value;
    }

    /** Gets a value or returns a default. */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        T value = (T) values.get(key);
        return value != null ? value : defaultValue;
    }

    /** Returns true if the summary is empty. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Returns the number of summary entries. */
    public int size() {
        return values.size();
    }

    /** Returns true if the run was successful (has success=true). */
    public boolean isSuccess() {
        return Boolean.TRUE.equals(values.get("success"));
    }

    /** Builder for Summary. */
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        /** Sets a summary value. */
        public Builder set(String key, Object value) {
            values.put(key, value);
            return this;
        }

        /** Builds the immutable Summary. */
        public Summary build() {
            return new Summary(values);
        }
    }
}
