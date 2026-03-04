/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal;

import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JournalStorage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the global tracking configuration, primarily the storage backend.
 *
 * <p>JournalContext is thread-safe and provides a single point of configuration
 * for the tracking system. It defaults to {@link InMemoryStorage} if no storage
 * is explicitly configured.
 *
 * <p>Configuration should typically be done once at application startup:
 * <pre>{@code
 * // Use file-based storage
 * JournalContext.setStorage(new JsonFileStorage(Path.of(".agent-journal")));
 *
 * // Or use in-memory for testing
 * JournalContext.setStorage(new InMemoryStorage());
 * }</pre>
 *
 * <p>For testing, use {@link #reset()} to restore default settings:
 * <pre>{@code
 * @AfterEach
 * void tearDown() {
 *     JournalContext.reset();
 * }
 * }</pre>
 */
public final class JournalContext {

    private static final AtomicReference<JournalStorage> storage = new AtomicReference<>();

    private JournalContext() {} // Utility class

    /**
     * Sets the storage backend for tracking.
     *
     * <p>This should be called once at application startup before creating
     * any runs. Changing storage after runs have been created may result
     * in data inconsistency.
     *
     * @param trackingStorage the storage implementation to use
     * @throws NullPointerException if trackingStorage is null
     */
    public static void setStorage(JournalStorage trackingStorage) {
        storage.set(Objects.requireNonNull(trackingStorage, "storage cannot be null"));
    }

    /**
     * Returns the configured storage backend.
     *
     * <p>If no storage has been explicitly configured, returns a default
     * {@link InMemoryStorage} instance. This allows the API to work
     * out-of-the-box without requiring explicit configuration.
     *
     * @return the current storage backend
     */
    public static JournalStorage getStorage() {
        JournalStorage current = storage.get();
        if (current == null) {
            // Lazily initialize with in-memory storage
            InMemoryStorage defaultStorage = new InMemoryStorage();
            if (storage.compareAndSet(null, defaultStorage)) {
                return defaultStorage;
            }
            // Another thread won the race, return their storage
            return storage.get();
        }
        return current;
    }

    /**
     * Returns true if storage has been explicitly configured.
     *
     * @return true if storage was explicitly set
     */
    public static boolean isConfigured() {
        return storage.get() != null;
    }

    /**
     * Resets the context to its initial state.
     *
     * <p>This is primarily useful for testing to ensure clean state
     * between test cases. Production code should typically set storage
     * once at startup and not call reset.
     */
    public static void reset() {
        storage.set(null);
    }
}
