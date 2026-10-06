package io.github.markpollack.journal.integration;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.RunStatus;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A workflow engine can record a step with the journal's existing API and read it back from
 * disk: the step carries the workflow's own run ID and step identifiers, and a step that
 * returned and a step that threw stay distinguishable after reload.
 *
 * <p>This is a compatibility demonstration, not a design. It uses only what a workflow library
 * already calls: {@link Journal#registerEventType}, {@link Run#logEvent}, {@link Run#logMetric}
 * and the built-in {@link CustomEvent}. {@link StepEvent} copies the shape of the step event such
 * a library registers today. Nothing here settles how a workflow's committed facts should be
 * projected into the journal: there is no event sequence, no scope, no delivery guarantee and no
 * de-duplication.
 */
@DisplayName("Workflow step: record and reload")
class WorkflowStepRecordReloadTest {

    /** The workflow's own run identifier. It is not the journal run's ID. */
    private static final String WORKFLOW_RUN_ID = "wf-run-0001";

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    enum NodeType { DETERMINISTIC, AGENT }

    /** The shape of a workflow library's step event: an instant, a duration, an enum, optional fields. */
    record StepEvent(
            Instant timestamp,
            String workflowRunId,
            String workflowName,
            String stepName,
            String fromStep,
            NodeType nodeType,
            Duration stepDuration,
            long tokensUsed,
            double costUsd,
            String routingLabel,
            String tracePath
    ) implements JournalEvent {

        @Override
        public String type() {
            return "workflow_step";
        }

        @Override
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type());
            map.put("workflowRunId", workflowRunId);
            map.put("stepName", stepName);
            return map;
        }
    }

    /**
     * Runs one step the way a workflow engine would: a returned value is a success, an unchecked
     * exception is a failure, recorded as the code {@code STEP_FAILED} and the message
     * {@code "<exception class>: <message>"}. The step event and the outcome are logged either
     * way, and the exception is rethrown.
     */
    private static <T> T runStep(Run run, String stepName, String invocationId, String attemptId, int attemptNumber,
            Supplier<T> step) {
        Instant start = Instant.now();
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("workflowRunId", WORKFLOW_RUN_ID);
        outcome.put("stepName", stepName);
        outcome.put("invocationId", invocationId);
        outcome.put("attemptId", attemptId);
        outcome.put("attemptNumber", attemptNumber);
        try {
            T value = step.get();
            outcome.put("status", "SUCCEEDED");
            outcome.put("output", String.valueOf(value));
            return value;
        } catch (RuntimeException failure) {
            outcome.put("status", "FAILED");
            outcome.put("failureCode", "STEP_FAILED");
            outcome.put("failureMessage", failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            run.logEvent(new StepEvent(start, WORKFLOW_RUN_ID, "release-check", stepName, null, NodeType.DETERMINISTIC,
                    Duration.between(start, Instant.now()), 0L, 0.0, null, null));
            run.logEvent(CustomEvent.of("workflow_step_outcome", outcome));
        }
    }

    private static Map<String, Object> outcomeOf(List<JournalEvent> events) {
        return events.stream()
                .filter(e -> e instanceof CustomEvent custom && custom.name().equals("workflow_step_outcome"))
                .map(e -> ((CustomEvent) e).attributes())
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a step that returns is reloaded from disk as a success, with its identifiers")
    void successIsReloaded(@TempDir Path dir) throws Exception {
        Journal.registerEventType("workflow_step", StepEvent.class);
        Journal.configure(new JsonFileStorage(dir));

        String journalRunId;
        StepEvent written;
        try (Run run = Journal.run("workflow-compat").config("workflowRunId", WORKFLOW_RUN_ID).start()) {
            journalRunId = run.id();
            String result = runStep(run, "compile", "inv-1", "att-1", 1, () -> "42 classes");
            assertThat(result).isEqualTo("42 classes");
            run.logMetric("workflow.steps.total", 1);
            run.logMetric("workflow.duration.ms.compile", 12L);
            written = (StepEvent) Journal.storage().loadEvents("workflow-compat", journalRunId).get(0);
        }
        // The journal's run ID is its own; the workflow's run ID travels as data.
        assertThat(journalRunId).isNotEqualTo(WORKFLOW_RUN_ID);

        Journal.reset();
        JsonFileStorage reader = new JsonFileStorage(dir);
        List<JournalEvent> events = reader.loadEvents("workflow-compat", journalRunId);

        // The step, its outcome, and one event per metric logged.
        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(StepEvent.class).isEqualTo(written);
        StepEvent step = (StepEvent) events.get(0);
        assertThat(step.workflowRunId()).isEqualTo(WORKFLOW_RUN_ID);
        assertThat(step.stepName()).isEqualTo("compile");
        assertThat(step.nodeType()).isEqualTo(NodeType.DETERMINISTIC);
        assertThat(step.stepDuration()).isEqualTo(written.stepDuration());
        assertThat(step.fromStep()).isNull();
        assertThat(step.routingLabel()).isNull();
        assertThat(step.tracePath()).isNull();

        assertThat(outcomeOf(events))
                .containsEntry("workflowRunId", WORKFLOW_RUN_ID)
                .containsEntry("stepName", "compile")
                .containsEntry("invocationId", "inv-1")
                .containsEntry("attemptId", "att-1")
                .containsEntry("attemptNumber", 1)
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("output", "42 classes")
                .doesNotContainKeys("failureCode", "failureMessage");

        RunData run = reader.loadRun("workflow-compat", journalRunId).orElseThrow();
        assertThat(run.status()).isEqualTo(RunStatus.FINISHED);
        assertThat(run.summary().values()).containsEntry("success", true);
        assertThat(run.config().values()).containsEntry("workflowRunId", WORKFLOW_RUN_ID);
        assertThat(run.errorMessage()).isNull();

        // On disk the step is a line of its registered type; the outcome is a built-in custom event.
        List<String> lines = Files.readAllLines(dir.resolve("experiments/workflow-compat/runs/" + journalRunId
                + "/events.jsonl"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("\"@type\":\"workflow_step\"")
                .contains("\"workflowRunId\":\"" + WORKFLOW_RUN_ID + "\""));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("\"@type\":\"custom\"")
                .contains("workflow_step_outcome"));
    }

    @Test
    @DisplayName("a step that throws is reloaded from disk as a failure, never as a success")
    void thrownFailureIsReloaded(@TempDir Path dir) {
        Journal.registerEventType("workflow_step", StepEvent.class);
        Journal.configure(new JsonFileStorage(dir));

        Run run = Journal.run("workflow-compat").config("workflowRunId", WORKFLOW_RUN_ID).start();
        String journalRunId = run.id();
        assertThatThrownBy(() -> {
            try (run) {
                try {
                    runStep(run, "test", "inv-2", "att-2", 1, () -> {
                        throw new IllegalStateException("3 tests failed");
                    });
                } catch (RuntimeException failure) {
                    // close() cannot see the exception, so the failure is recorded inside the block.
                    run.fail(failure);
                    throw failure;
                }
            }
        }).isInstanceOf(IllegalStateException.class).hasMessage("3 tests failed");

        Journal.reset();
        JsonFileStorage reader = new JsonFileStorage(dir);
        List<JournalEvent> events = reader.loadEvents("workflow-compat", journalRunId);

        assertThat(events).hasSize(2);
        assertThat(((StepEvent) events.get(0)).stepName()).isEqualTo("test");
        assertThat(outcomeOf(events))
                .containsEntry("workflowRunId", WORKFLOW_RUN_ID)
                .containsEntry("stepName", "test")
                .containsEntry("invocationId", "inv-2")
                .containsEntry("attemptId", "att-2")
                .containsEntry("status", "FAILED")
                .containsEntry("failureCode", "STEP_FAILED")
                .containsEntry("failureMessage", "java.lang.IllegalStateException: 3 tests failed")
                .doesNotContainKey("output");

        RunData reloaded = reader.loadRun("workflow-compat", journalRunId).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(RunStatus.FAILED);
        assertThat(reloaded.summary().values()).containsEntry("success", false);
        assertThat(reloaded.errorMessage()).isEqualTo("3 tests failed");
        assertThat(reloaded.errorType()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    @DisplayName("two journal runs can carry the same workflow run ID, as after a restart")
    void twoRecordingsShareAWorkflowRun(@TempDir Path dir) {
        Journal.registerEventType("workflow_step", StepEvent.class);
        Journal.configure(new JsonFileStorage(dir));

        String first;
        String second;
        try (Run run = Journal.run("workflow-compat").config("workflowRunId", WORKFLOW_RUN_ID).start()) {
            first = run.id();
            runStep(run, "compile", "inv-1", "att-1", 1, () -> "ok");
        }
        try (Run run = Journal.run("workflow-compat").config("workflowRunId", WORKFLOW_RUN_ID).start()) {
            second = run.id();
            runStep(run, "package", "inv-3", "att-3", 1, () -> "ok");
        }

        JsonFileStorage reader = new JsonFileStorage(dir);
        assertThat(first).isNotEqualTo(second);
        assertThat(reader.listRuns("workflow-compat")).hasSize(2).allSatisfy(run -> {
            assertThat(run.config().values()).containsEntry("workflowRunId", WORKFLOW_RUN_ID);
            // The workflow run is not a parent journal run.
            assertThat(run.parentRunId()).isNull();
        });
        assertThat(outcomeOf(reader.loadEvents("workflow-compat", second))).containsEntry("stepName", "package");
    }

    @Test
    @DisplayName("the same recording works on in-memory storage, which a workflow library's tests use")
    void worksInMemory() {
        Journal.registerEventType("workflow_step", StepEvent.class);
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);

        String journalRunId;
        try (Run run = Journal.run("workflow-compat").start()) {
            journalRunId = run.id();
            runStep(run, "compile", "inv-1", "att-1", 1, () -> "ok");
        }

        assertThat(storage.loadEvents("workflow-compat", journalRunId))
                .filteredOn(StepEvent.class::isInstance).singleElement()
                .satisfies(e -> assertThat(((StepEvent) e).workflowRunId()).isEqualTo(WORKFLOW_RUN_ID));
    }
}
