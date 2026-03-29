package io.github.markpollack.journal.storage;

import io.github.markpollack.journal.Config;
import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.Summary;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.metric.Tags;
import io.github.markpollack.journal.test.BaseTrackingTest;
import io.github.markpollack.journal.test.TestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for JournalStorage implementations.
 */
@DisplayName("Storage")
class StorageTest extends BaseTrackingTest {

    @Nested
    @DisplayName("InMemoryStorage")
    class InMemoryStorageTests {

        private InMemoryStorage storage;

        @BeforeEach
        void setUp() {
            storage = new InMemoryStorage();
        }

        @Nested
        @DisplayName("Experiments")
        class ExperimentTests {

            @Test
            @DisplayName("saves and loads experiment")
            void savesAndLoadsExperiment() {
                Experiment exp = Experiment.create("test-exp")
                        .name("Test Experiment")
                        .description("A test")
                        .tags(Tags.of("env", "test"))
                        .build();

                storage.saveExperiment(exp);
                Optional<Experiment> loaded = storage.loadExperiment("test-exp");

                assertThat(loaded).isPresent();
                assertThat(loaded.get().id()).isEqualTo("test-exp");
                assertThat(loaded.get().name()).isEqualTo("Test Experiment");
            }

            @Test
            @DisplayName("returns empty for missing experiment")
            void returnEmptyForMissing() {
                Optional<Experiment> loaded = storage.loadExperiment("nonexistent");
                assertThat(loaded).isEmpty();
            }

            @Test
            @DisplayName("lists all experiments")
            void listsAllExperiments() {
                storage.saveExperiment(Experiment.create("exp-1").build());
                storage.saveExperiment(Experiment.create("exp-2").build());
                storage.saveExperiment(Experiment.create("exp-3").build());

                List<Experiment> experiments = storage.listExperiments();
                assertThat(experiments).hasSize(3);
            }

            @Test
            @DisplayName("experimentExists returns correct value")
            void experimentExistsWorks() {
                storage.saveExperiment(Experiment.create("existing").build());

                assertThat(storage.experimentExists("existing")).isTrue();
                assertThat(storage.experimentExists("missing")).isFalse();
            }
        }

        @Nested
        @DisplayName("Runs")
        class RunTests {

            @Test
            @DisplayName("saves and loads run")
            void savesAndLoadsRun() {
                RunData runData = RunData.builder()
                        .id("run-123")
                        .experimentId("exp-1")
                        .name("test-run")
                        .status(RunStatus.FINISHED)
                        .config(Config.of("model", "claude"))
                        .summary(Summary.of("success", true))
                        .startTime(Instant.now())
                        .build();

                storage.saveRun(runData);
                Optional<RunData> loaded = storage.loadRun("exp-1", "run-123");

                assertThat(loaded).isPresent();
                assertThat(loaded.get().id()).isEqualTo("run-123");
                assertThat(loaded.get().status()).isEqualTo(RunStatus.FINISHED);
                assertThat(loaded.get().config().get("model", String.class)).isEqualTo("claude");
            }

            @Test
            @DisplayName("returns empty for missing run")
            void returnsEmptyForMissing() {
                Optional<RunData> loaded = storage.loadRun("exp-1", "nonexistent");
                assertThat(loaded).isEmpty();
            }

            @Test
            @DisplayName("lists runs for experiment")
            void listsRunsForExperiment() {
                storage.saveRun(createRunData("exp-1", "run-1"));
                storage.saveRun(createRunData("exp-1", "run-2"));
                storage.saveRun(createRunData("exp-2", "run-3"));

                List<RunData> runs = storage.listRuns("exp-1");
                assertThat(runs).hasSize(2);
            }

            @Test
            @DisplayName("runExists returns correct value")
            void runExistsWorks() {
                storage.saveRun(createRunData("exp-1", "existing"));

                assertThat(storage.runExists("exp-1", "existing")).isTrue();
                assertThat(storage.runExists("exp-1", "missing")).isFalse();
            }
        }

        @Nested
        @DisplayName("Events")
        class EventTests {

            @Test
            @DisplayName("appends and loads events")
            void appendsAndLoadsEvents() {
                storage.appendEvent("exp-1", "run-1", TestEvents.llmCall());
                storage.appendEvent("exp-1", "run-1", TestEvents.bashSuccess());
                storage.appendEvent("exp-1", "run-1", TestEvents.stateChangePlanToImplement());

                List<JournalEvent> events = storage.loadEvents("exp-1", "run-1");
                assertThat(events).hasSize(3);
            }

            @Test
            @DisplayName("returns empty for run with no events")
            void returnsEmptyForNoEvents() {
                List<JournalEvent> events = storage.loadEvents("exp-1", "run-1");
                assertThat(events).isEmpty();
            }

            @Test
            @DisplayName("preserves event order")
            void preservesEventOrder() {
                var event1 = TestEvents.llmCall();
                var event2 = TestEvents.bashSuccess();
                var event3 = TestEvents.tokenMetric();

                storage.appendEvent("exp-1", "run-1", event1);
                storage.appendEvent("exp-1", "run-1", event2);
                storage.appendEvent("exp-1", "run-1", event3);

                List<JournalEvent> events = storage.loadEvents("exp-1", "run-1");
                assertThat(events.get(0)).isEqualTo(event1);
                assertThat(events.get(1)).isEqualTo(event2);
                assertThat(events.get(2)).isEqualTo(event3);
            }
        }

        @Nested
        @DisplayName("Artifacts")
        class ArtifactTests {

            @Test
            @DisplayName("saves and loads artifact")
            void savesAndLoadsArtifact() {
                byte[] content = "Hello, World!".getBytes();

                storage.saveArtifact("exp-1", "run-1", "test.txt", content);
                Optional<byte[]> loaded = storage.loadArtifact("exp-1", "run-1", "test.txt");

                assertThat(loaded).isPresent();
                assertThat(new String(loaded.get())).isEqualTo("Hello, World!");
            }

            @Test
            @DisplayName("returns empty for missing artifact")
            void returnsEmptyForMissing() {
                Optional<byte[]> loaded = storage.loadArtifact("exp-1", "run-1", "missing.txt");
                assertThat(loaded).isEmpty();
            }

            @Test
            @DisplayName("lists artifacts")
            void listsArtifacts() {
                storage.saveArtifact("exp-1", "run-1", "file1.txt", "content".getBytes());
                storage.saveArtifact("exp-1", "run-1", "file2.md", "# Markdown".getBytes());

                List<String> artifacts = storage.listArtifacts("exp-1", "run-1");
                assertThat(artifacts).containsExactlyInAnyOrder("file1.txt", "file2.md");
            }
        }

        @Nested
        @DisplayName("Utility")
        class UtilityTests {

            @Test
            @DisplayName("clear removes all data")
            void clearRemovesAll() {
                storage.saveExperiment(Experiment.create("exp").build());
                storage.saveRun(createRunData("exp", "run"));
                storage.appendEvent("exp", "run", TestEvents.llmCall());
                storage.saveArtifact("exp", "run", "test.txt", "data".getBytes());

                storage.clear();

                assertThat(storage.experimentCount()).isZero();
                assertThat(storage.runCount()).isZero();
                assertThat(storage.eventCount()).isZero();
            }

            @Test
            @DisplayName("counts are accurate")
            void countsAreAccurate() {
                storage.saveExperiment(Experiment.create("exp-1").build());
                storage.saveExperiment(Experiment.create("exp-2").build());
                storage.saveRun(createRunData("exp-1", "run-1"));
                storage.saveRun(createRunData("exp-1", "run-2"));
                storage.saveRun(createRunData("exp-2", "run-3"));
                storage.appendEvent("exp-1", "run-1", TestEvents.llmCall());
                storage.appendEvent("exp-1", "run-1", TestEvents.bashSuccess());

                assertThat(storage.experimentCount()).isEqualTo(2);
                assertThat(storage.runCount()).isEqualTo(3);
                assertThat(storage.eventCount()).isEqualTo(2);
            }
        }

        @Nested
        @DisplayName("Thread Safety")
        class ThreadSafetyTests {

            @Test
            @DisplayName("handles concurrent event appends")
            void handlesConcurrentAppends() throws InterruptedException {
                int threadCount = 10;
                int eventsPerThread = 100;
                ExecutorService executor = Executors.newFixedThreadPool(threadCount);
                CountDownLatch latch = new CountDownLatch(threadCount);

                for (int i = 0; i < threadCount; i++) {
                    executor.submit(() -> {
                        try {
                            for (int j = 0; j < eventsPerThread; j++) {
                                storage.appendEvent("exp-1", "run-1", TestEvents.llmCall());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await(10, TimeUnit.SECONDS);
                executor.shutdown();

                assertThat(storage.loadEvents("exp-1", "run-1")).hasSize(threadCount * eventsPerThread);
            }
        }

        private RunData createRunData(String experimentId, String runId) {
            return RunData.builder()
                    .id(runId)
                    .experimentId(experimentId)
                    .status(RunStatus.RUNNING)
                    .startTime(Instant.now())
                    .build();
        }
    }

    @Nested
    @DisplayName("JsonFileStorage")
    class JsonFileStorageTests {

        @TempDir
        Path tempDir;

        private JsonFileStorage storage;

        @BeforeEach
        void setUp() {
            storage = new JsonFileStorage(tempDir);
        }

        @Nested
        @DisplayName("Experiments")
        class ExperimentTests {

            @Test
            @DisplayName("saves and loads experiment")
            void savesAndLoadsExperiment() {
                Experiment exp = Experiment.create("test-exp")
                        .name("Test Experiment")
                        .tags(Tags.of("env", "test"))
                        .build();

                storage.saveExperiment(exp);
                Optional<Experiment> loaded = storage.loadExperiment("test-exp");

                assertThat(loaded).isPresent();
                assertThat(loaded.get().id()).isEqualTo("test-exp");
                assertThat(loaded.get().name()).isEqualTo("Test Experiment");
            }

            @Test
            @DisplayName("creates directory structure")
            void createsDirectoryStructure() {
                storage.saveExperiment(Experiment.create("my-exp").build());

                Path expDir = tempDir.resolve("experiments/my-exp");
                assertThat(expDir).exists();
                assertThat(expDir.resolve("experiment.json")).exists();
            }

            @Test
            @DisplayName("lists experiments from disk")
            void listsExperiments() {
                storage.saveExperiment(Experiment.create("exp-1").build());
                storage.saveExperiment(Experiment.create("exp-2").build());

                // Create new storage instance to ensure we're reading from disk
                JsonFileStorage newStorage = new JsonFileStorage(tempDir);
                List<Experiment> experiments = newStorage.listExperiments();

                assertThat(experiments).hasSize(2);
            }
        }

        @Nested
        @DisplayName("Runs")
        class RunTests {

            @Test
            @DisplayName("saves and loads run with all fields")
            void savesAndLoadsRun() {
                RunData runData = RunData.builder()
                        .id("run-123")
                        .experimentId("exp-1")
                        .name("test-run")
                        .status(RunStatus.FINISHED)
                        .config(Config.of("model", "claude", "temp", 0.7))
                        .summary(Summary.of("success", true, "tokens", 1500))
                        .tags(Tags.of("type", "test"))
                        .agentId("test-agent")
                        .previousRunId("prev-run")
                        .parentRunId("parent-run")
                        .startTime(Instant.parse("2024-01-01T10:00:00Z"))
                        .endTime(Instant.parse("2024-01-01T10:05:00Z"))
                        .errorMessage("test error")
                        .errorType("RuntimeException")
                        .build();

                storage.saveRun(runData);

                // Load with fresh storage
                JsonFileStorage newStorage = new JsonFileStorage(tempDir);
                Optional<RunData> loaded = newStorage.loadRun("exp-1", "run-123");

                assertThat(loaded).isPresent();
                RunData data = loaded.get();
                assertThat(data.id()).isEqualTo("run-123");
                assertThat(data.status()).isEqualTo(RunStatus.FINISHED);
                assertThat(data.config().get("model", String.class)).isEqualTo("claude");
                assertThat(data.summary().get("tokens", Integer.class)).isEqualTo(1500);
                assertThat(data.agentId()).isEqualTo("test-agent");
                assertThat(data.previousRunId()).isEqualTo("prev-run");
            }

            @Test
            @DisplayName("creates run directory structure")
            void createsRunDirectory() {
                storage.saveRun(createRunData("exp-1", "run-1"));

                Path runDir = tempDir.resolve("experiments/exp-1/runs/run-1");
                assertThat(runDir).exists();
                assertThat(runDir.resolve("run.json")).exists();
            }
        }

        @Nested
        @DisplayName("Events (JSONL)")
        class EventTests {

            @Test
            @DisplayName("appends events in JSONL format")
            void appendsEventsInJsonl() {
                storage.appendEvent("exp-1", "run-1", TestEvents.llmCall());
                storage.appendEvent("exp-1", "run-1", TestEvents.bashSuccess());

                Path eventsFile = tempDir.resolve("experiments/exp-1/runs/run-1/events.jsonl");
                assertThat(eventsFile).exists();
            }

            @Test
            @DisplayName("loads events preserving types")
            void loadsEventsWithTypes() {
                storage.appendEvent("exp-1", "run-1", TestEvents.llmCall());
                storage.appendEvent("exp-1", "run-1", TestEvents.bashSuccess());
                storage.appendEvent("exp-1", "run-1", TestEvents.stateChangePlanToImplement());

                List<JournalEvent> events = storage.loadEvents("exp-1", "run-1");

                assertThat(events).hasSize(3);
                assertThat(events.get(0).type()).isEqualTo("llm_call");
                assertThat(events.get(1).type()).isEqualTo("tool_call");
                assertThat(events.get(2).type()).isEqualTo("state_change");
            }

            @Test
            @DisplayName("persists events across storage instances")
            void persistsEventsAcrossInstances() {
                storage.appendEvent("exp-1", "run-1", TestEvents.llmCall());
                storage.appendEvent("exp-1", "run-1", TestEvents.tokenMetric());

                // Create new storage to read from disk
                JsonFileStorage newStorage = new JsonFileStorage(tempDir);
                List<JournalEvent> events = newStorage.loadEvents("exp-1", "run-1");

                assertThat(events).hasSize(2);
            }
        }

        @Nested
        @DisplayName("Artifacts")
        class ArtifactTests {

            @Test
            @DisplayName("saves and loads text artifact")
            void savesAndLoadsTextArtifact() {
                String content = "# Plan\n- Step 1\n- Step 2";
                storage.saveArtifact("exp-1", "run-1", "plan.md", content.getBytes());

                Optional<byte[]> loaded = storage.loadArtifact("exp-1", "run-1", "plan.md");

                assertThat(loaded).isPresent();
                assertThat(new String(loaded.get())).isEqualTo(content);
            }

            @Test
            @DisplayName("saves and loads binary artifact")
            void savesAndLoadsBinaryArtifact() {
                byte[] binary = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};
                storage.saveArtifact("exp-1", "run-1", "data.bin", binary);

                Optional<byte[]> loaded = storage.loadArtifact("exp-1", "run-1", "data.bin");

                assertThat(loaded).isPresent();
                assertThat(loaded.get()).isEqualTo(binary);
            }

            @Test
            @DisplayName("creates artifacts directory")
            void createsArtifactsDirectory() {
                storage.saveArtifact("exp-1", "run-1", "test.txt", "data".getBytes());

                Path artifactsDir = tempDir.resolve("experiments/exp-1/runs/run-1/artifacts");
                assertThat(artifactsDir).exists();
                assertThat(artifactsDir.resolve("test.txt")).exists();
            }

            @Test
            @DisplayName("lists artifacts from disk")
            void listsArtifacts() {
                storage.saveArtifact("exp-1", "run-1", "file1.txt", "data1".getBytes());
                storage.saveArtifact("exp-1", "run-1", "file2.md", "data2".getBytes());

                List<String> artifacts = storage.listArtifacts("exp-1", "run-1");
                assertThat(artifacts).containsExactlyInAnyOrder("file1.txt", "file2.md");
            }
        }

        @Nested
        @DisplayName("Integration")
        class IntegrationTests {

            @Test
            @DisplayName("complete workflow persists everything")
            void completeWorkflow() {
                // Save experiment
                storage.saveExperiment(Experiment.create("full-test")
                        .name("Full Integration Test")
                        .build());

                // Save run
                RunData runData = RunData.builder()
                        .id("integration-run")
                        .experimentId("full-test")
                        .name("integration")
                        .status(RunStatus.FINISHED)
                        .config(Config.of("model", "claude-opus"))
                        .summary(Summary.of("success", true))
                        .startTime(Instant.now())
                        .build();
                storage.saveRun(runData);

                // Append events
                storage.appendEvent("full-test", "integration-run", TestEvents.llmCall());
                storage.appendEvent("full-test", "integration-run", TestEvents.bashSuccess());

                // Save artifact
                storage.saveArtifact("full-test", "integration-run", "output.txt", "result".getBytes());

                // Create new storage and verify everything persisted
                JsonFileStorage newStorage = new JsonFileStorage(tempDir);

                assertThat(newStorage.loadExperiment("full-test")).isPresent();
                assertThat(newStorage.loadRun("full-test", "integration-run")).isPresent();
                assertThat(newStorage.loadEvents("full-test", "integration-run")).hasSize(2);
                assertThat(newStorage.loadArtifact("full-test", "integration-run", "output.txt")).isPresent();
            }
        }

        private RunData createRunData(String experimentId, String runId) {
            return RunData.builder()
                    .id(runId)
                    .experimentId(experimentId)
                    .status(RunStatus.RUNNING)
                    .startTime(Instant.now())
                    .build();
        }
    }
}
