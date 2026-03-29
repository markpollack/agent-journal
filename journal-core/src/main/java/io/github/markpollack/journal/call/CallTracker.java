package io.github.markpollack.journal.call;

import io.github.markpollack.journal.metric.Tags;

import java.util.List;

/**
 * Tracks hierarchical call trees within a run.
 *
 * <p>CallTracker enables structured tracking of nested operations,
 * making it easy to understand the execution flow of agent workflows.
 * Each call can have child calls, forming a tree structure.
 *
 * <p>Example usage:
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
 * <p>After the run completes, the call tree can be analyzed to understand:
 * <ul>
 *   <li>How long each operation took</li>
 *   <li>Which operations failed and why</li>
 *   <li>The nesting structure of operations</li>
 * </ul>
 */
public interface CallTracker {

    /**
     * Starts a new root-level call with the given operation name.
     *
     * @param operation the operation name (e.g., "agent-loop", "planning")
     * @return a new Call that should be closed when the operation completes
     */
    default Call startCall(String operation) {
        return startCall(operation, Tags.empty());
    }

    /**
     * Starts a new root-level call with the given operation name and tags.
     *
     * @param operation the operation name
     * @param tags tags for categorizing the call
     * @return a new Call that should be closed when the operation completes
     */
    Call startCall(String operation, Tags tags);

    /**
     * Returns all root-level calls that have been started.
     * Completed calls are included.
     *
     * @return list of root calls
     */
    List<Call> rootCalls();

    /**
     * Returns all calls (root and nested) in a flat list.
     *
     * @return all calls
     */
    List<Call> allCalls();

    /**
     * Returns the currently active call (the most recently started call
     * that hasn't been closed yet), or null if no calls are active.
     *
     * @return the current active call, or null
     */
    Call currentCall();

    /**
     * Returns the total number of calls tracked.
     *
     * @return total call count
     */
    int callCount();
}
