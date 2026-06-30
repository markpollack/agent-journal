package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.event.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CM.1: {@link PhaseCapture#aggregateUsage()} is the cost-bearing per-type aggregate (Σ per-turn by
 * type, incl. cache), distinct from the final-snapshot/context-size view. Thinking is recorded but
 * never added to the priced sum (no per-turn thinking on the Claude wire).
 */
@DisplayName("PhaseCapture.aggregateUsage")
class PhaseCaptureAggregateUsageTest {

    /** Builds a capture with the given per-turn vectors and a snapshot that (as in the bug) under-counts. */
    private static PhaseCapture capture(List<TurnUsage> turns, int snapOut, int snapCacheCreate, int snapCacheRead,
            int thinking) {
        return new PhaseCapture("explore", "p", 0, snapOut, thinking, snapCacheCreate, snapCacheRead, 1000L, 900L,
                9.20, "sess", turns.size(), false, "done", List.of(), List.of(), "done", List.of(), turns, List.of());
    }

    @Test
    @DisplayName("sums per-turn usage by type incl. cache; equals hand-computed")
    void sumsPerTurnByType() {
        List<TurnUsage> turns = List.of(
                new TurnUsage("msg_1", "claude-opus-4-8", 2, 100, 20_000, 10_000, List.of("t1")),
                new TurnUsage("msg_2", "claude-opus-4-8", 2, 150, 5_000, 16_000, List.of("t2")),
                new TurnUsage("msg_3", "claude-opus-4-8", 2, 50, 0, 22_000, List.of()));
        // Snapshot deliberately ≪ the sum (the bug) — and the legacy headline zeroed cache entirely.
        PhaseCapture phase = capture(turns, /*snapOut*/ 50, /*snapCC*/ 0, /*snapCR*/ 22_000, /*thinking*/ 0);

        TokenUsage agg = phase.aggregateUsage();
        assertThat(agg.inputTokens()).isEqualTo(6);
        assertThat(agg.outputTokens()).isEqualTo(300);
        assertThat(agg.cacheCreationTokens()).isEqualTo(25_000);
        assertThat(agg.cacheReadTokens()).isEqualTo(48_000);

        // The aggregate strictly exceeds the snapshot on the cost-driving types.
        assertThat(agg.outputTokens()).isGreaterThan(phase.snapshotUsage().outputTokens());
        assertThat(agg.cacheReadTokens()).isGreaterThan(phase.snapshotUsage().cacheReadTokens());
    }

    @Test
    @DisplayName("records run-level thinking but never adds it to the priced input+output+cache sum")
    void recordsThinkingWithoutDoubleCounting() {
        List<TurnUsage> turns = List.of(
                new TurnUsage("msg_1", "claude-opus-4-8", 2, 100, 0, 10_000, List.of()));
        PhaseCapture phase = capture(turns, 100, 0, 10_000, /*thinking*/ 512);

        TokenUsage agg = phase.aggregateUsage();
        assertThat(agg.thinkingTokens()).isEqualTo(512); // recorded (available run-level signal)
        // Priced sum is input+output+cache; thinking is a subset of output, not summed on top.
        assertThat(agg.outputTokens()).isEqualTo(100);
        assertThat(agg.inputTokens()).isEqualTo(2);
        assertThat(agg.cacheReadTokens()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("falls back to the snapshot vector (incl. cache) when there are no turns")
    void fallsBackToSnapshotWhenNoTurns() {
        PhaseCapture phase = capture(List.of(), /*snapOut*/ 250, /*snapCC*/ 192, /*snapCR*/ 59_696, 0);
        TokenUsage agg = phase.aggregateUsage();
        // No per-turn truth → snapshot, but cache fields are still carried (unlike TokenUsage.of()).
        assertThat(agg.outputTokens()).isEqualTo(250);
        assertThat(agg.cacheCreationTokens()).isEqualTo(192);
        assertThat(agg.cacheReadTokens()).isEqualTo(59_696);
        assertThat(agg).isEqualTo(phase.snapshotUsage());
    }
}
