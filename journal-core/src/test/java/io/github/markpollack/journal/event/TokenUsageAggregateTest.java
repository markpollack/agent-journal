package io.github.markpollack.journal.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CM.1: the vendor-neutral per-type aggregator ({@link TokenUsage#plus}/{@link TokenUsage#sum}) — the
 * cost-bearing summation every capture adapter reuses. Sums each token type field-wise; cache types
 * are included (the bug was that the headline dropped them).
 */
@DisplayName("TokenUsage aggregation (plus/sum)")
class TokenUsageAggregateTest {

    @Test
    @DisplayName("plus sums all six token-type fields")
    void plusSumsAllFields() {
        TokenUsage a = new TokenUsage(2, 100, 0, 20_000, 15_000, 0);
        TokenUsage b = new TokenUsage(3, 200, 0, 5_000, 30_000, 1);
        TokenUsage s = a.plus(b);
        assertThat(s.inputTokens()).isEqualTo(5);
        assertThat(s.outputTokens()).isEqualTo(300);
        assertThat(s.cacheCreationTokens()).isEqualTo(25_000);
        assertThat(s.cacheReadTokens()).isEqualTo(45_000);
        assertThat(s.toolUseTokens()).isEqualTo(1);
    }

    @Test
    @DisplayName("plus treats null as the zero vector")
    void plusNullIsIdentity() {
        TokenUsage a = new TokenUsage(1, 2, 3, 4, 5, 6);
        assertThat(a.plus(null)).isEqualTo(a);
    }

    @Test
    @DisplayName("sum over many vectors aggregates by type; cache_read dominates")
    void sumAggregatesByType() {
        // Three turns re-reading a growing cached prefix — cache_read is billed each turn.
        TokenUsage sum = TokenUsage.sum(List.of(
                new TokenUsage(2, 100, 0, 20_000, 10_000, 0),
                new TokenUsage(2, 150, 0, 5_000, 16_000, 0),
                new TokenUsage(2, 50, 0, 0, 22_000, 0)));
        assertThat(sum.inputTokens()).isEqualTo(6);
        assertThat(sum.outputTokens()).isEqualTo(300);
        assertThat(sum.cacheCreationTokens()).isEqualTo(25_000);
        assertThat(sum.cacheReadTokens()).isEqualTo(48_000);
    }

    @Test
    @DisplayName("sum of empty / null is the zero vector")
    void sumEmptyIsZero() {
        assertThat(TokenUsage.sum(List.of())).isEqualTo(new TokenUsage(0, 0, 0, 0, 0, 0));
        assertThat(TokenUsage.sum(null)).isEqualTo(new TokenUsage(0, 0, 0, 0, 0, 0));
    }
}
