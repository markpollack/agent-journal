/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal;

import io.github.markpollack.journal.call.CallTracker;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.metric.MetricRegistry;
import io.github.markpollack.journal.metric.Tags;

import java.util.Map;

/**
 * A run represents a single execution of an agent workflow.
 *
 * <p>Runs are the primary unit of tracking. Each run captures:
 * <ul>
 *   <li>Config - immutable input parameters</li>
 *   <li>Events - append-only log of LLM calls, tool executions, state changes</li>
 *   <li>Metrics - counters, timers, gauges for performance tracking</li>
 *   <li>Summary - overwritable output metrics</li>
 *   <li>Artifacts - versioned file outputs</li>
 * </ul>
 *
 * <p>Run follows the try-with-resources pattern:
 * <pre>{@code
 * try (Run run = Journal.forExperiment("my-experiment")
 *         .config("model", "claude-opus-4.5")
 *         .start()) {
 *
 *     run.logEvent(LLMCallEvent.of("claude-opus-4.5", 1200, 450, 0.023));
 *     run.logMetric("tokens.total", 1650);
 *     run.setSummary("success", true);
 *
 * } // Auto-finish with SUCCESS (or FAILED on exception)
 * }</pre>
 *
 * <p>Status handling:
 * <ul>
 *   <li>Normal close() → FINISHED</li>
 *   <li>Exception escapes → FAILED (exception captured)</li>
 *   <li>Explicit fail(Throwable) → FAILED</li>
 *   <li>Explicit finish(RunStatus) → custom status</li>
 * </ul>
 */
public interface Run extends AutoCloseable {

    /** Returns the unique run identifier. */
    String id();

    /** Returns the run name (human-readable). */
    String name();

    /** Returns the experiment this run belongs to. */
    Experiment experiment();

    /** Returns the current run status. */
    RunStatus status();

    /** Returns the run configuration (immutable after start). */
    Config config();

    /** Returns the current summary. */
    Summary summary();

    /** Returns the run tags. */
    Tags tags();

    /** Returns the agent identifier, if set. */
    String agentId();

    /** Returns the previous run ID (for retry chains), if set. */
    String previousRunId();

    /** Returns the parent run ID (for sub-runs), if set. */
    String parentRunId();

    /**
     * Logs a structured event to the run's event log.
     *
     * @param event the event to log
     */
    void logEvent(JournalEvent event);

    /**
     * Logs a metric value.
     *
     * @param name the metric name
     * @param value the metric value
     */
    void logMetric(String name, double value);

    /**
     * Logs a metric value with tags.
     *
     * @param name the metric name
     * @param value the metric value
     * @param tags additional tags
     */
    void logMetric(String name, double value, Tags tags);

    /**
     * Logs an artifact (text content).
     *
     * @param name the artifact name (e.g., "plan.md")
     * @param content the artifact content
     */
    void logArtifact(String name, String content);

    /**
     * Logs an artifact with metadata.
     *
     * @param name the artifact name
     * @param content the artifact content as bytes
     * @param metadata additional metadata
     */
    void logArtifact(String name, byte[] content, Map<String, Object> metadata);

    /**
     * Sets or updates a summary value.
     * Summary values can be overwritten (unlike events which are append-only).
     *
     * @param key the summary key
     * @param value the summary value
     */
    void setSummary(String key, Object value);

    /**
     * Returns the metrics registry for this run.
     * Allows direct counter/timer/gauge manipulation.
     */
    MetricRegistry metrics();

    /**
     * Returns the call tracker for hierarchical call recording.
     *
     * <p>Example:
     * <pre>{@code
     * try (Call loop = run.calls().startCall("agent-loop")) {
     *     try (Call turn = loop.child("turn-1")) {
     *         // Do work
     *     }
     * }
     * }</pre>
     */
    CallTracker calls();

    /**
     * Marks the run as failed with the given exception.
     * Called automatically if an exception escapes the try-with-resources block.
     *
     * @param error the error that caused the failure
     */
    void fail(Throwable error);

    /**
     * Finishes the run with the given status.
     * Called automatically by close() if not already finished.
     *
     * @param status the final status
     */
    void finish(RunStatus status);

    /**
     * Closes the run, finalizing its status.
     * If no explicit status was set, defaults to FINISHED.
     * If an exception occurred (via fail()), status is FAILED.
     */
    @Override
    void close();
}
