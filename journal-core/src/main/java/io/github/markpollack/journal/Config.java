/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable configuration for a run.
 * Represents input parameters/hyperparameters.
 *
 * <p>Following W&B patterns:
 * <ul>
 *   <li>Config is set at run start (INIT phase)</li>
 *   <li>Config is immutable after the run starts</li>
 *   <li>Config represents INPUT parameters (vs Summary which represents OUTPUT)</li>
 * </ul>
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
 * @param values the configuration key-value pairs
 */
public record Config(@JsonValue Map<String, Object> values) {

    /** Creates an immutable copy of values. */
    public Config {
        values = Map.copyOf(values);
    }

    /** Creates Config from a map (for Jackson deserialization). */
    @JsonCreator
    public static Config fromMap(Map<String, Object> values) {
        return new Config(values != null ? values : Map.of());
    }

    /** Returns an empty configuration. */
    public static Config empty() {
        return new Config(Map.of());
    }

    /** Creates a config with one key-value pair. */
    public static Config of(String k1, Object v1) {
        return new Config(Map.of(k1, v1));
    }

    /** Creates a config with two key-value pairs. */
    public static Config of(String k1, Object v1, String k2, Object v2) {
        return new Config(Map.of(k1, v1, k2, v2));
    }

    /** Creates a config with three key-value pairs. */
    public static Config of(String k1, Object v1, String k2, Object v2, String k3, Object v3) {
        return new Config(Map.of(k1, v1, k2, v2, k3, v3));
    }

    /** Creates a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a new Config with the additional key-value pair. */
    public Config with(String key, Object value) {
        var newValues = new LinkedHashMap<>(values);
        newValues.put(key, value);
        return new Config(newValues);
    }

    /** Gets a typed value from the config. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = values.get(key);
        if (value == null) {
            return null;
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException("Config value '" + key + "' is not of type " + type.getName());
        }
        return (T) value;
    }

    /** Gets a value or returns a default. */
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        T value = (T) values.get(key);
        return value != null ? value : defaultValue;
    }

    /** Returns true if the config is empty. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Returns the number of config entries. */
    public int size() {
        return values.size();
    }

    /** Builder for Config. */
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        /** Sets a configuration value. */
        public Builder set(String key, Object value) {
            values.put(key, value);
            return this;
        }

        /** Builds the immutable Config. */
        public Config build() {
            return new Config(values);
        }
    }
}
