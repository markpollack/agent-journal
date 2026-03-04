/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.event;

import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.test.BaseTrackingTest;
import io.github.markpollack.journal.test.TestEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JSON serialization round-trips for all event types.
 * Verifies that events can be serialized to JSON and deserialized back
 * with all data preserved.
 */
@DisplayName("Event Serialization Round-Trip")
class EventSerializationRoundTripTest extends BaseTrackingTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Nested
    @DisplayName("LLMCallEvent")
    class LLMCallEventTests {

        @Test
        @DisplayName("round-trips minimal event")
        void roundTripsMinimal() throws Exception {
            LLMCallEvent original = TestEvents.llmCall();

            String json = objectMapper.writeValueAsString(original);
            LLMCallEvent deserialized = objectMapper.readValue(json, LLMCallEvent.class);

            assertThat(deserialized.model()).isEqualTo(original.model());
            assertThat(deserialized.tokenUsage().inputTokens()).isEqualTo(original.tokenUsage().inputTokens());
            assertThat(deserialized.tokenUsage().outputTokens()).isEqualTo(original.tokenUsage().outputTokens());
        }

        @Test
        @DisplayName("round-trips complete event")
        void roundTripsComplete() throws Exception {
            LLMCallEvent original = TestEvents.llmCallComplete();

            String json = objectMapper.writeValueAsString(original);
            LLMCallEvent deserialized = objectMapper.readValue(json, LLMCallEvent.class);

            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("round-trips event with thinking tokens")
        void roundTripsWithThinking() throws Exception {
            LLMCallEvent original = TestEvents.llmCallWithThinking();

            String json = objectMapper.writeValueAsString(original);
            LLMCallEvent deserialized = objectMapper.readValue(json, LLMCallEvent.class);

            assertThat(deserialized.tokenUsage().thinkingTokens())
                    .isEqualTo(original.tokenUsage().thinkingTokens());
        }

        @Test
        @DisplayName("round-trips event with cache usage")
        void roundTripsWithCache() throws Exception {
            LLMCallEvent original = TestEvents.llmCallWithCache();

            String json = objectMapper.writeValueAsString(original);
            LLMCallEvent deserialized = objectMapper.readValue(json, LLMCallEvent.class);

            assertThat(deserialized.tokenUsage().cacheCreationTokens())
                    .isEqualTo(original.tokenUsage().cacheCreationTokens());
            assertThat(deserialized.tokenUsage().cacheReadTokens())
                    .isEqualTo(original.tokenUsage().cacheReadTokens());
        }
    }

    @Nested
    @DisplayName("ToolCallEvent")
    class ToolCallEventTests {

        @Test
        @DisplayName("round-trips successful tool call")
        void roundTripsSuccess() throws Exception {
            ToolCallEvent original = TestEvents.bashSuccess();

            String json = objectMapper.writeValueAsString(original);
            ToolCallEvent deserialized = objectMapper.readValue(json, ToolCallEvent.class);

            assertThat(deserialized.toolName()).isEqualTo(original.toolName());
            assertThat(deserialized.success()).isEqualTo(original.success());
            assertThat(deserialized.durationMs()).isEqualTo(original.durationMs());
        }

        @Test
        @DisplayName("round-trips failed tool call")
        void roundTripsFailure() throws Exception {
            ToolCallEvent original = TestEvents.toolFailure();

            String json = objectMapper.writeValueAsString(original);
            ToolCallEvent deserialized = objectMapper.readValue(json, ToolCallEvent.class);

            assertThat(deserialized.success()).isFalse();
            assertThat(deserialized.errorMessage()).isEqualTo(original.errorMessage());
        }
    }

    @Nested
    @DisplayName("StateChangeEvent")
    class StateChangeEventTests {

        @Test
        @DisplayName("round-trips state change")
        void roundTrips() throws Exception {
            StateChangeEvent original = TestEvents.stateChangePlanToImplement();

            String json = objectMapper.writeValueAsString(original);
            StateChangeEvent deserialized = objectMapper.readValue(json, StateChangeEvent.class);

            assertThat(deserialized).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("MetricEvent")
    class MetricEventTests {

        @Test
        @DisplayName("round-trips metric without tags")
        void roundTripsWithoutTags() throws Exception {
            MetricEvent original = TestEvents.tokenMetric();

            String json = objectMapper.writeValueAsString(original);
            MetricEvent deserialized = objectMapper.readValue(json, MetricEvent.class);

            assertThat(deserialized.name()).isEqualTo(original.name());
            assertThat(deserialized.value()).isEqualTo(original.value());
        }

        @Test
        @DisplayName("round-trips metric with tags")
        void roundTripsWithTags() throws Exception {
            MetricEvent original = TestEvents.metricWithTags();

            String json = objectMapper.writeValueAsString(original);
            MetricEvent deserialized = objectMapper.readValue(json, MetricEvent.class);

            assertThat(deserialized.name()).isEqualTo(original.name());
            assertThat(deserialized.tags().get("model")).isEqualTo(original.tags().get("model"));
        }
    }

    @Nested
    @DisplayName("CustomEvent")
    class CustomEventTests {

        @Test
        @DisplayName("round-trips custom event")
        void roundTrips() throws Exception {
            CustomEvent original = TestEvents.checkpointEvent();

            String json = objectMapper.writeValueAsString(original);
            CustomEvent deserialized = objectMapper.readValue(json, CustomEvent.class);

            assertThat(deserialized.name()).isEqualTo(original.name());
            assertThat(deserialized.attributes()).isEqualTo(original.attributes());
        }
    }

    @Nested
    @DisplayName("Value Types")
    class ValueTypeTests {

        @Test
        @DisplayName("TokenUsage round-trips")
        void tokenUsageRoundTrips() throws Exception {
            TokenUsage original = new TokenUsage(1200, 450, 100, 50, 30, 20);

            String json = objectMapper.writeValueAsString(original);
            TokenUsage deserialized = objectMapper.readValue(json, TokenUsage.class);

            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("CostBreakdown round-trips")
        void costBreakdownRoundTrips() throws Exception {
            CostBreakdown original = new CostBreakdown(0.015, 0.045, 0.010, 0.005);

            String json = objectMapper.writeValueAsString(original);
            CostBreakdown deserialized = objectMapper.readValue(json, CostBreakdown.class);

            assertThat(deserialized).isEqualTo(original);
        }

        @Test
        @DisplayName("TimingInfo round-trips")
        void timingInfoRoundTrips() throws Exception {
            TimingInfo original = new TimingInfo(2500, 2200, 800);

            String json = objectMapper.writeValueAsString(original);
            TimingInfo deserialized = objectMapper.readValue(json, TimingInfo.class);

            assertThat(deserialized).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("Tags")
    class TagsTests {

        @Test
        @DisplayName("Tags round-trips")
        void tagsRoundTrips() throws Exception {
            Tags original = Tags.of("model", "claude-opus-4.5", "provider", "anthropic");

            String json = objectMapper.writeValueAsString(original);
            Tags deserialized = objectMapper.readValue(json, Tags.class);

            assertThat(deserialized.get("model")).isEqualTo(original.get("model"));
            assertThat(deserialized.get("provider")).isEqualTo(original.get("provider"));
        }
    }

    @Nested
    @DisplayName("GitPatchEvent")
    class GitPatchEventTests {

        @Test
        @DisplayName("round-trips simple patch")
        void roundTripsSimplePatch() throws Exception {
            GitPatchEvent original = TestEvents.gitPatch();

            String json = objectMapper.writeValueAsString(original);
            GitPatchEvent deserialized = objectMapper.readValue(json, GitPatchEvent.class);

            assertThat(deserialized.baseBranch()).isEqualTo(original.baseBranch());
            assertThat(deserialized.linesAdded()).isEqualTo(original.linesAdded());
            assertThat(deserialized.linesRemoved()).isEqualTo(original.linesRemoved());
            assertThat(deserialized.fileChanges()).hasSize(original.fileChanges().size());
        }

        @Test
        @DisplayName("round-trips patch with content")
        void roundTripsPatchWithContent() throws Exception {
            GitPatchEvent original = TestEvents.gitPatchWithContent();

            String json = objectMapper.writeValueAsString(original);
            GitPatchEvent deserialized = objectMapper.readValue(json, GitPatchEvent.class);

            assertThat(deserialized.patchContent()).isEqualTo(original.patchContent());
        }
    }

    @Nested
    @DisplayName("GitCommitEvent")
    class GitCommitEventTests {

        @Test
        @DisplayName("round-trips simple commit")
        void roundTripsSimpleCommit() throws Exception {
            GitCommitEvent original = TestEvents.gitCommit();

            String json = objectMapper.writeValueAsString(original);
            GitCommitEvent deserialized = objectMapper.readValue(json, GitCommitEvent.class);

            assertThat(deserialized.sha()).isEqualTo(original.sha());
            assertThat(deserialized.shortSha()).isEqualTo(original.shortSha());
            assertThat(deserialized.message()).isEqualTo(original.message());
            assertThat(deserialized.branch()).isEqualTo(original.branch());
        }

        @Test
        @DisplayName("round-trips complete commit")
        void roundTripsCompleteCommit() throws Exception {
            GitCommitEvent original = TestEvents.gitCommitComplete();

            String json = objectMapper.writeValueAsString(original);
            GitCommitEvent deserialized = objectMapper.readValue(json, GitCommitEvent.class);

            assertThat(deserialized.filesChanged()).isEqualTo(original.filesChanged());
            assertThat(deserialized.linesAdded()).isEqualTo(original.linesAdded());
            assertThat(deserialized.linesRemoved()).isEqualTo(original.linesRemoved());
        }
    }

    @Nested
    @DisplayName("GitBranchEvent")
    class GitBranchEventTests {

        @Test
        @DisplayName("round-trips branch created")
        void roundTripsBranchCreated() throws Exception {
            GitBranchEvent original = TestEvents.gitBranchCreated();

            String json = objectMapper.writeValueAsString(original);
            GitBranchEvent deserialized = objectMapper.readValue(json, GitBranchEvent.class);

            assertThat(deserialized.branchName()).isEqualTo(original.branchName());
            assertThat(deserialized.action()).isEqualTo(original.action());
            assertThat(deserialized.fromRef()).isEqualTo(original.fromRef());
        }

        @Test
        @DisplayName("round-trips branch checkout")
        void roundTripsBranchCheckout() throws Exception {
            GitBranchEvent original = TestEvents.gitBranchCheckedOut();

            String json = objectMapper.writeValueAsString(original);
            GitBranchEvent deserialized = objectMapper.readValue(json, GitBranchEvent.class);

            assertThat(deserialized.action()).isEqualTo(original.action());
            assertThat(deserialized.fromRef()).isNull();
        }
    }

    @Nested
    @DisplayName("GitPullRequestEvent")
    class GitPullRequestEventTests {

        @Test
        @DisplayName("round-trips PR created")
        void roundTripsPrCreated() throws Exception {
            GitPullRequestEvent original = TestEvents.gitPrCreated();

            String json = objectMapper.writeValueAsString(original);
            GitPullRequestEvent deserialized = objectMapper.readValue(json, GitPullRequestEvent.class);

            assertThat(deserialized.prNumber()).isEqualTo(original.prNumber());
            assertThat(deserialized.prUrl()).isEqualTo(original.prUrl());
            assertThat(deserialized.title()).isEqualTo(original.title());
            assertThat(deserialized.sourceBranch()).isEqualTo(original.sourceBranch());
            assertThat(deserialized.targetBranch()).isEqualTo(original.targetBranch());
            assertThat(deserialized.action()).isEqualTo(original.action());
        }

        @Test
        @DisplayName("round-trips PR merged")
        void roundTripsPrMerged() throws Exception {
            GitPullRequestEvent original = TestEvents.gitPrMerged();

            String json = objectMapper.writeValueAsString(original);
            GitPullRequestEvent deserialized = objectMapper.readValue(json, GitPullRequestEvent.class);

            assertThat(deserialized.action()).isEqualTo(original.action());
        }
    }
}
