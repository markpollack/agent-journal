package io.github.markpollack.journal;

import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import io.github.markpollack.journal.storage.JournalStorage;
import io.github.markpollack.journal.test.BaseTrackingTest;
import io.github.markpollack.journal.test.TestEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the Journal entry point and related classes.
 */
@DisplayName("Journal Entry Point")
class JournalTest extends BaseTrackingTest {

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // Ensure clean state before each test
        Journal.reset();
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Nested
    @DisplayName("JournalContext")
    class TrackingContextTests {

        @Test
        @DisplayName("defaults to in-memory storage")
        void defaultsToInMemoryStorage() {
            JournalStorage storage = JournalContext.getStorage();

            assertThat(storage).isInstanceOf(InMemoryStorage.class);
        }

        @Test
        @DisplayName("can configure custom storage")
        void canConfigureCustomStorage() {
            InMemoryStorage customStorage = new InMemoryStorage();
            JournalContext.setStorage(customStorage);

            assertThat(JournalContext.getStorage()).isSameAs(customStorage);
        }

        @Test
        @DisplayName("isConfigured returns true after explicit configuration")
        void isConfiguredAfterExplicitSet() {
            assertThat(JournalContext.isConfigured()).isFalse();

            JournalContext.setStorage(new InMemoryStorage());

            assertThat(JournalContext.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("reset clears configuration")
        void resetClearsConfiguration() {
            JournalContext.setStorage(new InMemoryStorage());
            assertThat(JournalContext.isConfigured()).isTrue();

            JournalContext.reset();

            assertThat(JournalContext.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("rejects null storage")
        void rejectsNullStorage() {
            assertThatThrownBy(() -> JournalContext.setStorage(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("ExperimentRegistry")
    class ExperimentRegistryTests {

        @Test
        @DisplayName("creates new experiment if not exists")
        void createsNewExperiment() {
            Experiment exp = ExperimentRegistry.getOrCreate("new-experiment");

            assertThat(exp.id()).isEqualTo("new-experiment");
            assertThat(exp.name()).isEqualTo("new-experiment");
        }

        @Test
        @DisplayName("returns same experiment on subsequent calls")
        void returnsSameExperiment() {
            Experiment first = ExperimentRegistry.getOrCreate("my-experiment");
            Experiment second = ExperimentRegistry.getOrCreate("my-experiment");

            assertThat(first).isSameAs(second);
        }

        @Test
        @DisplayName("accepts custom builder for new experiments")
        void acceptsCustomBuilder() {
            Experiment exp = ExperimentRegistry.getOrCreate("custom-experiment",
                    Experiment.create("custom-experiment")
                            .name("Custom Experiment")
                            .description("A custom experiment"));

            assertThat(exp.name()).isEqualTo("Custom Experiment");
            assertThat(exp.description()).isEqualTo("A custom experiment");
        }

        @Test
        @DisplayName("ignores builder for existing experiments")
        void ignoresBuilderForExisting() {
            // Create first
            ExperimentRegistry.getOrCreate("existing",
                    Experiment.create("existing").name("First Name"));

            // Try to override - should be ignored
            Experiment exp = ExperimentRegistry.getOrCreate("existing",
                    Experiment.create("existing").name("Second Name"));

            assertThat(exp.name()).isEqualTo("First Name");
        }

        @Test
        @DisplayName("get returns empty for non-existent experiment")
        void getReturnsEmptyForNonExistent() {
            Optional<Experiment> exp = ExperimentRegistry.get("non-existent");

            assertThat(exp).isEmpty();
        }

        @Test
        @DisplayName("get returns existing experiment")
        void getReturnsExisting() {
            ExperimentRegistry.getOrCreate("existing");

            Optional<Experiment> exp = ExperimentRegistry.get("existing");

            assertThat(exp).isPresent();
            assertThat(exp.get().id()).isEqualTo("existing");
        }

        @Test
        @DisplayName("persists experiments to storage")
        void persistsToStorage() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            ExperimentRegistry.getOrCreate("persisted-experiment");

            Optional<Experiment> fromStorage = storage.loadExperiment("persisted-experiment");
            assertThat(fromStorage).isPresent();
        }

        @Test
        @DisplayName("loads experiments from storage")
        void loadsFromStorage() {
            InMemoryStorage storage = new InMemoryStorage();
            Experiment saved = Experiment.create("pre-saved")
                    .name("Pre-saved Experiment")
                    .build();
            storage.saveExperiment(saved);

            Journal.configure(storage);
            ExperimentRegistry.clearCache();

            Experiment loaded = ExperimentRegistry.getOrCreate("pre-saved");

            assertThat(loaded.name()).isEqualTo("Pre-saved Experiment");
        }
    }

    @Nested
    @DisplayName("Journal static methods")
    class TrackingStaticTests {

        @Test
        @DisplayName("run() returns RunBuilder")
        void runReturnsBuilder() {
            RunBuilder builder = Journal.run("my-experiment");

            assertThat(builder).isNotNull();
        }

        @Test
        @DisplayName("experiment() gets or creates experiment")
        void experimentGetsOrCreates() {
            Experiment exp = Journal.experiment("my-experiment");

            assertThat(exp).isNotNull();
            assertThat(exp.id()).isEqualTo("my-experiment");
        }

        @Test
        @DisplayName("experiment() with builder allows customization")
        void experimentWithBuilder() {
            Experiment exp = Journal.experiment("custom",
                    Experiment.create("custom")
                            .name("Custom Name")
                            .description("Custom description"));

            assertThat(exp.name()).isEqualTo("Custom Name");
        }

        @Test
        @DisplayName("configure() sets storage")
        void configureSetsStorage() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            assertThat(Journal.storage()).isSameAs(storage);
        }

        @Test
        @DisplayName("storage() returns current storage")
        void storageReturnsCurrent() {
            JournalStorage storage = Journal.storage();

            assertThat(storage).isNotNull();
        }

        @Test
        @DisplayName("reset() clears all state")
        void resetClearsState() {
            Journal.configure(new InMemoryStorage());
            Journal.experiment("test-experiment");

            Journal.reset();

            assertThat(JournalContext.isConfigured()).isFalse();
            assertThat(ExperimentRegistry.cacheSize()).isZero();
        }
    }

    @Nested
    @DisplayName("Full Workflow Integration")
    class FullWorkflowTests {

        @Test
        @DisplayName("creates run, logs events, and persists to storage")
        void fullWorkflow() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            try (Run run = Journal.run("integration-test")
                    .name("test-run")
                    .config("model", "claude-opus")
                    .tag("type", "test")
                    .start()) {

                run.logEvent(TestEvents.llmCall());
                run.logMetric("tokens.total", 1650);
                run.setSummary("filesChanged", 3);
            }

            // Verify experiment was saved
            Optional<Experiment> exp = storage.loadExperiment("integration-test");
            assertThat(exp).isPresent();

            // Verify run was saved
            var runs = storage.listRuns("integration-test");
            assertThat(runs).hasSize(1);

            RunData runData = runs.get(0);
            assertThat(runData.name()).isEqualTo("test-run");
            assertThat(runData.status()).isEqualTo(RunStatus.FINISHED);
            assertThat(runData.config().get("model", String.class)).isEqualTo("claude-opus");
            assertThat(runData.summary().get("filesChanged", Integer.class)).isEqualTo(3);
            assertThat(runData.summary().get("success", Boolean.class)).isTrue();
        }

        @Test
        @DisplayName("persists events as they are logged")
        void persistsEvents() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("event-test").start()) {
                runId = run.id();
                run.logEvent(TestEvents.llmCall());
                run.logEvent(TestEvents.bashSuccess());

                // Events should be persisted immediately
                var events = storage.loadEvents("event-test", runId);
                assertThat(events).hasSize(2);
            }
        }

        @Test
        @DisplayName("persists artifacts")
        void persistsArtifacts() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("artifact-test").start()) {
                runId = run.id();
                run.logArtifact("plan.md", "# Implementation Plan");
            }

            Optional<byte[]> artifact = storage.loadArtifact("artifact-test", runId, "plan.md");
            assertThat(artifact).isPresent();
            assertThat(new String(artifact.get())).isEqualTo("# Implementation Plan");
        }

        @Test
        @DisplayName("handles run failure correctly")
        void handlesFailure() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("failure-test").start()) {
                runId = run.id();
                run.fail(new RuntimeException("Test failure"));
            }

            var runs = storage.listRuns("failure-test");
            RunData runData = runs.get(0);
            assertThat(runData.status()).isEqualTo(RunStatus.FAILED);
            assertThat(runData.summary().get("success", Boolean.class)).isFalse();
            assertThat(runData.errorMessage()).isEqualTo("Test failure");
            assertThat(runData.errorType()).isEqualTo("java.lang.RuntimeException");
        }

        @Test
        @DisplayName("works with file-based storage")
        void worksWithFileStorage(@TempDir Path tempDir) {
            JsonFileStorage storage = new JsonFileStorage(tempDir);
            Journal.configure(storage);

            try (Run run = Journal.run("file-test")
                    .config("key", "value")
                    .start()) {
                run.logEvent(TestEvents.llmCall());
                run.setSummary("result", "success");
            }

            // Reload from storage
            var runs = storage.listRuns("file-test");
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).config().get("key", String.class)).isEqualTo("value");
        }

        @Test
        @DisplayName("supports run linking (previousRunId)")
        void supportsRunLinking() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String firstRunId;
            try (Run firstRun = Journal.run("linked-test").start()) {
                firstRunId = firstRun.id();
                firstRun.fail(new RuntimeException("First attempt failed"));
            }

            try (Run retryRun = Journal.run("linked-test")
                    .previousRun(firstRunId)
                    .start()) {
                // Retry succeeds
            }

            var runs = storage.listRuns("linked-test");
            assertThat(runs).hasSize(2);

            // Find retry run
            RunData retry = runs.stream()
                    .filter(r -> r.previousRunId() != null)
                    .findFirst()
                    .orElseThrow();
            assertThat(retry.previousRunId()).isEqualTo(firstRunId);
        }

        @Test
        @DisplayName("supports parent-child runs")
        void supportsParentChildRuns() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String parentId;
            try (Run parent = Journal.run("parent-test")
                    .agent("supervisor")
                    .start()) {
                parentId = parent.id();

                try (Run child = Journal.run("parent-test")
                        .parentRun(parentId)
                        .agent("worker")
                        .start()) {
                    // Child does work
                }
            }

            var runs = storage.listRuns("parent-test");
            assertThat(runs).hasSize(2);

            RunData child = runs.stream()
                    .filter(r -> r.parentRunId() != null)
                    .findFirst()
                    .orElseThrow();
            assertThat(child.parentRunId()).isEqualTo(parentId);
            assertThat(child.agentId()).isEqualTo("worker");
        }
    }
}
