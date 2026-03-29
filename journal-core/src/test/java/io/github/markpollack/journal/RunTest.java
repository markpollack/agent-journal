package io.github.markpollack.journal;

import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.MetricEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.test.BaseTrackingTest;
import io.github.markpollack.journal.test.TestEvents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for Run interface and DefaultRun implementation.
 */
@DisplayName("Run")
class RunTest extends BaseTrackingTest {

    @Nested
    @DisplayName("RunBuilder")
    class RunBuilderTests {

        @Test
        @DisplayName("creates run with experiment")
        void createsRunWithExperiment() {
            Run run = RunBuilder.forExperiment("test-exp").start();

            assertThat(run.experiment().id()).isEqualTo("test-exp");
            assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("sets config via builder")
        void setsConfigViaBuilder() {
            Run run = RunBuilder.forExperiment("test-exp")
                    .config("model", "claude-opus")
                    .config("temperature", 0.7)
                    .start();

            assertThat(run.config().get("model", String.class)).isEqualTo("claude-opus");
            assertThat(run.config().get("temperature", Double.class)).isEqualTo(0.7);
        }

        @Test
        @DisplayName("sets config object")
        void setsConfigObject() {
            Config config = Config.of("model", "gpt-4", "maxTokens", 4000);
            Run run = RunBuilder.forExperiment("test-exp")
                    .config(config)
                    .start();

            assertThat(run.config()).isEqualTo(config);
        }

        @Test
        @DisplayName("sets tags via builder")
        void setsTagsViaBuilder() {
            Run run = RunBuilder.forExperiment("test-exp")
                    .tag("env", "test")
                    .tag("type", "unit")
                    .start();

            assertThat(run.tags().toMap())
                    .containsEntry("env", "test")
                    .containsEntry("type", "unit");
        }

        @Test
        @DisplayName("sets tags object")
        void setsTagsObject() {
            Tags tags = Tags.of("env", "prod");
            Run run = RunBuilder.forExperiment("test-exp")
                    .tags(tags)
                    .start();

            assertThat(run.tags()).isEqualTo(tags);
        }

        @Test
        @DisplayName("sets name")
        void setsName() {
            Run run = RunBuilder.forExperiment("test-exp")
                    .name("my-run")
                    .start();

            assertThat(run.name()).isEqualTo("my-run");
        }

        @Test
        @DisplayName("generates name if not set")
        void generatesNameIfNotSet() {
            Run run = RunBuilder.forExperiment("test-exp").start();

            assertThat(run.name()).startsWith("run-");
        }

        @Test
        @DisplayName("sets agent ID")
        void setsAgentId() {
            Run run = RunBuilder.forExperiment("test-exp")
                    .agent("code-reviewer")
                    .start();

            assertThat(run.agentId()).isEqualTo("code-reviewer");
        }

        @Test
        @DisplayName("sets previous run ID for retry chains")
        void setsPreviousRunId() {
            Run run = RunBuilder.forExperiment("test-exp")
                    .previousRun("prev-run-123")
                    .start();

            assertThat(run.previousRunId()).isEqualTo("prev-run-123");
        }

        @Test
        @DisplayName("sets parent run ID for sub-runs")
        void setsParentRunId() {
            Run run = RunBuilder.forExperiment("test-exp")
                    .parentRun("parent-run-456")
                    .start();

            assertThat(run.parentRunId()).isEqualTo("parent-run-456");
        }

        @Test
        @DisplayName("requires experiment ID")
        void requiresExperimentId() {
            assertThatThrownBy(() -> RunBuilder.forExperiment(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("experimentId");
        }
    }

    @Nested
    @DisplayName("Lifecycle")
    class LifecycleTests {

        @Test
        @DisplayName("starts in RUNNING status")
        void startsInRunningStatus() {
            Run run = RunBuilder.forExperiment("test").start();

            assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        }

        @Test
        @DisplayName("finishes with FINISHED status on close")
        void finishesOnClose() {
            Run run = RunBuilder.forExperiment("test").start();
            run.close();

            assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
        }

        @Test
        @DisplayName("works with try-with-resources")
        void worksWithTryWithResources() {
            Run run;
            try (Run r = RunBuilder.forExperiment("test").start()) {
                run = r;
                assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
            }
            assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
        }

        @Test
        @DisplayName("sets success in summary on normal close")
        void setsSuccessInSummary() {
            Run run = RunBuilder.forExperiment("test").start();
            run.close();

            assertThat(run.summary().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("explicit finish sets custom status")
        void explicitFinishSetsStatus() {
            Run run = RunBuilder.forExperiment("test").start();
            run.finish(RunStatus.CRASHED);

            assertThat(run.status()).isEqualTo(RunStatus.CRASHED);
        }

        @Test
        @DisplayName("close does nothing if already finished")
        void closeDoesNothingIfFinished() {
            Run run = RunBuilder.forExperiment("test").start();
            run.finish(RunStatus.CRASHED);
            run.close();

            assertThat(run.status()).isEqualTo(RunStatus.CRASHED);
        }

        @Test
        @DisplayName("generates unique run ID")
        void generatesUniqueId() {
            Run run1 = RunBuilder.forExperiment("test").start();
            Run run2 = RunBuilder.forExperiment("test").start();

            assertThat(run1.id()).isNotEqualTo(run2.id());
        }

        @Test
        @DisplayName("records start time")
        void recordsStartTime() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();

            assertThat(run.startTime()).isNotNull();
        }

        @Test
        @DisplayName("records end time on close")
        void recordsEndTime() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();
            run.close();

            assertThat(run.endTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Failure Handling")
    class FailureHandlingTests {

        @Test
        @DisplayName("fail sets FAILED status")
        void failSetsStatus() {
            Run run = RunBuilder.forExperiment("test").start();
            run.fail(new RuntimeException("Test error"));

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        @DisplayName("fail records error in summary")
        void failRecordsError() {
            Run run = RunBuilder.forExperiment("test").start();
            run.fail(new IllegalStateException("Something went wrong"));

            assertThat(run.summary().isSuccess()).isFalse();
            assertThat(run.summary().get("error", String.class)).isEqualTo("Something went wrong");
            assertThat(run.summary().get("errorType", String.class)).isEqualTo("java.lang.IllegalStateException");
        }

        @Test
        @DisplayName("fail stores exception")
        void failStoresException() {
            RuntimeException error = new RuntimeException("Test");
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();
            run.fail(error);

            assertThat(run.failureCause()).isSameAs(error);
        }

        @Test
        @DisplayName("fail is idempotent")
        void failIsIdempotent() {
            Run run = RunBuilder.forExperiment("test").start();
            run.fail(new RuntimeException("First"));
            run.fail(new RuntimeException("Second")); // Should be ignored

            assertThat(run.summary().get("error", String.class)).isEqualTo("First");
        }

        @Test
        @DisplayName("close preserves FAILED status")
        void closePreservesFailedStatus() {
            Run run = RunBuilder.forExperiment("test").start();
            run.fail(new RuntimeException("Error"));
            run.close();

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("Event Logging")
    class EventLoggingTests {

        @Test
        @DisplayName("logs LLM call events")
        void logsLlmCallEvents() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();
            LLMCallEvent event = TestEvents.llmCall();

            run.logEvent(event);

            assertThat(run.events()).containsExactly(event);
        }

        @Test
        @DisplayName("logs tool call events")
        void logsToolCallEvents() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();
            ToolCallEvent event = TestEvents.bashSuccess();

            run.logEvent(event);

            assertThat(run.events()).containsExactly(event);
        }

        @Test
        @DisplayName("logs multiple events in order")
        void logsMultipleEvents() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();
            LLMCallEvent event1 = TestEvents.llmCall();
            ToolCallEvent event2 = TestEvents.bashSuccess();

            run.logEvent(event1);
            run.logEvent(event2);

            assertThat(run.events()).containsExactly(event1, event2);
        }

        @Test
        @DisplayName("throws if logging after close")
        void throwsIfLoggingAfterClose() {
            Run run = RunBuilder.forExperiment("test").start();
            run.close();

            assertThatThrownBy(() -> run.logEvent(TestEvents.llmCall()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FINISHED");
        }
    }

    @Nested
    @DisplayName("Metric Logging")
    class MetricLoggingTests {

        @Test
        @DisplayName("logs metric value")
        void logsMetricValue() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();

            run.logMetric("tokens.total", 1500);

            var snapshot = run.inMemoryMetrics().snapshot();
            assertThat(snapshot.gaugeValue("tokens.total")).isEqualTo(1500.0);
        }

        @Test
        @DisplayName("logs metric with tags")
        void logsMetricWithTags() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();

            run.logMetric("cost.usd", 0.05, Tags.of("model", "opus"));

            // Metric is recorded in gauge
            var snapshot = run.inMemoryMetrics().snapshot();
            assertThat(snapshot.gauges()).containsKey("cost.usd");
        }

        @Test
        @DisplayName("logs metric as event")
        void logsMetricAsEvent() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();

            run.logMetric("tokens.total", 1500);

            assertThat(run.events()).hasSize(1);
            assertThat(run.events().get(0)).isInstanceOf(MetricEvent.class);
        }

        @Test
        @DisplayName("provides metric registry access")
        void providesMetricRegistryAccess() {
            Run run = RunBuilder.forExperiment("test").start();

            run.metrics().counter("calls").increment();
            run.metrics().counter("calls").increment();
            run.metrics().timer("latency").record(Duration.ofMillis(100));

            DefaultRun defaultRun = (DefaultRun) run;
            var snapshot = defaultRun.inMemoryMetrics().snapshot();

            assertThat(snapshot.counterValue("calls")).isEqualTo(2);
            assertThat(snapshot.timers().get("latency").count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Artifact Logging")
    class ArtifactLoggingTests {

        @Test
        @DisplayName("logs text artifact")
        void logsTextArtifact() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();

            run.logArtifact("plan.md", "# Plan\n- Step 1\n- Step 2");

            assertThat(run.artifacts()).hasSize(1);
            assertThat(run.artifacts().get(0).name()).isEqualTo("plan.md");
            assertThat(new String(run.artifacts().get(0).content())).contains("# Plan");
        }

        @Test
        @DisplayName("logs binary artifact with metadata")
        void logsBinaryArtifact() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();
            byte[] content = new byte[]{1, 2, 3, 4};
            Map<String, Object> metadata = Map.of("type", "binary", "size", 4);

            run.logArtifact("data.bin", content, metadata);

            assertThat(run.artifacts()).hasSize(1);
            assertThat(run.artifacts().get(0).content()).isEqualTo(content);
            assertThat(run.artifacts().get(0).metadata()).isEqualTo(metadata);
        }

        @Test
        @DisplayName("artifacts have timestamp")
        void artifactsHaveTimestamp() {
            DefaultRun run = (DefaultRun) RunBuilder.forExperiment("test").start();

            run.logArtifact("test.txt", "content");

            assertThat(run.artifacts().get(0).timestamp()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Summary")
    class SummaryTests {

        @Test
        @DisplayName("starts with empty summary")
        void startsWithEmptySummary() {
            Run run = RunBuilder.forExperiment("test").start();

            assertThat(run.summary().isEmpty()).isTrue();
        }

        @Test
        @DisplayName("sets summary values")
        void setsSummaryValues() {
            Run run = RunBuilder.forExperiment("test").start();

            run.setSummary("filesChanged", 5);
            run.setSummary("testsPass", true);

            assertThat(run.summary().get("filesChanged", Integer.class)).isEqualTo(5);
            assertThat(run.summary().get("testsPass", Boolean.class)).isTrue();
        }

        @Test
        @DisplayName("overwrites summary values")
        void overwritesSummaryValues() {
            Run run = RunBuilder.forExperiment("test").start();

            run.setSummary("progress", 0.5);
            run.setSummary("progress", 1.0);

            assertThat(run.summary().get("progress", Double.class)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("preserves existing summary on close success")
        void preservesExistingSummaryOnClose() {
            Run run = RunBuilder.forExperiment("test").start();
            run.setSummary("custom", "value");
            run.close();

            assertThat(run.summary().get("custom", String.class)).isEqualTo("value");
            assertThat(run.summary().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("does not overwrite explicit success=false")
        void doesNotOverwriteExplicitSuccess() {
            Run run = RunBuilder.forExperiment("test").start();
            run.setSummary("success", false);
            run.close();

            assertThat(run.summary().isSuccess()).isFalse();
        }
    }

    @Nested
    @DisplayName("Integration")
    class IntegrationTests {

        @Test
        @DisplayName("complete workflow with try-with-resources")
        void completeWorkflow() {
            DefaultRun run;

            try (Run r = RunBuilder.forExperiment("integration-test")
                    .name("full-workflow")
                    .config("model", "claude-opus")
                    .tag("type", "integration")
                    .agent("test-runner")
                    .start()) {

                run = (DefaultRun) r;

                // Log events
                r.logEvent(TestEvents.llmCall());
                r.logEvent(TestEvents.readSuccess());

                // Log metrics
                r.logMetric("tokens.total", 700);
                r.metrics().counter("api.calls").increment();

                // Log artifact
                r.logArtifact("output.txt", "Result");

                // Set summary
                r.setSummary("filesRead", 1);
            }

            // Verify final state
            assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
            assertThat(run.summary().isSuccess()).isTrue();
            assertThat(run.events()).hasSize(3); // 2 events + 1 metric event
            assertThat(run.artifacts()).hasSize(1);
            assertThat(run.inMemoryMetrics().snapshot().counterValue("api.calls")).isEqualTo(1);
        }

        @Test
        @DisplayName("retry chain linking")
        void retryChainLinking() {
            // First attempt fails
            Run attempt1 = RunBuilder.forExperiment("retry-test")
                    .name("attempt-1")
                    .start();
            attempt1.fail(new RuntimeException("First attempt failed"));
            attempt1.close();

            // Second attempt links to first
            Run attempt2 = RunBuilder.forExperiment("retry-test")
                    .name("attempt-2")
                    .previousRun(attempt1.id())
                    .start();
            attempt2.close();

            assertThat(attempt1.status()).isEqualTo(RunStatus.FAILED);
            assertThat(attempt2.status()).isEqualTo(RunStatus.FINISHED);
            assertThat(attempt2.previousRunId()).isEqualTo(attempt1.id());
        }

        @Test
        @DisplayName("parent-child run hierarchy")
        void parentChildHierarchy() {
            // Supervisor run
            Run supervisor = RunBuilder.forExperiment("multi-agent")
                    .agent("supervisor")
                    .start();

            // Child run
            Run child = RunBuilder.forExperiment("multi-agent")
                    .agent("worker")
                    .parentRun(supervisor.id())
                    .start();

            assertThat(child.parentRunId()).isEqualTo(supervisor.id());
            assertThat(child.agentId()).isEqualTo("worker");

            child.close();
            supervisor.close();
        }
    }
}
