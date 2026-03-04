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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default implementation of {@link Call}.
 *
 * <p>Thread-safe implementation that tracks call hierarchy, timing,
 * and attributes.
 */
public final class DefaultCall implements Call {

    private final String id;
    private final String operation;
    private final Tags tags;
    private final Call parent;
    private final Instant startTime;
    private final DefaultCallTracker tracker;
    private final List<DefaultCall> children;
    private final Map<String, Object> attributes;
    private final List<CallEvent> events;

    private volatile Instant endTime;
    private volatile Throwable failureCause;

    /**
     * Creates a new call.
     *
     * @param operation the operation name
     * @param tags call tags
     * @param parent the parent call, or null for root calls
     * @param tracker the call tracker managing this call
     */
    DefaultCall(String operation, Tags tags, Call parent, DefaultCallTracker tracker) {
        this.id = UUID.randomUUID().toString();
        this.operation = operation;
        this.tags = tags != null ? tags : Tags.empty();
        this.parent = parent;
        this.startTime = Instant.now();
        this.tracker = tracker;
        this.children = new CopyOnWriteArrayList<>();
        this.attributes = Collections.synchronizedMap(new LinkedHashMap<>());
        this.events = new CopyOnWriteArrayList<>();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String operation() {
        return operation;
    }

    @Override
    public Tags tags() {
        return tags;
    }

    @Override
    public Call parent() {
        return parent;
    }

    @Override
    public Instant startTime() {
        return startTime;
    }

    @Override
    public Instant endTime() {
        return endTime;
    }

    @Override
    public Duration duration() {
        if (endTime == null) {
            return null;
        }
        return Duration.between(startTime, endTime);
    }

    @Override
    public boolean isComplete() {
        return endTime != null;
    }

    @Override
    public boolean isFailed() {
        return failureCause != null;
    }

    @Override
    public Throwable failureCause() {
        return failureCause;
    }

    @Override
    public Call child(String operation, Tags tags) {
        if (isComplete()) {
            throw new IllegalStateException("Cannot create child call on completed call");
        }
        DefaultCall child = new DefaultCall(operation, tags, this, tracker);
        children.add(child);
        tracker.registerCall(child);
        return child;
    }

    @Override
    public void event(String name, Map<String, Object> attributes) {
        if (isComplete()) {
            throw new IllegalStateException("Cannot log event on completed call");
        }
        events.add(new CallEvent(name, attributes, Instant.now()));
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (isComplete()) {
            throw new IllegalStateException("Cannot set attribute on completed call");
        }
        attributes.put(key, value);
    }

    @Override
    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    @Override
    public void fail(Throwable error) {
        if (isComplete()) {
            return; // Ignore if already complete
        }
        this.failureCause = error;
        this.endTime = Instant.now();
        tracker.onCallComplete(this);
    }

    @Override
    public void close() {
        if (isComplete()) {
            return; // Ignore if already complete
        }
        this.endTime = Instant.now();
        tracker.onCallComplete(this);
    }

    /**
     * Returns the children of this call.
     */
    public List<Call> children() {
        return Collections.unmodifiableList(new ArrayList<>(children));
    }

    /**
     * Returns all events logged within this call.
     */
    public List<CallEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    /**
     * Returns the duration in milliseconds, or -1 if still running.
     */
    public long durationMs() {
        Duration d = duration();
        return d != null ? d.toMillis() : -1;
    }

    @Override
    public String toString() {
        String status = isComplete() ? (isFailed() ? "FAILED" : "COMPLETE") : "RUNNING";
        return "Call{operation='" + operation + "', status=" + status + ", duration=" + durationMs() + "ms}";
    }

    /**
     * Represents an event logged within a call.
     */
    public record CallEvent(
            String name,
            Map<String, Object> attributes,
            Instant timestamp
    ) {}
}
