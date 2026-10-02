package io.github.markpollack.journal.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link LLMCallEvent.Builder}.
 */
class LLMCallEventBuilderTest {

    @Test
    void inputTokensReplacesOnlyTheInputCount() {
        LLMCallEvent event = LLMCallEvent.builder()
                .model("m")
                .tokenUsage(new TokenUsage(1, 2, 3, 4, 5, 6))
                .inputTokens(10)
                .build();

        assertThat(event.tokenUsage()).isEqualTo(new TokenUsage(10, 2, 3, 4, 5, 6));
    }

    @Test
    void outputTokensReplacesOnlyTheOutputCount() {
        LLMCallEvent event = LLMCallEvent.builder()
                .model("m")
                .tokenUsage(new TokenUsage(1, 2, 3, 4, 5, 6))
                .outputTokens(20)
                .build();

        assertThat(event.tokenUsage()).isEqualTo(new TokenUsage(1, 20, 3, 4, 5, 6));
    }

    @Test
    void inputAndOutputTokensWithoutEarlierUsageLeaveTheOtherCountsZero() {
        LLMCallEvent event = LLMCallEvent.builder()
                .model("m")
                .inputTokens(10)
                .outputTokens(20)
                .build();

        assertThat(event.tokenUsage()).isEqualTo(TokenUsage.of(10, 20));
    }
}
