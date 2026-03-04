/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.test;

import io.github.markpollack.journal.Config;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.metric.Tags;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Factory for creating test run scenarios.
 *
 * <p>Provides pre-configured run data for testing run lifecycle,
 * storage, and query operations. Since the Run interface is not yet
 * implemented, this provides {@link TestRunData} records that capture
 * all the data needed for a run.
 *
 * <p>Example:
 * <pre>{@code
 * @Test
 * void shouldSaveAndLoadRun() {
 *     TestRunData runData = TestRuns.successfulRun();
 *     Run run = createRun(runData); // Once Run impl exists
 *     storage.save(run);
 *     Run loaded = storage.load(run.id());
 *     assertThat(loaded.status()).isEqualTo(RunStatus.FINISHED);
 * }
 * }</pre>
 */
public final class TestRuns {

    // Standard test values
    public static final String DEFAULT_EXPERIMENT_ID = "test-experiment";
    public static final String DEFAULT_TASK_ID = "issue-123";
    public static final String DEFAULT_AGENT_ID = "test-agent";
    public static final String DEFAULT_MODEL = "claude-opus-4.5";

    private TestRuns() {} // Utility class

    /**
     * Captures all data for a test run.
     * Can be converted to an actual Run once the implementation exists.
     */
    public record TestRunData(
            String id,
            String experimentId,
            String taskId,
            String agentId,
            String name,
            RunStatus status,
            Config config,
            Tags tags,
            Map<String, Object> summary,
            List<JournalEvent> events,
            String previousRunId,
            String parentRunId,
            Instant startedAt,
            Instant finishedAt,
            Throwable error
    ) {
        /** Creates a builder from this data. */
        public Builder toBuilder() {
            return new Builder()
                    .id(id)
                    .experimentId(experimentId)
                    .taskId(taskId)
                    .agentId(agentId)
                    .name(name)
                    .status(status)
                    .config(config)
                    .tags(tags)
                    .summary(summary)
                    .events(events)
                    .previousRunId(previousRunId)
                    .parentRunId(parentRunId)
                    .startedAt(startedAt)
                    .finishedAt(finishedAt)
                    .error(error);
        }

        /** Duration of the run. */
        public Duration duration() {
            if (startedAt == null || finishedAt == null) return Duration.ZERO;
            return Duration.between(startedAt, finishedAt);
        }
    }

    // ========== Basic Run Scenarios ==========

    /** Creates a minimal successful run. */
    public static TestRunData successfulRun() {
        Instant start = Instant.now().minusSeconds(60);
        return new TestRunData(
                generateRunId(),
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "Successful test run",
                RunStatus.FINISHED,
                Config.of("model", DEFAULT_MODEL),
                Tags.of("test", "true"),
                Map.of("success", true, "filesChanged", 3),
                TestEvents.agentTurnSequence(),
                null,
                null,
                start,
                Instant.now(),
                null
        );
    }

    /** Creates a failed run. */
    public static TestRunData failedRun() {
        Instant start = Instant.now().minusSeconds(30);
        return new TestRunData(
                generateRunId(),
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "Failed test run",
                RunStatus.FAILED,
                Config.of("model", DEFAULT_MODEL),
                Tags.of("test", "true"),
                Map.of("success", false, "error", "Compilation failed"),
                TestEvents.failedRunSequence(),
                null,
                null,
                start,
                Instant.now(),
                new RuntimeException("Compilation failed")
        );
    }

    /** Creates a run in INIT status (not yet started). */
    public static TestRunData initRun() {
        return new TestRunData(
                generateRunId(),
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "Initializing run",
                RunStatus.INIT,
                Config.empty(),
                Tags.empty(),
                Map.of(),
                List.of(),
                null,
                null,
                Instant.now(),
                null,
                null
        );
    }

    /** Creates a run in RUNNING status. */
    public static TestRunData runningRun() {
        return new TestRunData(
                generateRunId(),
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "Running test run",
                RunStatus.RUNNING,
                Config.of("model", DEFAULT_MODEL, "maxTokens", 4000),
                Tags.of("test", "true"),
                Map.of(),
                List.of(TestEvents.llmCall(), TestEvents.bashSuccess()),
                null,
                null,
                Instant.now().minusSeconds(15),
                null,
                null
        );
    }

    // ========== Linked Run Scenarios ==========

    /** Creates a chain of runs representing retry attempts. */
    public static List<TestRunData> retryChain() {
        String run1Id = generateRunId();
        String run2Id = generateRunId();
        String run3Id = generateRunId();
        Instant baseTime = Instant.now().minusSeconds(600); // 10 minutes ago

        TestRunData run1 = new TestRunData(
                run1Id,
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "First attempt",
                RunStatus.FAILED,
                Config.of("model", "claude-haiku-3"),
                Tags.of("attempt", "1"),
                Map.of("success", false, "error", "Model capability insufficient"),
                TestEvents.failedRunSequence(),
                null,
                null,
                baseTime,
                baseTime.plusSeconds(30),
                new RuntimeException("Model capability insufficient")
        );

        TestRunData run2 = new TestRunData(
                run2Id,
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "Second attempt",
                RunStatus.FAILED,
                Config.of("model", "claude-sonnet-4"),
                Tags.of("attempt", "2"),
                Map.of("success", false, "error", "Test failures"),
                TestEvents.failedRunSequence(),
                run1Id,  // Links to first attempt
                null,
                baseTime.plusSeconds(120), // +2 minutes
                baseTime.plusSeconds(180), // +3 minutes
                new RuntimeException("Test failures")
        );

        TestRunData run3 = new TestRunData(
                run3Id,
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                DEFAULT_AGENT_ID,
                "Third attempt - success",
                RunStatus.FINISHED,
                Config.of("model", DEFAULT_MODEL),
                Tags.of("attempt", "3"),
                Map.of("success", true, "filesChanged", 5, "testsPass", true),
                TestEvents.agentTurnSequence(),
                run2Id,  // Links to second attempt
                null,
                baseTime.plusSeconds(300), // +5 minutes
                baseTime.plusSeconds(420), // +7 minutes
                null
        );

        return List.of(run1, run2, run3);
    }

    /** Creates a parent run with child sub-runs (multi-agent scenario). */
    public static List<TestRunData> multiAgentRuns() {
        String parentId = generateRunId();
        String child1Id = generateRunId();
        String child2Id = generateRunId();
        Instant baseTime = Instant.now().minusSeconds(300); // 5 minutes ago

        TestRunData parent = new TestRunData(
                parentId,
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                "supervisor",
                "Supervisor run",
                RunStatus.FINISHED,
                Config.of("model", DEFAULT_MODEL, "strategy", "divide-and-conquer"),
                Tags.of("role", "supervisor"),
                Map.of("success", true, "childRuns", 2),
                List.of(TestEvents.llmCall()),
                null,
                null,
                baseTime,
                baseTime.plusSeconds(240), // +4 minutes
                null
        );

        TestRunData child1 = new TestRunData(
                child1Id,
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                "code-gen",
                "Code generation sub-run",
                RunStatus.FINISHED,
                Config.of("model", DEFAULT_MODEL),
                Tags.of("role", "worker", "task", "generate-code"),
                Map.of("success", true, "filesGenerated", 3),
                TestEvents.agentTurnSequence(),
                null,
                parentId,  // Links to parent
                baseTime.plusSeconds(30),
                baseTime.plusSeconds(120), // +2 minutes
                null
        );

        TestRunData child2 = new TestRunData(
                child2Id,
                DEFAULT_EXPERIMENT_ID,
                DEFAULT_TASK_ID,
                "test-writer",
                "Test writing sub-run",
                RunStatus.FINISHED,
                Config.of("model", "claude-sonnet-4"),
                Tags.of("role", "worker", "task", "write-tests"),
                Map.of("success", true, "testsWritten", 5),
                TestEvents.agentTurnSequence(),
                null,
                parentId,  // Links to parent
                baseTime.plusSeconds(120), // +2 minutes
                baseTime.plusSeconds(210), // +3.5 minutes
                null
        );

        return List.of(parent, child1, child2);
    }

    // ========== Config Scenarios ==========

    /** Creates a run with comprehensive config. */
    public static TestRunData runWithFullConfig() {
        return builder()
                .config(Config.builder()
                        .set("model", DEFAULT_MODEL)
                        .set("maxTokens", 8000)
                        .set("temperature", 0.7)
                        .set("topP", 0.95)
                        .set("systemPrompt", "You are a helpful coding assistant.")
                        .set("enabledTools", List.of("Bash", "Read", "Write", "Glob", "Grep"))
                        .set("maxTurns", 20)
                        .set("contextStrategy", "full")
                        .build())
                .build();
    }

    // ========== Builder ==========

    /** Creates a new builder with default values. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder for TestRunData. */
    public static final class Builder {
        private String id = generateRunId();
        private String experimentId = DEFAULT_EXPERIMENT_ID;
        private String taskId = DEFAULT_TASK_ID;
        private String agentId = DEFAULT_AGENT_ID;
        private String name = "Test run";
        private RunStatus status = RunStatus.FINISHED;
        private Config config = Config.of("model", DEFAULT_MODEL);
        private Tags tags = Tags.empty();
        private Map<String, Object> summary = Map.of();
        private List<JournalEvent> events = List.of();
        private String previousRunId;
        private String parentRunId;
        private Instant startedAt = Instant.now().minusSeconds(60);
        private Instant finishedAt = Instant.now();
        private Throwable error;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder experimentId(String experimentId) {
            this.experimentId = experimentId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder status(RunStatus status) {
            this.status = status;
            return this;
        }

        public Builder config(Config config) {
            this.config = config;
            return this;
        }

        public Builder tags(Tags tags) {
            this.tags = tags;
            return this;
        }

        public Builder summary(Map<String, Object> summary) {
            this.summary = summary;
            return this;
        }

        public Builder events(List<JournalEvent> events) {
            this.events = events;
            return this;
        }

        public Builder previousRunId(String previousRunId) {
            this.previousRunId = previousRunId;
            return this;
        }

        public Builder parentRunId(String parentRunId) {
            this.parentRunId = parentRunId;
            return this;
        }

        public Builder startedAt(Instant startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public Builder finishedAt(Instant finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Builder error(Throwable error) {
            this.error = error;
            return this;
        }

        public TestRunData build() {
            return new TestRunData(id, experimentId, taskId, agentId, name, status,
                    config, tags, summary, events, previousRunId, parentRunId,
                    startedAt, finishedAt, error);
        }
    }

    /** Generates a unique run ID. */
    public static String generateRunId() {
        return "run-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Generates a unique experiment ID. */
    public static String generateExperimentId() {
        return "exp-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
