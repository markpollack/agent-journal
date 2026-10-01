package io.github.markpollack.journal.call;

import io.github.markpollack.journal.metric.Tags;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * One named operation inside a run, such as an agent loop, a turn or a tool call, timed from when
 * it starts until it is closed. Start a top-level call with {@link CallTracker#startCall(String)}
 * on {@link io.github.markpollack.journal.Run#calls()}, start nested calls with
 * {@link #child(String)}, and close each one with try-with-resources. Calls are kept in memory
 * only: nothing about them is written to storage. To record an operation in the run's event
 * log, log a {@link io.github.markpollack.journal.event.JournalEvent} on the run instead.
 *
 * <p>A call ends when it is closed or failed. To record a failure, call
 * {@link #fail(Throwable)} inside the try block: it ends the call, and the later {@code close()}
 * does nothing. Once a call has ended, {@link #child(String, Tags)},
 * {@link #event(String, Map)} and {@link #setAttribute(String, Object)} throw
 * {@link IllegalStateException}. Closing a call does not close its children.
 *
 * <p>The calls a run's tracker creates are safe for use from several threads. Implementations
 * must make {@code close()} and {@code fail} do nothing on a call that has already ended.
 *
 * <p>Example:
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
     * Returns the ID of this call, a random UUID.
     *
     * @return the ID, never {@code null}
     */
    String id();

    /**
     * Returns the name of the operation, as passed when the call was started.
     *
     * @return the operation name
     */
    String operation();

    /**
     * Returns the tags given when the call was started.
     *
     * @return the tags, empty if none were given
     */
    Tags tags();

    /**
     * Returns the call this one was started from with {@link #child(String)}.
     *
     * @return the parent call, or {@code null} for a call started by {@link CallTracker}
     */
    Call parent();

    /**
     * Returns when this call started.
     *
     * @return the start time
     */
    Instant startTime();

    /**
     * Returns when this call was closed or failed.
     *
     * @return the end time, or {@code null} while the call is open
     */
    Instant endTime();

    /**
     * Returns how long this call ran, from its start to its end.
     *
     * @return the duration, or {@code null} while the call is open
     */
    Duration duration();

    /**
     * Returns whether this call has ended, by {@link #close()} or {@link #fail(Throwable)}.
     *
     * @return {@code true} if the call has ended
     */
    boolean isComplete();

    /**
     * Returns whether this call ended through {@link #fail(Throwable)}.
     *
     * @return {@code true} if the call failed
     */
    boolean isFailed();

    /**
     * Returns the error passed to {@link #fail(Throwable)}.
     *
     * @return the cause, or {@code null} if the call has not failed
     */
    Throwable failureCause();

    /**
     * Starts a call nested in this one, with no tags. Close it before this call.
     * The default calls {@link #child(String, Tags)} with {@link Tags#empty()}.
     *
     * @param operation the name of the nested operation, such as {@code "turn"}
     * @return the new call, already started
     * @throws IllegalStateException if this call has ended
     */
    default Call child(String operation) {
        return child(operation, Tags.empty());
    }

    /**
     * Starts a call nested in this one. The new call becomes the current call of the calling
     * thread (see {@link CallTracker#currentCall()}). Close it before this call.
     *
     * @param operation the name of the nested operation
     * @param tags the tags for the new call; {@code null} gives no tags
     * @return the new call, already started
     * @throws IllegalStateException if this call has ended
     */
    Call child(String operation, Tags tags);

    /**
     * Records a named moment inside this call, with no attributes.
     * The default calls {@link #event(String, Map)} with an empty map.
     *
     * @param name the name of the moment, such as {@code "retry"}
     * @throws IllegalStateException if this call has ended
     */
    default void event(String name) {
        event(name, Map.of());
    }

    /**
     * Records a named moment inside this call, with attributes and the current time. The moment
     * is kept with the call in memory, not in the run's event log, and this interface has no
     * method to read it back; {@link DefaultCall#events()} returns them.
     *
     * @param name the name of the moment
     * @param attributes details of the moment
     * @throws IllegalStateException if this call has ended
     */
    void event(String name, Map<String, Object> attributes);

    /**
     * Sets an attribute of this call, such as the model or the tool used, replacing any earlier
     * value for the key.
     *
     * @param key the attribute name
     * @param value the value
     * @throws IllegalStateException if this call has ended
     */
    void setAttribute(String key, Object value);

    /**
     * Returns the attributes set on this call so far.
     *
     * @return an unmodifiable copy of the attributes, in the order they were first set
     */
    Map<String, Object> attributes();

    /**
     * Ends this call as failed and records the error. Does nothing if the call has already ended.
     * Call it inside the try block: the later {@code close()} then does nothing.
     *
     * @param error the cause of the failure; must not be {@code null}
     */
    void fail(Throwable error);

    /**
     * Ends this call and records its end time, unless it has already ended. A call that was not
     * failed counts as successful. Try-with-resources calls this at the end of the block.
     */
    @Override
    void close();
}
