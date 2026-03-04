/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.call;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.test.BaseTrackingTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for CallTracker and Call functionality.
 */
@DisplayName("CallTracker")
class CallTrackerTest extends BaseTrackingTest {

    @BeforeEach
    void setUp() {
        Journal.reset();
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Nested
    @DisplayName("Basic Call Operations")
    class BasicCallOperations {

        @Test
        @DisplayName("starts a root call")
        void startsRootCall() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("test-operation");

            assertThat(call).isNotNull();
            assertThat(call.operation()).isEqualTo("test-operation");
            assertThat(call.parent()).isNull();
            assertThat(call.isComplete()).isFalse();
        }

        @Test
        @DisplayName("starts call with tags")
        void startsCallWithTags() {
            DefaultCallTracker tracker = new DefaultCallTracker();
            Tags tags = Tags.of("key", "value");

            Call call = tracker.startCall("tagged-op", tags);

            assertThat(call.tags()).isEqualTo(tags);
        }

        @Test
        @DisplayName("closes call and records duration")
        void closesCallWithDuration() throws InterruptedException {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("timed-op");
            Thread.sleep(10); // Wait a bit
            call.close();

            assertThat(call.isComplete()).isTrue();
            assertThat(call.isFailed()).isFalse();
            assertThat(call.duration()).isNotNull();
            assertThat(call.duration().toMillis()).isGreaterThanOrEqualTo(10);
        }

        @Test
        @DisplayName("marks call as failed")
        void failsCall() {
            DefaultCallTracker tracker = new DefaultCallTracker();
            RuntimeException error = new RuntimeException("Test failure");

            Call call = tracker.startCall("failing-op");
            call.fail(error);

            assertThat(call.isComplete()).isTrue();
            assertThat(call.isFailed()).isTrue();
            assertThat(call.failureCause()).isSameAs(error);
        }

        @Test
        @DisplayName("generates unique call IDs")
        void generatesUniqueIds() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call1 = tracker.startCall("op1");
            Call call2 = tracker.startCall("op2");

            assertThat(call1.id()).isNotEqualTo(call2.id());
        }
    }

    @Nested
    @DisplayName("Call Hierarchy")
    class CallHierarchy {

        @Test
        @DisplayName("creates child calls")
        void createsChildCalls() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call parent = tracker.startCall("parent");
            Call child = parent.child("child");

            assertThat(child.parent()).isSameAs(parent);
            assertThat(child.operation()).isEqualTo("child");
        }

        @Test
        @DisplayName("creates nested children")
        void createsNestedChildren() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call root = tracker.startCall("root");
            Call level1 = root.child("level-1");
            Call level2 = level1.child("level-2");

            assertThat(root.parent()).isNull();
            assertThat(level1.parent()).isSameAs(root);
            assertThat(level2.parent()).isSameAs(level1);
        }

        @Test
        @DisplayName("creates child with tags")
        void createsChildWithTags() {
            DefaultCallTracker tracker = new DefaultCallTracker();
            Tags childTags = Tags.of("turn", "1");

            Call parent = tracker.startCall("parent");
            Call child = parent.child("child", childTags);

            assertThat(child.tags()).isEqualTo(childTags);
        }

        @Test
        @DisplayName("tracks all calls including children")
        void tracksAllCalls() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call root = tracker.startCall("root");
            Call child1 = root.child("child-1");
            Call child2 = root.child("child-2");
            Call grandchild = child1.child("grandchild");

            assertThat(tracker.callCount()).isEqualTo(4);
            assertThat(tracker.rootCalls()).hasSize(1);
            assertThat(tracker.allCalls()).hasSize(4);
        }

        @Test
        @DisplayName("prevents child creation on completed call")
        void preventsChildOnCompleted() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call parent = tracker.startCall("parent");
            parent.close();

            assertThatThrownBy(() -> parent.child("child"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed");
        }
    }

    @Nested
    @DisplayName("Call Attributes")
    class CallAttributes {

        @Test
        @DisplayName("sets and retrieves attributes")
        void setsAndGetsAttributes() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("op");
            call.setAttribute("model", "claude-opus");
            call.setAttribute("tokens", 1500);

            assertThat(call.attributes())
                    .containsEntry("model", "claude-opus")
                    .containsEntry("tokens", 1500);
        }

        @Test
        @DisplayName("attributes are immutable view")
        void attributesAreImmutable() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("op");
            call.setAttribute("key", "value");

            Map<String, Object> attrs = call.attributes();
            assertThatThrownBy(() -> attrs.put("new", "value"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("prevents attributes on completed call")
        void preventsAttributesOnCompleted() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("op");
            call.close();

            assertThatThrownBy(() -> call.setAttribute("key", "value"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Call Events")
    class CallEvents {

        @Test
        @DisplayName("logs events within call")
        void logsEvents() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            DefaultCall call = (DefaultCall) tracker.startCall("op");
            call.event("step-1");
            call.event("step-2", Map.of("detail", "value"));

            assertThat(call.events()).hasSize(2);
            assertThat(call.events().get(0).name()).isEqualTo("step-1");
            assertThat(call.events().get(1).name()).isEqualTo("step-2");
            assertThat(call.events().get(1).attributes()).containsEntry("detail", "value");
        }

        @Test
        @DisplayName("prevents events on completed call")
        void preventsEventsOnCompleted() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("op");
            call.close();

            assertThatThrownBy(() -> call.event("late-event"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Current Call Journal")
    class CurrentCallTracking {

        @Test
        @DisplayName("tracks current call")
        void tracksCurrentCall() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            assertThat(tracker.currentCall()).isNull();

            Call call1 = tracker.startCall("call-1");
            assertThat(tracker.currentCall()).isSameAs(call1);

            Call call2 = call1.child("call-2");
            assertThat(tracker.currentCall()).isSameAs(call2);

            call2.close();
            assertThat(tracker.currentCall()).isSameAs(call1);

            call1.close();
            assertThat(tracker.currentCall()).isNull();
        }
    }

    @Nested
    @DisplayName("Integration with Run")
    class IntegrationWithRun {

        @Test
        @DisplayName("run provides call tracker")
        void runProvidesCallTracker() {
            try (Run run = Journal.run("test-experiment").start()) {
                CallTracker calls = run.calls();

                assertThat(calls).isNotNull();
                assertThat(calls.callCount()).isZero();
            }
        }

        @Test
        @DisplayName("tracks calls within run")
        void tracksCallsWithinRun() {
            try (Run run = Journal.run("test-experiment").start()) {
                try (Call mainLoop = run.calls().startCall("main-loop")) {
                    try (Call step1 = mainLoop.child("step-1")) {
                        // Do step 1
                    }
                    try (Call step2 = mainLoop.child("step-2")) {
                        // Do step 2
                    }
                }

                assertThat(run.calls().callCount()).isEqualTo(3);
                assertThat(run.calls().rootCalls()).hasSize(1);
            }
        }

        @Test
        @DisplayName("multiple root calls within run")
        void multipleRootCalls() {
            try (Run run = Journal.run("test-experiment").start()) {
                try (Call phase1 = run.calls().startCall("phase-1")) {
                    // Phase 1
                }
                try (Call phase2 = run.calls().startCall("phase-2")) {
                    // Phase 2
                }

                assertThat(run.calls().rootCalls()).hasSize(2);
            }
        }
    }

    @Nested
    @DisplayName("Timing Accuracy")
    class TimingAccuracy {

        @Test
        @DisplayName("records accurate timing")
        void recordsAccurateTiming() throws InterruptedException {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("timed-op");
            Thread.sleep(50);
            call.close();

            // Should be at least 50ms, allow some margin
            assertThat(call.duration().toMillis()).isBetween(50L, 200L);
        }

        @Test
        @DisplayName("child timing within parent timing")
        void childTimingWithinParent() throws InterruptedException {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call parent = tracker.startCall("parent");
            Thread.sleep(20);

            Call child = parent.child("child");
            Thread.sleep(30);
            child.close();

            Thread.sleep(20);
            parent.close();

            // Parent should be longer than child
            assertThat(parent.duration().toMillis()).isGreaterThan(child.duration().toMillis());
            // Child should be at least 30ms
            assertThat(child.duration().toMillis()).isGreaterThanOrEqualTo(30);
        }

        @Test
        @DisplayName("startTime and endTime are captured")
        void capturesTimestamps() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            Call call = tracker.startCall("op");
            assertThat(call.startTime()).isNotNull();
            assertThat(call.endTime()).isNull();

            call.close();
            assertThat(call.endTime()).isNotNull();
            assertThat(call.endTime()).isAfterOrEqualTo(call.startTime());
        }
    }

    @Nested
    @DisplayName("Try-With-Resources")
    class TryWithResources {

        @Test
        @DisplayName("auto-closes in try-with-resources")
        void autoClosesInTry() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            try (Call call = tracker.startCall("auto-close")) {
                assertThat(call.isComplete()).isFalse();
            }

            assertThat(tracker.allCalls().get(0).isComplete()).isTrue();
        }

        @Test
        @DisplayName("nested try-with-resources closes in order")
        void nestedTryClosesInOrder() {
            DefaultCallTracker tracker = new DefaultCallTracker();

            try (Call parent = tracker.startCall("parent")) {
                try (Call child = parent.child("child")) {
                    assertThat(parent.isComplete()).isFalse();
                    assertThat(child.isComplete()).isFalse();
                }
                assertThat(((DefaultCall) parent).children().get(0).isComplete()).isTrue();
            }

            assertThat(tracker.allCalls()).allMatch(Call::isComplete);
        }
    }
}
