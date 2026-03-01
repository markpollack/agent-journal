package io.github.markpollack.journal;

import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.StateChangeEvent;
import io.github.markpollack.journal.event.GitCommitEvent;
import io.github.markpollack.journal.event.GitPatchEvent;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.event.CostBreakdown;
import io.github.markpollack.journal.event.TimingInfo;
import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Example-style tests demonstrating common API usage patterns.
 *
 * <p>These tests serve as living documentation for how to use the
 * Journal API. Each test demonstrates a specific usage pattern.
 */
@DisplayName("API Usage Examples")
class JournalExamplesTest {

    @BeforeEach
    void setUp() {
        Journal.reset();
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Nested
    @DisplayName("Basic Usage")
    class BasicUsageExamples {

        @Test
        @DisplayName("Example: Minimal run tracking")
        void minimalRunTracking() {
            // The simplest way to track a run
            try (Run run = Journal.run("my-experiment").start()) {
                // Do some work...
                run.setSummary("result", "success");
            }
            // Run auto-finishes with SUCCESS status when try-with-resources closes
        }

        @Test
        @DisplayName("Example: Run with configuration")
        void runWithConfiguration() {
            try (Run run = Journal.run("implement-feature")
                    .name("attempt-1")
                    .config("model", "claude-opus-4.5")
                    .config("maxTokens", 4000)
                    .config("temperature", 0.7)
                    .tag("type", "feature")
                    .tag("priority", "high")
                    .start()) {

                // Access configuration during the run
                String model = run.config().get("model", String.class);
                assertThat(model).isEqualTo("claude-opus-4.5");
            }
        }

        @Test
        @DisplayName("Example: Logging LLM calls")
        void loggingLlmCalls() {
            try (Run run = Journal.run("chat-session").start()) {
                // Log an LLM call with full metrics
                LLMCallEvent event = LLMCallEvent.builder()
                        .model("claude-opus-4.5")
                        .tokenUsage(TokenUsage.of(1200, 450))
                        .cost(CostBreakdown.of(0.018, 0.00675))
                        .timing(TimingInfo.of(2500))  // totalMs
                        .build();

                run.logEvent(event);

                // Also track aggregate metrics
                run.logMetric("tokens.total", 1650);
                run.logMetric("cost.usd", 0.02475);
            }
        }

        @Test
        @DisplayName("Example: Logging tool calls")
        void loggingToolCalls() {
            try (Run run = Journal.run("agent-task").start()) {
                // Log a successful tool call
                run.logEvent(ToolCallEvent.success(
                        "bash",
                        Map.of("command", "ls -la"),
                        Map.of("files", List.of("README.md", "pom.xml")),
                        150
                ));

                // Log a failed tool call
                run.logEvent(ToolCallEvent.failure(
                        "read",
                        Map.of("path", "/nonexistent"),
                        "File not found",
                        50
                ));
            }
        }
    }

    @Nested
    @DisplayName("Run Lifecycle")
    class RunLifecycleExamples {

        @Test
        @DisplayName("Example: Handling failures")
        void handlingFailures() {
            try (Run run = Journal.run("risky-operation").start()) {
                try {
                    // Simulate an operation that might fail
                    if (true) throw new RuntimeException("Operation failed");
                } catch (Exception e) {
                    // Explicitly mark the run as failed
                    run.fail(e);
                }
            }

            // The run is now marked as FAILED with error details in summary
        }

        @Test
        @DisplayName("Example: State transitions")
        void stateTransitions() {
            try (Run run = Journal.run("agent-workflow").start()) {
                // Log state changes as the agent progresses
                run.logEvent(StateChangeEvent.of("init", "planning", "Starting planning phase"));
                // ... do planning ...

                run.logEvent(StateChangeEvent.of("planning", "executing", "Plan approved"));
                // ... do execution ...

                run.logEvent(StateChangeEvent.of("executing", "validating", "Execution complete"));
                // ... validate results ...

                run.logEvent(StateChangeEvent.of("validating", "complete", "All validations passed"));
            }
        }

        @Test
        @DisplayName("Example: Summary metrics")
        void summaryMetrics() {
            try (Run run = Journal.run("code-generation").start()) {
                // Track work metrics
                int filesChanged = 5;
                int testsAdded = 12;
                boolean testsPassed = true;

                // Set summary values (can be overwritten)
                run.setSummary("filesChanged", filesChanged);
                run.setSummary("testsAdded", testsAdded);
                run.setSummary("testsPassed", testsPassed);
                run.setSummary("success", true);
            }
        }
    }

    @Nested
    @DisplayName("Run Linking")
    class RunLinkingExamples {

        @Test
        @DisplayName("Example: Retry chains")
        void retryChains() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            // First attempt fails
            String firstAttemptId;
            try (Run attempt1 = Journal.run("implement-feature").start()) {
                firstAttemptId = attempt1.id();
                attempt1.fail(new RuntimeException("Tests failed"));
            }

            // Retry links to first attempt
            try (Run attempt2 = Journal.run("implement-feature")
                    .previousRun(firstAttemptId)
                    .start()) {
                // This attempt succeeds
                attempt2.setSummary("success", true);
            }

            // Now we can trace the retry chain
            var runs = storage.listRuns("implement-feature");
            assertThat(runs).hasSize(2);
        }

        @Test
        @DisplayName("Example: Multi-agent hierarchies")
        void multiAgentHierarchies() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            // Supervisor agent creates parent run
            try (Run supervisor = Journal.run("complex-task")
                    .agent("supervisor")
                    .start()) {

                // Supervisor spawns child agents for subtasks
                try (Run codeAgent = Journal.run("complex-task")
                        .parentRun(supervisor.id())
                        .agent("code-writer")
                        .start()) {
                    // Code agent does its work
                }

                try (Run testAgent = Journal.run("complex-task")
                        .parentRun(supervisor.id())
                        .agent("test-writer")
                        .start()) {
                    // Test agent does its work
                }
            }

            // All runs are linked by parentRunId
            var runs = storage.listRuns("complex-task");
            assertThat(runs).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Git Integration")
    class GitIntegrationExamples {

        @Test
        @DisplayName("Example: Journal code changes")
        void trackingCodeChanges() {
            try (Run run = Journal.run("code-changes").start()) {
                // Log individual file changes as patches
                run.logEvent(GitPatchEvent.of(
                        "main",  // base branch
                        List.of(GitPatchEvent.FileChange.modified(
                                "src/main/java/Example.java",
                                5,  // lines added
                                2   // lines removed
                        ))
                ));

                // Log when code is committed
                run.logEvent(GitCommitEvent.of(
                        "abc123def",        // commit sha
                        "Add new feature",  // message
                        "main"              // branch
                ));
            }
        }
    }

    @Nested
    @DisplayName("Storage Configuration")
    class StorageExamples {

        @Test
        @DisplayName("Example: In-memory storage for testing")
        void inMemoryStorage() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            try (Run run = Journal.run("test-run").start()) {
                run.logEvent(ToolCallEvent.success("bash", Map.of(), "ok", 0));
            }

            // Query the storage directly for testing
            var runs = storage.listRuns("test-run");
            assertThat(runs).hasSize(1);
        }

        @Test
        @DisplayName("Example: File-based storage for persistence")
        void fileBasedStorage(@TempDir Path tempDir) {
            JsonFileStorage storage = new JsonFileStorage(tempDir);
            Journal.configure(storage);

            try (Run run = Journal.run("persistent-run")
                    .config("model", "claude-opus")
                    .start()) {
                run.setSummary("result", "completed");
            }

            // Data persists to disk
            // Structure: tempDir/experiments/persistent-run/runs/<run-id>/
            assertThat(tempDir.resolve("experiments")).exists();
        }
    }

    @Nested
    @DisplayName("Experiments")
    class ExperimentExamples {

        @Test
        @DisplayName("Example: Creating experiments with metadata")
        void experimentWithMetadata() {
            Experiment exp = Journal.experiment("oauth-implementation",
                    Experiment.create("oauth-implementation")
                            .name("OAuth 2.0 Implementation")
                            .description("Adding OAuth2 authentication to the application")
                            .tags(Tags.of(
                                    "feature", "auth",
                                    "priority", "high"
                            )));

            assertThat(exp.name()).isEqualTo("OAuth 2.0 Implementation");
            assertThat(exp.description()).isEqualTo("Adding OAuth2 authentication to the application");
        }

        @Test
        @DisplayName("Example: Reusing experiments across runs")
        void reusingExperiments() {
            // First run
            try (Run run1 = Journal.run("my-experiment").start()) {
                // ...
            }

            // Second run uses the same experiment
            try (Run run2 = Journal.run("my-experiment").start()) {
                // Both runs belong to "my-experiment"
                assertThat(run2.experiment().id()).isEqualTo("my-experiment");
            }
        }
    }

    @Nested
    @DisplayName("Metrics")
    class MetricExamples {

        @Test
        @DisplayName("Example: Using the metrics registry")
        void usingMetricsRegistry() {
            try (Run run = Journal.run("metrics-example").start()) {
                var metrics = run.metrics();

                // Counter - for tracking counts
                var apiCalls = metrics.counter("api.calls");
                apiCalls.increment();
                apiCalls.increment();

                // Timer - for tracking durations
                var responseTime = metrics.timer("api.responseTime");
                var sample = responseTime.start();
                // ... do timed operation ...
                sample.stop();

                // Gauge - for tracking current values
                var activeConnections = metrics.gauge("connections.active");
                activeConnections.set(5);
            }
        }
    }

    @Nested
    @DisplayName("Artifacts")
    class ArtifactExamples {

        @Test
        @DisplayName("Example: Logging text artifacts")
        void loggingTextArtifacts() {
            try (Run run = Journal.run("artifact-example").start()) {
                // Log a markdown plan
                run.logArtifact("plan.md", "# Implementation Plan\n\n1. Step one\n2. Step two");

                // Log generated code
                run.logArtifact("output.java", "public class Example { }");

                // Log test results
                run.logArtifact("test-results.json", "{\"passed\": 10, \"failed\": 0}");
            }
        }
    }
}
