/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.call;

import io.github.markpollack.journal.metric.Tags;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Represents a single call in a hierarchical call tree.
 *
 * <p>Calls track the execution of operations within an agent workflow,
 * supporting parent-child relationships for nested operations. Each call
 * automatically tracks its duration when closed.
 *
 * <p>Example usage:
 * <pre>{@code
 * try (Call agentLoop = run.calls().startCall("agent-loop")) {
 *     for (int turn = 0; turn < 10; turn++) {
 *         try (Call turnCall = agentLoop.child("turn")) {
 *             turnCall.setAttribute("turnNumber", turn);
 *
 *             try (Call llmCall = turnCall.child("llm-call")) {
 *                 // Make LLM call
 *                 llmCall.setAttribute("model", "claude-opus-4.5");
 *             }
 *
 *             try (Call toolCall = turnCall.child("tool-call")) {
 *                 toolCall.setAttribute("tool", "bash");
 *                 // Execute tool
 *             }
 *         }
 *     }
 * }
 * }</pre>
 */
public interface Call extends AutoCloseable {

    /**
     * Returns the unique identifier for this call.
     */
    String id();

    /**
     * Returns the operation name for this call.
     */
    String operation();

    /**
     * Returns the tags associated with this call.
     */
    Tags tags();

    /**
     * Returns the parent call, or null if this is a root call.
     */
    Call parent();

    /**
     * Returns the start time of this call.
     */
    Instant startTime();

    /**
     * Returns the end time of this call, or null if still running.
     */
    Instant endTime();

    /**
     * Returns the duration of this call, or null if still running.
     */
    Duration duration();

    /**
     * Returns true if this call has completed (either successfully or with failure).
     */
    boolean isComplete();

    /**
     * Returns true if this call failed.
     */
    boolean isFailed();

    /**
     * Returns the failure cause, or null if not failed.
     */
    Throwable failureCause();

    /**
     * Creates a child call with the given operation name.
     *
     * @param operation the operation name for the child call
     * @return a new child call
     */
    default Call child(String operation) {
        return child(operation, Tags.empty());
    }

    /**
     * Creates a child call with the given operation name and tags.
     *
     * @param operation the operation name for the child call
     * @param tags tags for the child call
     * @return a new child call
     */
    Call child(String operation, Tags tags);

    /**
     * Logs an event within this call.
     *
     * @param name the event name
     */
    default void event(String name) {
        event(name, Map.of());
    }

    /**
     * Logs an event with attributes within this call.
     *
     * @param name the event name
     * @param attributes event attributes
     */
    void event(String name, Map<String, Object> attributes);

    /**
     * Sets a custom attribute on this call.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    void setAttribute(String key, Object value);

    /**
     * Returns the attributes set on this call.
     */
    Map<String, Object> attributes();

    /**
     * Marks this call as failed with the given error.
     *
     * @param error the error that caused the failure
     */
    void fail(Throwable error);

    /**
     * Closes this call, recording its end time and duration.
     * If the call has not been explicitly failed, it is considered successful.
     */
    @Override
    void close();
}
