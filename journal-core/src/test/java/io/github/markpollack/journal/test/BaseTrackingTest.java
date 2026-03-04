/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.test;

import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

/**
 * Base test class providing common setup for tracking tests.
 *
 * <p>Provides:
 * <ul>
 *   <li>Temporary directory for file-based storage tests</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * class MyTest extends BaseTrackingTest {
 *     @Test
 *     void shouldWriteToStorage() {
 *         Path storagePath = getStoragePath();
 *         // ... test file-based storage
 *     }
 * }
 * }</pre>
 */
public abstract class BaseTrackingTest {

    /** Temporary directory for storage tests. Cleaned up after each test. */
    @TempDir
    protected Path tempDir;

    /**
     * Returns the path where .agent-journal storage would be created.
     * Use this for file-based storage tests.
     */
    protected Path getStoragePath() {
        return tempDir.resolve(".agent-journal");
    }
}
