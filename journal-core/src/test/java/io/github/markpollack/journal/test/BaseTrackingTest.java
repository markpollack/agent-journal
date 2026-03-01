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
     * Returns the path where .tuvium storage would be created.
     * Use this for file-based storage tests.
     */
    protected Path getStoragePath() {
        return tempDir.resolve(".tuvium");
    }
}
