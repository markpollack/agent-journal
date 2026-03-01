package io.github.markpollack.journal.integration;

import io.github.markpollack.journal.*;
import io.github.markpollack.journal.call.Call;
import io.github.markpollack.journal.call.CallTracker;
import io.github.markpollack.journal.event.*;
import io.github.markpollack.journal.metric.Counter;
import io.github.markpollack.journal.metric.Gauge;
import io.github.markpollack.journal.metric.InMemoryMetricRegistry;
import io.github.markpollack.journal.metric.MetricRegistry;
import io.github.markpollack.journal.storage.*;

// Alias to avoid conflict with org.junit.jupiter.api.Tags
import io.github.markpollack.journal.metric.Tags;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive integration tests for Phase 1 of tuvium-runtime-core.
 *
 * <p>These tests verify that all components work together correctly in
 * realistic scenarios. Each test exercises multiple subsystems to ensure
 * proper integration.
 *
 * <p>Components tested:
 * <ul>
 *   <li>Journal entry point and global configuration</li>
 *   <li>Run lifecycle with events, metrics, artifacts</li>
 *   <li>CallTracker hierarchical call tracking</li>
 *   <li>Storage backends (InMemory and JsonFile)</li>
 *   <li>Event serialization and persistence</li>
 *   <li>Metric registry with counters, timers, gauges</li>
 * </ul>
 */
@DisplayName("Phase 1 Integration Tests")
class Phase1IntegrationTest {

    @BeforeEach
    void setUp() {
        Journal.reset();
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Nested
    @DisplayName("Complete Agent Workflow")
    class CompleteAgentWorkflow {

        @Test
        @DisplayName("simulates full coding agent session with all components")
        void fullCodingAgentSession() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("feature-implementation")
                    .name("add-dark-mode")
                    .config("model", "claude-opus-4.5")
                    .config("maxTokens", 8000)
                    .config("task", "Add dark mode toggle to settings page")
                    .tag("type", "feature")
                    .tag("priority", "high")
                    .agent("code-assistant")
                    .start()) {

                runId = run.id();
                CallTracker calls = run.calls();
                MetricRegistry metrics = run.metrics();

                // Phase 1: Planning
                try (Call planningPhase = calls.startCall("planning", Tags.of("phase", "1"))) {
                    run.logEvent(StateChangeEvent.of("init", "planning", "Starting planning phase"));

                    // LLM call for initial analysis
                    try (Call analyzeCall = planningPhase.child("llm-analyze")) {
                        run.logEvent(LLMCallEvent.builder()
                                .model("claude-opus-4.5")
                                .tokenUsage(TokenUsage.of(500, 200))
                                .cost(CostBreakdown.of(0.0075, 0.003))
                                .timing(TimingInfo.of(1500))
                                .build());
                        metrics.counter("llm.calls").increment();
                    }

                    run.logArtifact("plan.md", "# Implementation Plan\n\n1. Create theme context\n2. Add toggle component\n3. Update styles");
                }

                // Phase 2: Implementation
                try (Call implPhase = calls.startCall("implementation", Tags.of("phase", "2"))) {
                    run.logEvent(StateChangeEvent.of("planning", "implementing", "Starting implementation"));

                    // Multiple coding turns
                    for (int turn = 1; turn <= 3; turn++) {
                        try (Call turnCall = implPhase.child("turn-" + turn, Tags.of("turn", String.valueOf(turn)))) {
                            // Read operation
                            run.logEvent(ToolCallEvent.success(
                                    "read",
                                    Map.of("path", "src/components/Settings.tsx"),
                                    Map.of("lines", 150),
                                    50));

                            // LLM generates code
                            run.logEvent(LLMCallEvent.builder()
                                    .model("claude-opus-4.5")
                                    .tokenUsage(TokenUsage.of(1200, 800))
                                    .cost(CostBreakdown.of(0.018, 0.012))
                                    .timing(TimingInfo.of(3000))
                                    .build());
                            metrics.counter("llm.calls").increment();
                            metrics.gauge("tokens.last_call", Tags.of("turn", String.valueOf(turn))).set(2000);

                            // Write operation
                            run.logEvent(ToolCallEvent.success(
                                    "write",
                                    Map.of("path", "src/components/DarkModeToggle.tsx"),
                                    Map.of("linesWritten", 45),
                                    30));
                            metrics.counter("files.written").increment();
                        }
                    }
                }

                // Phase 3: Testing
                try (Call testPhase = calls.startCall("testing", Tags.of("phase", "3"))) {
                    run.logEvent(StateChangeEvent.of("implementing", "testing", "Running tests"));

                    try (Call runTests = testPhase.child("npm-test")) {
                        run.logEvent(ToolCallEvent.success(
                                "bash",
                                Map.of("command", "npm test"),
                                Map.of("passed", 47, "failed", 0),
                                12000));

                        runTests.setAttribute("testsRun", 47);
                        runTests.setAttribute("testsPassed", 47);
                    }
                }

                // Phase 4: Git commit
                try (Call gitPhase = calls.startCall("git-commit", Tags.of("phase", "4"))) {
                    run.logEvent(StateChangeEvent.of("testing", "committing", "Committing changes"));

                    run.logEvent(GitPatchEvent.of("main", List.of(
                            GitPatchEvent.FileChange.added("src/components/DarkModeToggle.tsx", 45),
                            GitPatchEvent.FileChange.modified("src/components/Settings.tsx", 12, 3),
                            GitPatchEvent.FileChange.modified("src/styles/theme.css", 25, 0)
                    )));

                    run.logEvent(GitCommitEvent.of(
                            "a1b2c3d4e5f6",
                            "feat: Add dark mode toggle to settings page",
                            "feature/dark-mode"));
                }

                // Final summary
                run.setSummary("filesChanged", 3);
                run.setSummary("linesAdded", 82);
                run.setSummary("linesRemoved", 3);
                run.setSummary("testsAdded", 5);
                run.setSummary("testsPassed", true);
                run.setSummary("success", true);
            }

            // Verify complete persistence
            var runs = storage.listRuns("feature-implementation");
            assertThat(runs).hasSize(1);

            RunData runData = runs.get(0);
            assertThat(runData.id()).isEqualTo(runId);
            assertThat(runData.name()).isEqualTo("add-dark-mode");
            assertThat(runData.status()).isEqualTo(RunStatus.FINISHED);
            assertThat(runData.agentId()).isEqualTo("code-assistant");

            // Config preserved
            assertThat(runData.config().get("model", String.class)).isEqualTo("claude-opus-4.5");
            assertThat(runData.config().get("task", String.class)).contains("dark mode");

            // Summary captured
            assertThat(runData.summary().get("filesChanged", Integer.class)).isEqualTo(3);
            assertThat(runData.summary().get("success", Boolean.class)).isTrue();

            // Events persisted
            var events = storage.loadEvents("feature-implementation", runId);
            assertThat(events).isNotEmpty();

            // Count different event types
            long llmCalls = events.stream().filter(e -> e instanceof LLMCallEvent).count();
            long toolCalls = events.stream().filter(e -> e instanceof ToolCallEvent).count();
            long stateChanges = events.stream().filter(e -> e instanceof StateChangeEvent).count();
            long gitEvents = events.stream().filter(e -> e instanceof GitCommitEvent || e instanceof GitPatchEvent).count();

            assertThat(llmCalls).isEqualTo(4);  // 1 analyze + 3 coding turns
            assertThat(toolCalls).isGreaterThanOrEqualTo(6);  // Multiple read/write/bash
            assertThat(stateChanges).isEqualTo(4);  // init→planning→implementing→testing→committing
            assertThat(gitEvents).isEqualTo(2);  // 1 patch + 1 commit

            // Artifact persisted
            var artifact = storage.loadArtifact("feature-implementation", runId, "plan.md");
            assertThat(artifact).isPresent();
            assertThat(new String(artifact.get())).contains("Implementation Plan");
        }

        @Test
        @DisplayName("handles agent failure with proper error capture")
        void agentFailureWithErrorCapture() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            RuntimeException error = new RuntimeException("Build failed with exit code 1");

            String runId;
            try (Run run = Journal.run("failing-task")
                    .config("model", "claude-sonnet")
                    .start()) {
                runId = run.id();

                try (Call buildCall = run.calls().startCall("build")) {
                    run.logEvent(ToolCallEvent.failure(
                            "bash",
                            Map.of("command", "npm run build"),
                            "Build failed with exit code 1",
                            5000));

                    buildCall.fail(error);
                }

                run.setSummary("buildPassed", false);
                run.fail(error);
            }

            var runs = storage.listRuns("failing-task");
            assertThat(runs).hasSize(1);

            RunData runData = runs.get(0);
            assertThat(runData.status()).isEqualTo(RunStatus.FAILED);
            assertThat(runData.summary().get("success", Boolean.class)).isFalse();
            assertThat(runData.errorMessage()).isEqualTo("Build failed with exit code 1");
            assertThat(runData.errorType()).isEqualTo("java.lang.RuntimeException");
        }
    }

    @Nested
    @DisplayName("Call Tracker Integration")
    class CallTrackerIntegration {

        @Test
        @DisplayName("tracks deeply nested call hierarchies")
        void deeplyNestedCallHierarchies() throws InterruptedException {
            try (Run run = Journal.run("nested-calls").start()) {
                CallTracker calls = run.calls();

                try (Call root = calls.startCall("agent-loop")) {
                    root.setAttribute("maxIterations", 10);

                    try (Call turn1 = root.child("turn-1")) {
                        turn1.setAttribute("turnNumber", 1);

                        try (Call llm = turn1.child("llm-call")) {
                            llm.setAttribute("model", "claude-opus");
                            llm.event("prompt-sent");

                            try (Call thinking = llm.child("thinking")) {
                                thinking.setAttribute("thinkingBudget", 5000);
                                Thread.sleep(10);
                            }

                            llm.event("response-received");
                        }

                        try (Call tool = turn1.child("tool-call")) {
                            tool.setAttribute("toolName", "bash");
                            tool.event("tool-started");
                            tool.event("tool-completed", Map.of("exitCode", 0));
                        }
                    }

                    try (Call turn2 = root.child("turn-2")) {
                        turn2.setAttribute("turnNumber", 2);
                        // Simpler second turn
                    }
                }

                // Verify hierarchy
                assertThat(calls.callCount()).isEqualTo(6);  // root + turn1 + llm + thinking + tool + turn2
                assertThat(calls.rootCalls()).hasSize(1);

                Call root = calls.rootCalls().get(0);
                assertThat(root.operation()).isEqualTo("agent-loop");
                assertThat(root.attributes()).containsEntry("maxIterations", 10);
            }
        }

        @Test
        @DisplayName("call durations are accurate within hierarchy")
        void callDurationsAccurate() throws InterruptedException {
            try (Run run = Journal.run("timed-calls").start()) {
                CallTracker calls = run.calls();

                Call parent;
                Call child;
                try (Call p = calls.startCall("parent")) {
                    parent = p;
                    Thread.sleep(30);

                    try (Call c = p.child("child")) {
                        child = c;
                        Thread.sleep(50);
                    }

                    Thread.sleep(20);
                }

                assertThat(parent.duration().toMillis()).isGreaterThanOrEqualTo(100);
                assertThat(child.duration().toMillis()).isGreaterThanOrEqualTo(50);
                assertThat(parent.duration()).isGreaterThan(child.duration());
            }
        }
    }

    @Nested
    @DisplayName("Metrics Integration")
    class MetricsIntegration {

        @Test
        @DisplayName("all metric types work together")
        void allMetricTypesTogether() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            try (Run run = Journal.run("metrics-test").start()) {
                MetricRegistry metrics = run.metrics();

                // Counters
                Counter tokenCounter = metrics.counter("tokens.total");
                tokenCounter.increment(1000);
                tokenCounter.increment(500);

                Counter callCounter = metrics.counter("llm.calls");
                callCounter.increment();
                callCounter.increment();
                callCounter.increment();

                // Tagged counters
                metrics.counter("tool.calls", Tags.of("tool", "bash")).increment();
                metrics.counter("tool.calls", Tags.of("tool", "read")).increment(5);

                // Gauges
                Gauge memory = metrics.gauge("memory.used_mb");
                memory.set(256);
                memory.set(320);  // Overwrites

                // Tagged gauges
                metrics.gauge("model.temperature", Tags.of("model", "opus")).set(0.7);

                // Timers
                io.github.markpollack.journal.metric.Timer apiTimer = metrics.timer("api.latency");
                io.github.markpollack.journal.metric.Timer.Sample sample = apiTimer.start();
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sample.stop();

                // Log metrics as events too
                run.logMetric("session.cost", 0.05);

                // Summary with aggregate
                run.setSummary("totalTokens", 1500);
            }

            // Verify persistence
            var runs = storage.listRuns("metrics-test");
            assertThat(runs).hasSize(1);
        }

        @Test
        @DisplayName("metrics registry is isolated per run")
        void metricsIsolatedPerRun() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            // Run 1
            try (Run run1 = Journal.run("isolation-test").start()) {
                run1.metrics().counter("calls").increment(10);
            }

            // Run 2 - should have fresh metrics
            try (Run run2 = Journal.run("isolation-test").start()) {
                Counter counter = run2.metrics().counter("calls");
                // New counter starts at 0
                counter.increment();

                // Access via InMemoryMetricRegistry to check value
                InMemoryMetricRegistry registry = ((DefaultRun) run2).inMemoryMetrics();
                assertThat(registry.snapshot().counterValue("calls")).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("Storage Integration")
    class StorageIntegration {

        @Test
        @DisplayName("JsonFileStorage persists and reloads complete runs")
        void jsonFileStoragePersistence(@TempDir Path tempDir) {
            JsonFileStorage storage = new JsonFileStorage(tempDir);
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("persistence-test")
                    .name("persisted-run")
                    .config("model", "claude-opus")
                    .config("nested", Map.of("a", 1, "b", 2))
                    .tag("env", "test")
                    .start()) {
                runId = run.id();

                run.logEvent(LLMCallEvent.builder()
                        .model("claude-opus")
                        .tokenUsage(TokenUsage.of(100, 50))
                        .cost(CostBreakdown.of(0.001, 0.0005))
                        .timing(TimingInfo.of(500))
                        .build());

                run.logArtifact("output.txt", "Test artifact content");
                run.setSummary("completed", true);
            }

            // Create new storage instance to simulate fresh load
            JsonFileStorage reloadedStorage = new JsonFileStorage(tempDir);

            // Verify experiment
            var exp = reloadedStorage.loadExperiment("persistence-test");
            assertThat(exp).isPresent();

            // Verify run
            var runs = reloadedStorage.listRuns("persistence-test");
            assertThat(runs).hasSize(1);

            RunData runData = runs.get(0);
            assertThat(runData.id()).isEqualTo(runId);
            assertThat(runData.name()).isEqualTo("persisted-run");
            assertThat(runData.config().get("model", String.class)).isEqualTo("claude-opus");
            assertThat(runData.summary().get("completed", Boolean.class)).isTrue();

            // Verify events
            var events = reloadedStorage.loadEvents("persistence-test", runId);
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(LLMCallEvent.class);

            // Verify artifact
            var artifact = reloadedStorage.loadArtifact("persistence-test", runId, "output.txt");
            assertThat(artifact).isPresent();
            assertThat(new String(artifact.get())).isEqualTo("Test artifact content");
        }

        @Test
        @DisplayName("multiple runs under same experiment")
        void multipleRunsSameExperiment() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            List<String> runIds = new ArrayList<>();

            for (int i = 1; i <= 5; i++) {
                try (Run run = Journal.run("multi-run-test")
                        .name("run-" + i)
                        .config("iteration", i)
                        .start()) {
                    runIds.add(run.id());
                    run.logMetric("score", i * 10.0);
                    run.setSummary("iteration", i);
                }
            }

            // Single experiment
            var experiments = storage.listExperiments();
            long matchingExperiments = experiments.stream()
                    .filter(e -> e.id().equals("multi-run-test"))
                    .count();
            assertThat(matchingExperiments).isEqualTo(1);

            // Multiple runs
            var runs = storage.listRuns("multi-run-test");
            assertThat(runs).hasSize(5);

            // Each run has unique ID
            Set<String> uniqueIds = new HashSet<>();
            for (RunData run : runs) {
                uniqueIds.add(run.id());
            }
            assertThat(uniqueIds).hasSize(5);
            assertThat(uniqueIds).containsExactlyInAnyOrderElementsOf(runIds);
        }
    }

    @Nested
    @DisplayName("Run Linking")
    class RunLinking {

        @Test
        @DisplayName("retry chain with previousRunId linking")
        void retryChainLinking() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String attempt1Id;
            String attempt2Id;
            String attempt3Id;

            // Attempt 1 - fails
            try (Run attempt1 = Journal.run("retry-chain")
                    .name("attempt-1")
                    .start()) {
                attempt1Id = attempt1.id();
                attempt1.fail(new RuntimeException("Test failed"));
            }

            // Attempt 2 - also fails, links to attempt 1
            try (Run attempt2 = Journal.run("retry-chain")
                    .name("attempt-2")
                    .previousRun(attempt1Id)
                    .start()) {
                attempt2Id = attempt2.id();
                attempt2.fail(new RuntimeException("Build failed"));
            }

            // Attempt 3 - succeeds, links to attempt 2
            try (Run attempt3 = Journal.run("retry-chain")
                    .name("attempt-3")
                    .previousRun(attempt2Id)
                    .start()) {
                attempt3Id = attempt3.id();
                attempt3.setSummary("success", true);
            }

            // Verify chain
            var runs = storage.listRuns("retry-chain");
            assertThat(runs).hasSize(3);

            Map<String, RunData> runById = new HashMap<>();
            for (RunData run : runs) {
                runById.put(run.id(), run);
            }

            // Attempt 1 has no previous
            assertThat(runById.get(attempt1Id).previousRunId()).isNull();
            assertThat(runById.get(attempt1Id).status()).isEqualTo(RunStatus.FAILED);

            // Attempt 2 links to attempt 1
            assertThat(runById.get(attempt2Id).previousRunId()).isEqualTo(attempt1Id);
            assertThat(runById.get(attempt2Id).status()).isEqualTo(RunStatus.FAILED);

            // Attempt 3 links to attempt 2 and succeeded
            assertThat(runById.get(attempt3Id).previousRunId()).isEqualTo(attempt2Id);
            assertThat(runById.get(attempt3Id).status()).isEqualTo(RunStatus.FINISHED);
        }

        @Test
        @DisplayName("parent-child runs for multi-agent orchestration")
        void parentChildMultiAgent() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String orchestratorId;
            String plannerChildId;
            String coderChildId;
            String testerChildId;

            try (Run orchestrator = Journal.run("multi-agent-task")
                    .name("orchestrator")
                    .agent("supervisor")
                    .start()) {
                orchestratorId = orchestrator.id();

                // Planner sub-agent
                try (Run planner = Journal.run("multi-agent-task")
                        .name("planner")
                        .parentRun(orchestratorId)
                        .agent("planner")
                        .start()) {
                    plannerChildId = planner.id();
                    planner.setSummary("planCreated", true);
                }

                // Coder sub-agent
                try (Run coder = Journal.run("multi-agent-task")
                        .name("coder")
                        .parentRun(orchestratorId)
                        .agent("coder")
                        .start()) {
                    coderChildId = coder.id();
                    coder.setSummary("filesChanged", 3);
                }

                // Tester sub-agent
                try (Run tester = Journal.run("multi-agent-task")
                        .name("tester")
                        .parentRun(orchestratorId)
                        .agent("tester")
                        .start()) {
                    testerChildId = tester.id();
                    tester.setSummary("testsPassed", true);
                }

                orchestrator.setSummary("success", true);
            }

            // Verify hierarchy
            var runs = storage.listRuns("multi-agent-task");
            assertThat(runs).hasSize(4);

            // Orchestrator has no parent
            RunData orchestratorData = runs.stream()
                    .filter(r -> r.id().equals(orchestratorId))
                    .findFirst().orElseThrow();
            assertThat(orchestratorData.parentRunId()).isNull();
            assertThat(orchestratorData.agentId()).isEqualTo("supervisor");

            // All children point to orchestrator
            List<RunData> children = runs.stream()
                    .filter(r -> orchestratorId.equals(r.parentRunId()))
                    .toList();
            assertThat(children).hasSize(3);

            Set<String> childAgents = new HashSet<>();
            for (RunData child : children) {
                childAgents.add(child.agentId());
            }
            assertThat(childAgents).containsExactlyInAnyOrder("planner", "coder", "tester");
        }
    }

    @Nested
    @DisplayName("Event Serialization")
    class EventSerialization {

        @Test
        @DisplayName("all event types round-trip through storage")
        void allEventTypesRoundTrip(@TempDir Path tempDir) {
            JsonFileStorage storage = new JsonFileStorage(tempDir);
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("event-roundtrip").start()) {
                runId = run.id();

                // LLMCallEvent
                run.logEvent(LLMCallEvent.builder()
                        .model("claude-opus-4.5")
                        .tokenUsage(TokenUsage.of(1000, 500))
                        .cost(CostBreakdown.of(0.015, 0.0075))
                        .timing(TimingInfo.of(2000))
                        .build());

                // ToolCallEvent - success
                run.logEvent(ToolCallEvent.success(
                        "bash",
                        Map.of("command", "ls -la"),
                        Map.of("files", List.of("a.txt", "b.txt")),
                        100));

                // ToolCallEvent - failure
                run.logEvent(ToolCallEvent.failure(
                        "read",
                        Map.of("path", "/nonexistent"),
                        "File not found",
                        50));

                // StateChangeEvent
                run.logEvent(StateChangeEvent.of("init", "running", "Started execution"));

                // GitPatchEvent
                run.logEvent(GitPatchEvent.of("main", List.of(
                        GitPatchEvent.FileChange.added("new.txt", 10),
                        GitPatchEvent.FileChange.modified("existing.txt", 5, 3),
                        GitPatchEvent.FileChange.deleted("old.txt", 20)
                )));

                // GitCommitEvent
                run.logEvent(GitCommitEvent.of("abc123", "Fix bug", "main"));

                // MetricEvent
                run.logMetric("custom.metric", 42.5, Tags.of("key", "value"));
            }

            // Reload from fresh storage
            JsonFileStorage reloaded = new JsonFileStorage(tempDir);
            var events = reloaded.loadEvents("event-roundtrip", runId);

            assertThat(events).hasSize(7);

            // Verify each type
            assertThat(events.get(0)).isInstanceOf(LLMCallEvent.class);
            LLMCallEvent llmEvent = (LLMCallEvent) events.get(0);
            assertThat(llmEvent.model()).isEqualTo("claude-opus-4.5");
            assertThat(llmEvent.tokenUsage().inputTokens()).isEqualTo(1000);

            assertThat(events.get(1)).isInstanceOf(ToolCallEvent.class);
            ToolCallEvent bashEvent = (ToolCallEvent) events.get(1);
            assertThat(bashEvent.toolName()).isEqualTo("bash");
            assertThat(bashEvent.success()).isTrue();

            assertThat(events.get(2)).isInstanceOf(ToolCallEvent.class);
            ToolCallEvent readEvent = (ToolCallEvent) events.get(2);
            assertThat(readEvent.success()).isFalse();
            assertThat(readEvent.errorMessage()).isEqualTo("File not found");

            assertThat(events.get(3)).isInstanceOf(StateChangeEvent.class);
            StateChangeEvent stateEvent = (StateChangeEvent) events.get(3);
            assertThat(stateEvent.fromState()).isEqualTo("init");
            assertThat(stateEvent.toState()).isEqualTo("running");

            assertThat(events.get(4)).isInstanceOf(GitPatchEvent.class);
            GitPatchEvent patchEvent = (GitPatchEvent) events.get(4);
            assertThat(patchEvent.fileChanges()).hasSize(3);

            assertThat(events.get(5)).isInstanceOf(GitCommitEvent.class);
            GitCommitEvent commitEvent = (GitCommitEvent) events.get(5);
            assertThat(commitEvent.sha()).isEqualTo("abc123");

            assertThat(events.get(6)).isInstanceOf(MetricEvent.class);
            MetricEvent metricEvent = (MetricEvent) events.get(6);
            assertThat(metricEvent.name()).isEqualTo("custom.metric");
            assertThat(metricEvent.value()).isEqualTo(42.5);
        }
    }

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccess {

        @Test
        @DisplayName("concurrent event logging is thread-safe")
        void concurrentEventLogging() throws InterruptedException {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            int numThreads = 10;
            int eventsPerThread = 100;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger errors = new AtomicInteger(0);

            String runId;
            try (Run run = Journal.run("concurrent-test").start()) {
                runId = run.id();

                for (int t = 0; t < numThreads; t++) {
                    final int threadNum = t;
                    Thread thread = new Thread(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < eventsPerThread; i++) {
                                run.logEvent(ToolCallEvent.success(
                                        "tool-" + threadNum,
                                        Map.of("iteration", i),
                                        "ok",
                                        1));
                                run.logMetric("thread." + threadNum, i);
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                    thread.start();
                }

                startLatch.countDown();
                boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

                assertThat(completed).isTrue();
                assertThat(errors.get()).isZero();
            }

            // Verify all events were logged
            var events = storage.loadEvents("concurrent-test", runId);
            // Each thread logs eventsPerThread ToolCallEvents + eventsPerThread MetricEvents
            assertThat(events).hasSize(numThreads * eventsPerThread * 2);
        }

        @Test
        @DisplayName("call tracker handles concurrent child creation")
        void concurrentCallTrackerAccess() throws InterruptedException {
            int numThreads = 5;
            int callsPerThread = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger errors = new AtomicInteger(0);

            try (Run run = Journal.run("concurrent-calls").start()) {
                CallTracker calls = run.calls();

                try (Call root = calls.startCall("root")) {
                    for (int t = 0; t < numThreads; t++) {
                        final int threadNum = t;
                        Thread thread = new Thread(() -> {
                            try {
                                startLatch.await();
                                for (int i = 0; i < callsPerThread; i++) {
                                    try (Call child = root.child("thread-" + threadNum + "-call-" + i)) {
                                        child.setAttribute("value", i);
                                    }
                                }
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            } finally {
                                doneLatch.countDown();
                            }
                        });
                        thread.start();
                    }

                    startLatch.countDown();
                    boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

                    assertThat(completed).isTrue();
                    assertThat(errors.get()).isZero();
                }

                // +1 for root
                assertThat(calls.callCount()).isEqualTo(1 + numThreads * callsPerThread);
            }
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("empty run with no events or artifacts")
        void emptyRun() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String runId;
            try (Run run = Journal.run("empty-run").start()) {
                runId = run.id();
                // No events, metrics, or artifacts logged
            }

            var runs = storage.listRuns("empty-run");
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).status()).isEqualTo(RunStatus.FINISHED);

            var events = storage.loadEvents("empty-run", runId);
            assertThat(events).isEmpty();
        }

        @Test
        @DisplayName("run with very long config and summary values")
        void longValues() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            String longString = "x".repeat(10000);
            Map<String, Object> deepNested = Map.of(
                    "level1", Map.of(
                            "level2", Map.of(
                                    "level3", Map.of("value", 42)
                            )
                    )
            );

            try (Run run = Journal.run("long-values")
                    .config("longKey", longString)
                    .config("nested", deepNested)
                    .start()) {
                run.setSummary("longOutput", longString);
                run.setSummary("complexResult", deepNested);
            }

            var runs = storage.listRuns("long-values");
            assertThat(runs).hasSize(1);

            RunData runData = runs.get(0);
            assertThat(runData.config().get("longKey", String.class)).hasSize(10000);
        }

        @Test
        @DisplayName("special characters in names and IDs")
        void specialCharacters() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            try (Run run = Journal.run("test-experiment")
                    .name("run with spaces & symbols!")
                    .config("key with spaces", "value with \"quotes\"")
                    .tag("emoji-tag", "✅")
                    .start()) {
                run.logArtifact("file (1).txt", "content with newlines\nand tabs\t");
                run.setSummary("unicode", "日本語テスト");
            }

            var runs = storage.listRuns("test-experiment");
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).name()).isEqualTo("run with spaces & symbols!");
        }

        @Test
        @DisplayName("run cannot log after close")
        void cannotLogAfterClose() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            Run run = Journal.run("closed-run").start();
            run.close();

            assertThatThrownBy(() -> run.logEvent(
                    ToolCallEvent.success("test", Map.of(), "ok", 0)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already");

            assertThatThrownBy(() -> run.logMetric("test", 1.0))
                    .isInstanceOf(IllegalStateException.class);

            assertThatThrownBy(() -> run.setSummary("key", "value"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("double close is idempotent")
        void doubleCloseIdempotent() {
            InMemoryStorage storage = new InMemoryStorage();
            Journal.configure(storage);

            Run run = Journal.run("double-close").start();
            String runId = run.id();
            run.close();
            run.close();  // Should not throw
            run.close();  // Should not throw

            var runs = storage.listRuns("double-close");
            assertThat(runs).hasSize(1);
            assertThat(runs.get(0).status()).isEqualTo(RunStatus.FINISHED);
        }
    }
}
