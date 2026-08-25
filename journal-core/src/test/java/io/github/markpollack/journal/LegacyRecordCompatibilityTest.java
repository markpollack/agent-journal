package io.github.markpollack.journal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.github.markpollack.journal.derived.DerivedEvent;
import io.github.markpollack.journal.derived.StepCostEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.ToolCallEvent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading a pre-1.9.0 journal back: <strong>a field that was never captured must not read as a
 * plausible value.</strong>
 *
 * <p>
 * The 1.9.0 capture fields are additive, so older {@code events.jsonl} / {@code analysis.jsonl}
 * still deserialize — but Jackson's default for an absent primitive {@code int}/{@code long} is
 * {@code 0}, and {@code 0} is a perfectly plausible turn ordinal and a perfectly plausible
 * duration. Left alone, every historical tool call would read as "issued by turn 0, took no time",
 * and nothing would flag it. That is the same class of silent, confident wrongness the release
 * exists to eliminate, so absent ordinals and durations resolve to -1 instead.
 *
 * <p>
 * These fixtures are byte-shaped like the lines the 1.8.x recorder actually wrote.
 */
@DisplayName("pre-1.9.0 records read back as 'not captured', not as zero")
class LegacyRecordCompatibilityTest {

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Test
    @DisplayName("a 1.8.x tool_call has no turn ordinal, and says so")
    void legacyToolCallHasUnknownTurnIndex() throws Exception {
        String legacy = """
                {"@type":"tool_call","timestamp":"2026-08-01T00:00:00Z","toolName":"Read",
                 "input":{},"output":null,"durationMs":42,"success":true,"errorMessage":null,
                 "id":"toolu_1","kind":"READ"}""";

        JournalEvent event = mapper().readValue(legacy, JournalEvent.class);

        assertThat(event).isInstanceOf(ToolCallEvent.class);
        ToolCallEvent tc = (ToolCallEvent) event;
        assertThat(tc.turnIndex()).isEqualTo(-1);
        assertThat(tc.turnId()).isNull();
        // Everything that was captured still round-trips untouched.
        assertThat(tc.id()).isEqualTo("toolu_1");
        assertThat(tc.durationMs()).isEqualTo(42L);
        assertThat(tc.success()).isTrue();
    }

    @Test
    @DisplayName("a 1.8.x step_cost has no ordinal and no duration, and says so")
    void legacyStepCostHasUnknownOrdinalAndDuration() throws Exception {
        String legacy = """
                {"@type":"step_cost","timestamp":"2026-08-01T00:00:00Z","runId":"run-1",
                 "stepId":"toolu_1","turnId":"msg_1","toolName":"Read","inputTokens":100,
                 "outputTokens":200,"attributedCostUsd":0.01,"actualRunCostUsd":0.04,
                 "attributionMethod":"OUTPUT_TOKEN_PROPORTIONAL","vendor":"claude-code"}""";

        DerivedEvent event = mapper().readValue(legacy, DerivedEvent.class);

        assertThat(event).isInstanceOf(StepCostEvent.class);
        StepCostEvent sc = (StepCostEvent) event;
        assertThat(sc.turnIndex()).isEqualTo(-1);
        assertThat(sc.durationMs()).isEqualTo(-1L);
        // Token counts legitimately default to 0: "not recorded" and "zero" are the same claim
        // about volume, unlike an ordinal or a duration.
        assertThat(sc.thinkingTokens()).isZero();
        assertThat(sc.cacheReadTokens()).isZero();
        // The cost attribution it did carry is preserved exactly.
        assertThat(sc.attributedCostUsd()).isEqualTo(0.01);
        assertThat(sc.actualRunCostUsd()).isEqualTo(0.04);
    }

    @Test
    @DisplayName("a 1.9.0 record round-trips its new fields through the same reader")
    void currentRecordRoundTrips() throws Exception {
        ObjectMapper mapper = mapper();
        ToolCallEvent original = ToolCallEvent.builder()
                .toolName("Bash").id("toolu_9").durationMs(1500).turnIndex(7).turnId("msg_7").build();

        ToolCallEvent back = (ToolCallEvent) mapper.readValue(mapper.writeValueAsString(original),
                JournalEvent.class);

        assertThat(back.turnIndex()).isEqualTo(7);
        assertThat(back.turnId()).isEqualTo("msg_7");
        assertThat(back.durationMs()).isEqualTo(1500L);
    }
}
