/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.metric;

/**
 * A counter metric that can only be incremented.
 *
 * <p>Counters are useful for tracking cumulative values like:
 * <ul>
 *   <li>Total tokens used</li>
 *   <li>Number of API calls</li>
 *   <li>Error counts</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * Counter tokenCounter = registry.counter("tokens.total", Tags.of("model", "claude-opus"));
 * tokenCounter.increment(1500);
 * }</pre>
 */
public interface Counter {

    /**
     * Increments the counter by 1.
     */
    void increment();

    /**
     * Increments the counter by the given amount.
     *
     * @param amount the amount to add (must be non-negative)
     */
    void increment(long amount);

    /**
     * Returns the current count.
     *
     * @return the current count value
     */
    long count();
}
