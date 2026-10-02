package io.github.markpollack.journal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The inputs of a {@link Run}, such as the model, the prompt version or the temperature, as an
 * immutable map from names to values. Set it on the {@link RunBuilder} with
 * {@code config(key, value)} or {@code config(Config)} before {@link RunBuilder#start()}; the run
 * keeps it unchanged and saves it in its run record. Put the run's outputs in its
 * {@link Summary} instead, which can change while the run is open.
 *
 * <p>Keys and values must not be {@code null}: every method that makes a config throws
 * {@link NullPointerException} for a {@code null} key or value, and lookups throw it for a
 * {@code null} key. The map keeps its entries in the order they were added, by a builder or by
 * {@code with}, or in the order of the map it was made from; the {@code of} methods promise no
 * order. File storage writes the entries in that order and reads them back in the order of the
 * file. A config read back from file storage holds JSON types: whole numbers come back as
 * {@code Integer} or {@code Long} and decimals as {@code Double}, so read numbers as
 * {@link Number}. A config is safe to share between threads.
 *
 * <p>Example:
 * <pre>{@code
 * Config config = Config.builder()
 *     .set("model", "claude-opus-4.5")
 *     .set("maxTokens", 4000)
 *     .set("temperature", 0.7)
 *     .build();
 * }</pre>
 *
 * @param values the inputs by name; an immutable copy, with no {@code null} keys or values
 */
public record Config(@JsonValue Map<String, Object> values) {

    /**
     * Creates a config holding an immutable copy of the given map.
     *
     * @param values the inputs by name
     * @throws NullPointerException if {@code values} is {@code null} or holds a {@code null} key
     *         or value
     */
    public Config {
        values = orderedCopy(values);
    }

    /**
     * Creates a config from a map; JSON reading uses it. Unlike the constructor, it accepts
     * {@code null}.
     *
     * @param values the inputs by name, or {@code null} for an empty config
     * @return the config
     * @throws NullPointerException if the map holds a {@code null} key or value
     */
    @JsonCreator
    public static Config fromMap(Map<String, Object> values) {
        return new Config(values != null ? values : Map.of());
    }

    /**
     * Returns a config with no entries.
     *
     * @return an empty config
     */
    public static Config empty() {
        return new Config(Map.of());
    }

    /**
     * Creates a config with one entry.
     *
     * @param k1 the name
     * @param v1 the value
     * @return the config
     * @throws NullPointerException if a name or value is {@code null}
     */
    public static Config of(String k1, Object v1) {
        return new Config(Map.of(k1, v1));
    }

    /**
     * Creates a config with two entries.
     *
     * @param k1 the first name
     * @param v1 the first value
     * @param k2 the second name
     * @param v2 the second value
     * @return the config
     * @throws NullPointerException if a name or value is {@code null}
     * @throws IllegalArgumentException if two names are equal
     */
    public static Config of(String k1, Object v1, String k2, Object v2) {
        return new Config(Map.of(k1, v1, k2, v2));
    }

    /**
     * Creates a config with three entries.
     *
     * @param k1 the first name
     * @param v1 the first value
     * @param k2 the second name
     * @param v2 the second value
     * @param k3 the third name
     * @param v3 the third value
     * @return the config
     * @throws NullPointerException if a name or value is {@code null}
     * @throws IllegalArgumentException if two names are equal
     */
    public static Config of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return new Config(Map.of(k1, v1, k2, v2, k3, v3));
    }

    /**
     * Returns a builder for a config with many entries.
     *
     * @return a new, empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a copy of this config with one entry added, replacing any earlier value for the
     * name. This config does not change.
     *
     * @param key the name
     * @param value the value
     * @return the new config
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     */
    public Config with(String key, Object value) {
        var newValues = new LinkedHashMap<>(values);
        newValues.put(key, value);
        return new Config(newValues);
    }

    /**
     * Returns the value for a name, checked against the expected type.
     *
     * @param <T> the expected type
     * @param key the name; must not be {@code null}
     * @param type the expected type of the value
     * @return the value, or {@code null} if the config has no entry for the name
     * @throws ClassCastException if the value is not an instance of {@code type}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = values.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException("Config value '" + key + "' is not of type " + type.getName());
        }
        return (T) value;
    }

    /**
     * Returns the value for a name, or a default if the config has no entry for it. The value's
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
        T value = (T) values.get(Objects.requireNonNull(key, "key"));
        return value != null ? value : defaultValue;
    }

    /**
     * Returns whether this config has no entries.
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
     * Collects entries for a {@link Config}. Get one from {@link Config#builder()}. Unlike the
     * config, a builder accepts a {@code null} value at first, but {@link #build()} then throws.
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
         * Returns a config holding the entries added so far. The builder can be used again
         * afterwards; later changes do not affect the returned config.
         *
         * @return the config
         * @throws NullPointerException if a value is {@code null}
         */
        public Config build() {
            return new Config(values);
        }
    }

    /** An unmodifiable copy that keeps the order of {@code values} and rejects a null key or value. */
    private static Map<String, Object> orderedCopy(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"),
                Objects.requireNonNull(value, () -> "value of " + key)));
        return Collections.unmodifiableMap(copy);
    }
}
