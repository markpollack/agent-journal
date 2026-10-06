package io.github.markpollack.journal.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conventions behind {@link TokenUsage}'s derived figures: thinking tokens are part of the
 * output tokens, and input tokens do not include cache reads.
 */
@DisplayName("TokenUsage totals and cache share")
class TokenUsageTotalsTest {

    @Test
    @DisplayName("total() is input plus output; thinking is inside output and is not added again")
    void totalDoesNotCountThinkingTwice() {
        TokenUsage usage = new TokenUsage(6_339, 576, 146, 0, 41_216, 0);

        assertThat(usage.total()).isEqualTo(6_339 + 576);
        assertThat(usage.toMap()).containsEntry("total_tokens", 6_339 + 576);
        assertThat(LLMCallEvent.builder().provider("p").model("m").tokenUsage(usage).build().totalTokens())
                .isEqualTo(6_339 + 576);
    }

    @Test
    @DisplayName("cacheHitRatio() is the cache reads' share of input plus cache reads, never above 1")
    void cacheHitRatioStaysWithinZeroAndOne() {
        // 100 fresh input tokens beside 1,000 cache reads used to give a ratio of 10.
        TokenUsage usage = new TokenUsage(100, 50, 0, 0, 1_000, 0);

        assertThat(usage.cacheHitRatio()).isEqualTo(1_000.0 / 1_100.0);
    }

    @Test
    @DisplayName("cacheHitRatio() is 0 without any input, and 1 when everything was read from the cache")
    void cacheHitRatioEdges() {
        assertThat(new TokenUsage(0, 10, 0, 0, 0, 0).cacheHitRatio()).isZero();
        assertThat(new TokenUsage(0, 10, 0, 0, 500, 0).cacheHitRatio()).isEqualTo(1.0);
        assertThat(TokenUsage.of(100, 10).cacheHitRatio()).isZero();
    }

    @Test
    @DisplayName("effectiveInputTokens() is the input tokens, which already exclude cache reads")
    @SuppressWarnings("deprecation")
    void effectiveInputIsNeverNegative() {
        TokenUsage usage = new TokenUsage(100, 50, 0, 0, 1_000, 0);

        assertThat(usage.effectiveInputTokens()).isEqualTo(100);
    }
}
