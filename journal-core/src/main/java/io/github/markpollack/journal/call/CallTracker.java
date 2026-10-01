package io.github.markpollack.journal.call;

import io.github.markpollack.journal.metric.Tags;

import java.util.List;

/**
 * Records the nested, timed operations of one run as a tree of {@link Call}s. Get a run's tracker
 * from {@link io.github.markpollack.journal.Run#calls()}, start a top-level call with
 * {@link #startCall(String)}, and nest calls under it with {@link Call#child(String)}. Afterwards,
 * walk the tree from {@link #rootCalls()} or list every call with {@link #allCalls()} to see what
 * ran, for how long, and what failed. The tree is kept in memory only: nothing is written to
 * storage, so read it before the run object is discarded.
 *
 * <p>Each thread has its own current call, the innermost call it started that is still open
 * (see {@link #currentCall()}). The tracker a run returns is safe for use from several threads.
 * Implementations must include ended calls in {@link #rootCalls()}, {@link #allCalls()} and
 * {@link #callCount()}; the interface does not require thread safety.
 *
 * <p>Example:
 * <pre>{@code
 * try (Run run = Journal.run("agent-task").start()) {
 *     CallTracker calls = run.calls();
 *
 *     try (Call mainLoop = calls.startCall("main-loop")) {
 *         try (Call step1 = mainLoop.child("step-1")) {
 *             // Do step 1
 *         }
 *         try (Call step2 = mainLoop.child("step-2")) {
 *             // Do step 2
 *         }
 *     }
 * }
 * }</pre>
 *
 */
public interface CallTracker {

    /**
     * Starts a top-level call with no tags.
     * The default calls {@link #startCall(String, Tags)} with {@link Tags#empty()}.
     *
     * @param operation the name of the operation, such as {@code "agent-loop"} or
     *        {@code "planning"}
     * @return the new call, already started; close it when the operation ends
     */
    default Call startCall(String operation) {
        return startCall(operation, Tags.empty());
    }

    /**
     * Starts a top-level call. It has no parent even if another call is open; to nest a call, use
     * {@link Call#child(String, Tags)}. The new call becomes the current call of the calling
     * thread.
     *
     * @param operation the name of the operation
     * @param tags the tags for the call; {@code null} gives no tags
     * @return the new call, already started; close it when the operation ends
     */
    Call startCall(String operation, Tags tags);

    /**
     * Returns the top-level calls started so far, open and ended, in the order they started.
     *
     * @return an unmodifiable copy of the list, empty if no call was started
     */
    List<Call> rootCalls();

    /**
     * Returns every call started so far, top-level and nested, open and ended, in the order they
     * started.
     *
     * @return an unmodifiable copy of the list, empty if no call was started
     */
    List<Call> allCalls();

    /**
     * Returns the innermost open call started on the calling thread. Calls started on other
     * threads are not seen. This holds only when calls are closed in the reverse order they were
     * started, as try-with-resources does; otherwise the result can be a call that has already
     * ended.
     *
     * @return the current call, or {@code null} if the calling thread has no open call
     */
    Call currentCall();

    /**
     * Returns the number of calls started so far, top-level and nested, open and ended.
     *
     * @return the number of calls
     */
    int callCount();
}
