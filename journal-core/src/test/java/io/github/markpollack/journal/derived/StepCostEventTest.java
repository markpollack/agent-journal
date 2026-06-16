package io.github.markpollack.journal.derived;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.markpollack.journal.trace.AttributionMethod;
import io.github.markpollack.journal.trace.JournalStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StepCostEventTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Test
    void roundTripsPolymorphicallyAsDerivedEvent() throws Exception {
        StepCostEvent original = new StepCostEvent(Instant.parse("2026-06-16T12:00:00Z"), "run-1", "toolu_1",
                "msg_1", "Bash", 10, 742, 0.031, 0.094841, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, "claude-code");

        String json = mapper.writeValueAsString(original);
        // discriminator distinguishes derived types, like execution events
        assertThat(json).contains("\"@type\":\"step_cost\"");

        DerivedEvent back = mapper.readValue(json, DerivedEvent.class);
        assertThat(back).isInstanceOf(StepCostEvent.class).isEqualTo(original);
        assertThat(back.type()).isEqualTo("step_cost");
        assertThat(back.runId()).isEqualTo("run-1");
        assertThat(back.stepId()).isEqualTo("toolu_1");
    }

    @Test
    void bridgesFromJournalStepPreservingGroundTruthAndAllocation() {
        JournalStep step = new JournalStep("run-1", "msg_1", "toolu_1", "Bash", 10, 742,
                0.031, 0.094841, AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL, false, null, "claude-code", false);

        Instant when = Instant.parse("2026-06-16T12:00:00Z");
        StepCostEvent event = StepCostEvent.fromStep(step, when);

        assertThat(event.timestamp()).isEqualTo(when);
        assertThat(event.stepId()).isEqualTo("toolu_1");        // joins to the execution ToolCallEvent
        assertThat(event.turnId()).isEqualTo("msg_1");
        assertThat(event.attributedCostUsd()).isEqualTo(0.031);
        assertThat(event.actualRunCostUsd()).isEqualTo(0.094841);
        assertThat(event.attributionMethod()).isEqualTo(AttributionMethod.OUTPUT_TOKEN_PROPORTIONAL);
        assertThat(event.vendor()).isEqualTo("claude-code");
    }
}
