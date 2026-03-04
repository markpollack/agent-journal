/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.test;

import io.github.markpollack.journal.Config;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.event.*;
import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.test.TestRuns.TestRunData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the test infrastructure itself.
 * Ensures test fixtures work correctly before using them in feature tests.
 */
@DisplayName("Test Infrastructure")
class TestInfrastructureTest extends BaseTrackingTest {

    @Nested
    @DisplayName("TestEvents factory")
    class TestEventsTests {

        @Test
        @DisplayName("creates valid LLMCallEvent")
        void createsLLMCallEvent() {
            LLMCallEvent event = TestEvents.llmCall();

            assertThat(event.model()).isEqualTo(TestEvents.DEFAULT_MODEL);
            assertThat(event.type()).isEqualTo("llm_call");
            assertThat(event.timestamp()).isNotNull();
            assertThat(event.totalTokens()).isGreaterThan(0);
        }

        @Test
        @DisplayName("creates LLMCallEvent with thinking tokens")
        void createsLLMCallWithThinking() {
            LLMCallEvent event = TestEvents.llmCallWithThinking();

            assertThat(event.tokenUsage()).isNotNull();
            assertThat(event.tokenUsage().thinkingTokens()).isEqualTo(500);
            assertThat(event.finishReason()).isEqualTo("stop");
        }

        @Test
        @DisplayName("creates LLMCallEvent with cache usage")
        void createsLLMCallWithCache() {
            LLMCallEvent event = TestEvents.llmCallWithCache();

            assertThat(event.tokenUsage().cacheCreationTokens()).isEqualTo(500);
            assertThat(event.tokenUsage().cacheReadTokens()).isEqualTo(300);
            assertThat(event.tokenUsage().cacheHitRatio()).isGreaterThan(0);
        }

        @Test
        @DisplayName("creates valid ToolCallEvents")
        void createsToolCallEvents() {
            ToolCallEvent bash = TestEvents.bashSuccess();
            ToolCallEvent read = TestEvents.readSuccess();
            ToolCallEvent failure = TestEvents.toolFailure();

            assertThat(bash.toolName()).isEqualTo("Bash");
            assertThat(bash.success()).isTrue();
            assertThat(bash.type()).isEqualTo("tool_call");

            assertThat(read.toolName()).isEqualTo("Read");
            assertThat(read.success()).isTrue();

            assertThat(failure.success()).isFalse();
            assertThat(failure.errorMessage()).isNotNull();
        }

        @Test
        @DisplayName("creates valid StateChangeEvent")
        void createsStateChangeEvent() {
            StateChangeEvent event = TestEvents.stateChangePlanToImplement();

            assertThat(event.fromState()).isEqualTo("planning");
            assertThat(event.toState()).isEqualTo("implementing");
            assertThat(event.reason()).isNotNull();
            assertThat(event.type()).isEqualTo("state_change");
        }

        @Test
        @DisplayName("creates valid MetricEvent")
        void createsMetricEvent() {
            MetricEvent event = TestEvents.tokenMetric();

            assertThat(event.name()).isEqualTo("tokens.total");
            assertThat(event.value()).isEqualTo(1650);
            assertThat(event.type()).isEqualTo("metric");
        }

        @Test
        @DisplayName("creates MetricEvent with tags")
        void createsMetricWithTags() {
            MetricEvent event = TestEvents.metricWithTags();

            assertThat(event.tags()).isNotNull();
            assertThat(event.tags().isEmpty()).isFalse();
            assertThat(event.tags().get("model")).isEqualTo(TestEvents.DEFAULT_MODEL);
        }

        @Test
        @DisplayName("creates valid CustomEvent")
        void createsCustomEvent() {
            CustomEvent event = TestEvents.checkpointEvent();

            assertThat(event.name()).isEqualTo("checkpoint");
            assertThat(event.type()).isEqualTo("checkpoint");
            assertThat(event.attributes()).containsKey("phase");
        }

        @Test
        @DisplayName("allEventTypes returns one of each type")
        void allEventTypesReturnsAllTypes() {
            List<JournalEvent> events = TestEvents.allEventTypes();

            assertThat(events).hasSize(9);
            assertThat(events).anyMatch(e -> e instanceof LLMCallEvent);
            assertThat(events).anyMatch(e -> e instanceof ToolCallEvent);
            assertThat(events).anyMatch(e -> e instanceof StateChangeEvent);
            assertThat(events).anyMatch(e -> e instanceof MetricEvent);
            assertThat(events).anyMatch(e -> e instanceof CustomEvent);
            assertThat(events).anyMatch(e -> e instanceof GitPatchEvent);
            assertThat(events).anyMatch(e -> e instanceof GitCommitEvent);
            assertThat(events).anyMatch(e -> e instanceof GitBranchEvent);
            assertThat(events).anyMatch(e -> e instanceof GitPullRequestEvent);
        }

        @Test
        @DisplayName("agentTurnSequence returns realistic event flow")
        void agentTurnSequenceIsRealistic() {
            List<JournalEvent> events = TestEvents.agentTurnSequence();

            assertThat(events).hasSizeGreaterThan(5);
            // First event should be state change
            assertThat(events.get(0)).isInstanceOf(StateChangeEvent.class);
        }
    }

    @Nested
    @DisplayName("TestRuns factory")
    class TestRunsTests {

        @Test
        @DisplayName("creates successful run")
        void createsSuccessfulRun() {
            TestRunData run = TestRuns.successfulRun();

            assertThat(run.id()).startsWith("run-");
            assertThat(run.experimentId()).isEqualTo(TestRuns.DEFAULT_EXPERIMENT_ID);
            assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
            assertThat(run.error()).isNull();
            assertThat(run.events()).isNotEmpty();
            assertThat(run.duration().toSeconds()).isGreaterThan(0);
        }

        @Test
        @DisplayName("creates failed run")
        void createsFailedRun() {
            TestRunData run = TestRuns.failedRun();

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.error()).isNotNull();
            assertThat(run.summary()).containsEntry("success", false);
        }

        @Test
        @DisplayName("creates init run")
        void createsInitRun() {
            TestRunData run = TestRuns.initRun();

            assertThat(run.status()).isEqualTo(RunStatus.INIT);
            assertThat(run.config().isEmpty()).isTrue();
            assertThat(run.events()).isEmpty();
        }

        @Test
        @DisplayName("creates running run")
        void createsRunningRun() {
            TestRunData run = TestRuns.runningRun();

            assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(run.finishedAt()).isNull();
            assertThat(run.events()).isNotEmpty();
        }

        @Test
        @DisplayName("creates retry chain with correct links")
        void createsRetryChain() {
            List<TestRunData> chain = TestRuns.retryChain();

            assertThat(chain).hasSize(3);

            // First run has no previous
            assertThat(chain.get(0).previousRunId()).isNull();
            assertThat(chain.get(0).status()).isEqualTo(RunStatus.FAILED);

            // Second run links to first
            assertThat(chain.get(1).previousRunId()).isEqualTo(chain.get(0).id());
            assertThat(chain.get(1).status()).isEqualTo(RunStatus.FAILED);

            // Third run links to second and is successful
            assertThat(chain.get(2).previousRunId()).isEqualTo(chain.get(1).id());
            assertThat(chain.get(2).status()).isEqualTo(RunStatus.FINISHED);
        }

        @Test
        @DisplayName("creates multi-agent runs with parent links")
        void createsMultiAgentRuns() {
            List<TestRunData> runs = TestRuns.multiAgentRuns();

            assertThat(runs).hasSize(3);

            TestRunData parent = runs.get(0);
            TestRunData child1 = runs.get(1);
            TestRunData child2 = runs.get(2);

            // Parent has no parent
            assertThat(parent.parentRunId()).isNull();
            assertThat(parent.agentId()).isEqualTo("supervisor");

            // Children link to parent
            assertThat(child1.parentRunId()).isEqualTo(parent.id());
            assertThat(child2.parentRunId()).isEqualTo(parent.id());
        }

        @Test
        @DisplayName("builder creates customized run")
        void builderCreatesCustomRun() {
            TestRunData run = TestRuns.builder()
                    .experimentId("custom-experiment")
                    .taskId("custom-task")
                    .status(RunStatus.CRASHED)
                    .build();

            assertThat(run.experimentId()).isEqualTo("custom-experiment");
            assertThat(run.taskId()).isEqualTo("custom-task");
            assertThat(run.status()).isEqualTo(RunStatus.CRASHED);
        }
    }

    @Nested
    @DisplayName("Core types")
    class CoreTypesTests {

        @Test
        @DisplayName("Config is immutable")
        void configIsImmutable() {
            Config config = Config.of("key", "value");
            Config updated = config.with("newKey", "newValue");

            // Original unchanged
            assertThat(config.values()).hasSize(1);
            assertThat(config.values()).doesNotContainKey("newKey");

            // New instance has both
            assertThat(updated.values()).hasSize(2);
            assertThat(updated.values()).containsKey("newKey");
        }

        @Test
        @DisplayName("Config builder works")
        void configBuilderWorks() {
            Config config = Config.builder()
                    .set("model", "claude-opus-4.5")
                    .set("maxTokens", 4000)
                    .set("temperature", 0.7)
                    .build();

            assertThat(config.get("model", String.class)).isEqualTo("claude-opus-4.5");
            assertThat(config.get("maxTokens", Integer.class)).isEqualTo(4000);
            assertThat(config.get("temperature", Double.class)).isEqualTo(0.7);
        }

        @Test
        @DisplayName("Tags is immutable")
        void tagsIsImmutable() {
            Tags tags = Tags.of("key", "value");
            Tags updated = tags.and("newKey", "newValue");

            assertThat(tags.size()).isEqualTo(1);
            assertThat(tags.contains("newKey")).isFalse();

            assertThat(updated.size()).isEqualTo(2);
            assertThat(updated.contains("newKey")).isTrue();
        }

        @Test
        @DisplayName("RunStatus terminal states")
        void runStatusTerminalStates() {
            assertThat(RunStatus.INIT.isTerminal()).isFalse();
            assertThat(RunStatus.RUNNING.isTerminal()).isFalse();
            assertThat(RunStatus.FINISHED.isTerminal()).isTrue();
            assertThat(RunStatus.FAILED.isTerminal()).isTrue();
            assertThat(RunStatus.CRASHED.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("RunStatus successful states")
        void runStatusSuccessfulStates() {
            assertThat(RunStatus.FINISHED.isSuccessful()).isTrue();
            assertThat(RunStatus.FAILED.isSuccessful()).isFalse();
            assertThat(RunStatus.CRASHED.isSuccessful()).isFalse();
        }
    }

    @Nested
    @DisplayName("Event serialization")
    class EventSerializationTests {

        @Test
        @DisplayName("LLMCallEvent toMap includes all fields")
        void llmCallEventToMap() {
            LLMCallEvent event = TestEvents.llmCallComplete();
            var map = event.toMap();

            assertThat(map).containsKey("type");
            assertThat(map).containsKey("timestamp");
            assertThat(map).containsKey("model");
            assertThat(map).containsKey("provider");
            assertThat(map).containsKey("token_usage");
            assertThat(map).containsKey("cost");
            assertThat(map).containsKey("timing");
            assertThat(map).containsKey("finish_reason");
            assertThat(map).containsKey("response_id");
        }

        @Test
        @DisplayName("TokenUsage toMap includes all non-zero fields")
        void tokenUsageToMap() {
            TokenUsage usage = new TokenUsage(1200, 450, 100, 50, 30, 20);
            var map = usage.toMap();

            assertThat(map).containsEntry("input_tokens", 1200);
            assertThat(map).containsEntry("output_tokens", 450);
            assertThat(map).containsEntry("thinking_tokens", 100);
            assertThat(map).containsEntry("cache_creation_tokens", 50);
            assertThat(map).containsEntry("cache_read_tokens", 30);
            assertThat(map).containsEntry("tool_use_tokens", 20);
            assertThat(map).containsEntry("total_tokens", 1750);
        }

        @Test
        @DisplayName("CostBreakdown toMap calculates total correctly")
        void costBreakdownToMap() {
            CostBreakdown cost = new CostBreakdown(0.015, 0.045, 0.010, 0.005);
            var map = cost.toMap();

            assertThat(map).containsEntry("input_cost_usd", 0.015);
            assertThat(map).containsEntry("output_cost_usd", 0.045);
            assertThat(map).containsEntry("thinking_cost_usd", 0.010);
            assertThat(map).containsEntry("cache_savings_usd", 0.005);
            assertThat((Double) map.get("total_cost_usd")).isCloseTo(0.065, org.assertj.core.data.Offset.offset(0.0001));
        }
    }

    @Nested
    @DisplayName("BaseTrackingTest setup")
    class BaseTestSetupTests {

        @Test
        @DisplayName("tempDir is available")
        void tempDirIsAvailable() {
            assertThat(tempDir).isNotNull();
            assertThat(tempDir).exists();
        }

        @Test
        @DisplayName("getStoragePath returns .agent-journal under tempDir")
        void getStoragePathWorks() {
            assertThat(getStoragePath()).isEqualTo(tempDir.resolve(".agent-journal"));
        }
    }
}
