/**
 * Test fixtures and utilities for tuvium-runtime-core tests.
 *
 * <p>This package provides:
 * <ul>
 *   <li>{@link io.github.markpollack.journal.test.TestEvents} - Factory for sample event instances</li>
 *   <li>{@link io.github.markpollack.journal.test.TestRuns} - Factory for run lifecycle scenarios</li>
 *   <li>{@link io.github.markpollack.journal.test.BaseTrackingTest} - Base class with common setup</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * class MyFeatureTest extends BaseTrackingTest {
 *     @Test
 *     void shouldTrackLLMCalls() {
 *         LLMCallEvent event = TestEvents.llmCall();
 *         // ... test logic
 *     }
 *
 *     @Test
 *     void shouldHandleRunRetries() {
 *         List<TestRunData> retryChain = TestRuns.retryChain();
 *         // ... test linked runs
 *     }
 * }
 * }</pre>
 */
package io.github.markpollack.journal.test;
