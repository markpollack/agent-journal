package io.github.markpollack.journal.call;

import io.github.markpollack.journal.metric.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link CallTracker}.
 *
 * <p>Thread-safe implementation that maintains a tree of calls and tracks
 * the currently active call stack.
 */
public final class DefaultCallTracker implements CallTracker {

    private final List<DefaultCall> rootCalls;
    private final List<DefaultCall> allCalls;
    private final ThreadLocal<Stack<DefaultCall>> callStack;

    /**
     * Creates a new call tracker.
     */
    public DefaultCallTracker() {
        this.rootCalls = new CopyOnWriteArrayList<>();
        this.allCalls = new CopyOnWriteArrayList<>();
        this.callStack = ThreadLocal.withInitial(Stack::new);
    }

    @Override
    public Call startCall(String operation, Tags tags) {
        DefaultCall call = new DefaultCall(operation, tags, null, this);
        rootCalls.add(call);
        allCalls.add(call);
        callStack.get().push(call);
        return call;
    }

    @Override
    public List<Call> rootCalls() {
        return Collections.unmodifiableList(new ArrayList<>(rootCalls));
    }

    @Override
    public List<Call> allCalls() {
        return Collections.unmodifiableList(new ArrayList<>(allCalls));
    }

    @Override
    public Call currentCall() {
        Stack<DefaultCall> stack = callStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    @Override
    public int callCount() {
        return allCalls.size();
    }

    /**
     * Registers a child call (called by DefaultCall.child()).
     *
     * @param call the call to register
     */
    void registerCall(DefaultCall call) {
        allCalls.add(call);
        callStack.get().push(call);
    }

    /**
     * Called when a call completes (called by DefaultCall.close() or fail()).
     *
     * @param call the completed call
     */
    void onCallComplete(DefaultCall call) {
        Stack<DefaultCall> stack = callStack.get();
        // Pop from stack if it's the current call
        if (!stack.isEmpty() && stack.peek() == call) {
            stack.pop();
        }
    }

    /**
     * Returns all completed calls.
     */
    public List<Call> completedCalls() {
        return allCalls.stream()
                .filter(Call::isComplete)
                .map(c -> (Call) c)
                .toList();
    }

    /**
     * Returns all failed calls.
     */
    public List<Call> failedCalls() {
        return allCalls.stream()
                .filter(Call::isFailed)
                .map(c -> (Call) c)
                .toList();
    }

    /**
     * Clears all tracked calls.
     * Primarily useful for testing.
     */
    public void clear() {
        rootCalls.clear();
        allCalls.clear();
        callStack.get().clear();
    }
}
