package io.github.markpollack.journal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outputs of a {@link Run}, such as whether it succeeded, the files it changed or its total
 * cost, as an immutable map from names to values. While a run is open, each
 * {@link Run#setSummary(String, Object)} replaces the run's summary with a copy that has the new
 * value, so the last value for a name wins; read it with {@link Run#summary()}. Put the run's
 * inputs in its {@link Config} instead, which cannot change after the run starts.
 *
 * <p>The run writes the summary to storage each time the run record is saved: when the run
 * starts, with an empty summary, on each {@link Run#setSummary(String, Object)}, and when it ends
 * through {@link Run#close()}, {@link Run#finish(RunStatus)} or {@link Run#fail(Throwable)}. A
 * value set while the run is open is therefore kept even if the process dies before the run
 * ends.
 *
 * <p>The run sets three names itself. {@code fail(error)} sets {@code success} to {@code false},
 * {@code error} to the exception's message, if it has one, and {@code errorType} to its class
 * name. Ending as {@link RunStatus#FINISHED} sets {@code success} to {@code true} unless a
 * {@code success} value is already present. Other names, such as {@code filesChanged}, are the caller's choice.
 *
 * <p>Keys and values must not be {@code null}: every method that makes a summary throws
 * {@link NullPointerException} for a {@code null} key or value, and lookups throw it for a
 * {@code null} key. The map does not keep the order in which entries were added. A summary read
 * back from file storage holds JSON types: whole numbers come back as {@code Integer} or
 * {@code Long} and decimals as {@code Double}, so read numbers as {@link Number}. A summary is
 * safe to share between threads.
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
 * @param values the outputs by name; an immutable copy, with no {@code null} keys or values
 */
public record Summary(@JsonValue Map<String, Object> values) {

    /**
     * Creates a summary holding an immutable copy of the given map.
     *
     * @param values the outputs by name
     * @throws NullPointerException if {@code values} is {@code null} or holds a {@code null} key
     *         or value
     */
    public Summary {
        values = Map.copyOf(values);
    }

    /**
     * Creates a summary from a map; JSON reading uses it. Unlike the constructor, it accepts
     * {@code null}.
     *
     * @param values the outputs by name, or {@code null} for an empty summary
     * @return the summary
     * @throws NullPointerException if the map holds a {@code null} key or value
     */
    @JsonCreator
    public static Summary fromMap(Map<String, Object> values) {
        return new Summary(values != null ? values : Map.of());
    }

    /**
     * Returns a summary with no entries; a new run starts with one.
     *
     * @return an empty summary
     */
    public static Summary empty() {
        return new Summary(Map.of());
    }

    /**
     * Creates a summary with one entry.
     *
     * @param k1 the name
     * @param v1 the value
     * @return the summary
     * @throws NullPointerException if a name or value is {@code null}
     */
    public static Summary of(String k1, Object v1) {
        return new Summary(Map.of(k1, v1));
    }

    /**
     * Creates a summary with two entries.
     *
     * @param k1 the first name
     * @param v1 the first value
     * @param k2 the second name
     * @param v2 the second value
     * @return the summary
     * @throws NullPointerException if a name or value is {@code null}
     * @throws IllegalArgumentException if two names are equal
     */
    public static Summary of(String k1, Object v1, String k2, Object v2) {
        return new Summary(Map.of(k1, v1, k2, v2));
    }

    /**
     * Creates a summary with three entries.
     *
     * @param k1 the first name
     * @param v1 the first value
     * @param k2 the second name
     * @param v2 the second value
     * @param k3 the third name
     * @param v3 the third value
     * @return the summary
     * @throws NullPointerException if a name or value is {@code null}
     * @throws IllegalArgumentException if two names are equal
     */
    public static Summary of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return new Summary(Map.of(k1, v1, k2, v2, k3, v3));
    }

    /**
     * Returns a builder for a summary with many entries.
     *
     * @return a new, empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a copy of this summary with one entry added, replacing any earlier value for the
     * name. This summary does not change. {@link Run#setSummary(String, Object)} uses it.
     *
     * @param key the name
     * @param value the value
     * @return the new summary
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public Summary with(String key, Object value) {
        var newValues = new LinkedHashMap<>(values);
        newValues.put(key, value);
        return new Summary(newValues);
    }

    /**
     * Returns a copy of this summary with all entries of the given map added, replacing earlier
     * values for the same names. This summary does not change.
     *
     * @param additional the entries to add
     * @return the new summary
     * @throws NullPointerException if {@code additional} is {@code null} or holds a {@code null}
     *         key or value
     */
    public Summary merge(Map<String, Object> additional) {
        var newValues = new LinkedHashMap<>(values);
        newValues.putAll(additional);
        return new Summary(newValues);
    }

    /**
     * Returns the value for a name, checked against the expected type.
     *
     * @param <T> the expected type
     * @param key the name; must not be {@code null}
     * @param type the expected type of the value
     * @return the value, or {@code null} if the summary has no entry for the name
     * @throws ClassCastException if the value is not an instance of {@code type}
     */
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

    /**
     * Returns the value for a name, or a default if the summary has no entry for it. The value's
     * type is not checked: use it only when the stored value has the default's type, or prefer
     * {@link #get(String, Class)}.
     *
     * @param <T> the type of the value
     * @param key the name; must not be {@code null}
     * @param defaultValue the value to return when there is no entry
     * @return the stored value, or {@code defaultValue}
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        T value = (T) values.get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns whether this summary has no entries.
     *
     * @return {@code true} if there are no entries
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Returns the number of entries.
     *
     * @return the number of entries
     */
    public int size() {
        return values.size();
    }

    /**
     * Returns whether the {@code success} value is {@link Boolean#TRUE}. Any other value, such as
     * the string {@code "true"}, or no value counts as not successful.
     *
     * @return {@code true} if {@code success} is the boolean {@code true}
     */
    public boolean isSuccess() {
        return Boolean.TRUE.equals(values.get("success"));
    }

    /**
     * Collects entries for a {@link Summary}. Get one from {@link Summary#builder()}. Unlike the
     * summary, a builder accepts a {@code null} value at first, but {@link #build()} then throws.
     * A builder is not safe for use from several threads.
     */
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        /**
         * Adds an entry, replacing any earlier value for the name.
         *
         * @param key the name; must not be {@code null}
         * @param value the value; must not be {@code null} when {@link #build()} is called
         * @return this builder
         */
        public Builder set(String key, Object value) {
            values.put(key, value);
            return this;
        }

        /**
         * Returns a summary holding the entries added so far. The builder can be used again
         * afterwards; later changes do not affect the returned summary.
         *
         * @return the summary
         * @throws NullPointerException if a value is {@code null}
         */
        public Summary build() {
            return new Summary(values);
        }
    }
}
