package io.github.markpollack.journal.derived;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.markpollack.journal.Experiment;
import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * R2.6: the generic per-step outcome / goal-distance channel — journal provides the slot,
 * the experiment supplies domain values; stored alongside StepCostEvent in analysis.jsonl.
 */
class StepOutcomeEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void carriesExperimentSuppliedMetricsWithoutDomainSemantics() throws Exception {
        // The experiment decides what the metrics mean — journal just carries them.
        StepOutcomeEvent outcome = StepOutcomeEvent.withGoalDistance("run-1", "toolu_1", 0.42,
                Map.of("exit_code", 0, "test_result", "PASS", "coverage_delta", 0.07, "files_touched", 3));

        String json = mapper.writeValueAsString(outcome);
        assertThat(json).contains("\"@type\":\"step_outcome\"");

        DerivedEvent back = mapper.readValue(json, DerivedEvent.class);
        assertThat(back).isInstanceOf(StepOutcomeEvent.class);
        StepOutcomeEvent ro = (StepOutcomeEvent) back;
        assertThat(ro.goalDistance()).isCloseTo(0.42, within(1e-9));
        assertThat(ro.stepId()).isEqualTo("toolu_1");
        assertThat(ro.metrics()).containsEntry("test_result", "PASS").containsEntry("files_touched", 3);
    }

    @Test
    void analysisLayerReadsGoalDistanceFromTheStream() {
        InMemoryStorage storage = new InMemoryStorage();
        storage.saveExperiment(Experiment.create("exp-1").build());
        storage.saveRun(RunData.builder().id("run-1").experimentId("exp-1").build());

        // cost (StepCostEvent) and outcome (StepOutcomeEvent) coexist in the same derived stream,
        // both keyed to the same step
        storage.appendDerivedEvent("exp-1", "run-1", new StepCostEvent(
                java.time.Instant.parse("2026-06-16T12:00:00Z"), "run-1", "toolu_1", "msg_1", "Bash",
                10, 50, 0.01, 0.05, io.github.markpollack.journal.trace.AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL,
                "claude-code"));
        storage.appendDerivedEvent("exp-1", "run-1",
                StepOutcomeEvent.withGoalDistance("run-1", "toolu_1", 0.42, Map.of("test_result", "PASS")));

        List<DerivedEvent> derived = storage.loadDerivedEvents("exp-1", "run-1");
        assertThat(derived).hasSize(2);

        // the analysis layer can pull the goal-distance signal for a step
        Double goal = derived.stream()
                .filter(d -> d instanceof StepOutcomeEvent && "toolu_1".equals(d.stepId()))
                .map(d -> ((StepOutcomeEvent) d).goalDistance())
                .findFirst().orElse(null);
        assertThat(goal).isCloseTo(0.42, within(1e-9));
    }

    @Test
    void metricsDefaultToEmptyNeverNull() {
        StepOutcomeEvent outcome = StepOutcomeEvent.of("run-1", "toolu_1", null);
        assertThat(outcome.metrics()).isNotNull().isEmpty();
        assertThat(outcome.goalDistance()).isNull();
    }
}
