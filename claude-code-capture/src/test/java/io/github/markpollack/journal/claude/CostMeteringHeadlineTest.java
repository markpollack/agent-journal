package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.TokenUsage;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CM.2: the recorded headline {@link LLMCallEvent#tokenUsage()} is the cost-bearing aggregate (Σ
 * per-turn by type, incl. cache), not the final snapshot — and it is regenerable offline from the
 * persisted {@code META_TURNS} via {@link JournalSteps#aggregateUsageFromEvents}.
 */
@DisplayName("CM cost-metering: headline aggregate")
class CostMeteringHeadlineTest {

    private static final class Rec extends BaseRunRecorder {
        Rec(Run run) {
            this.currentRun = run;
        }
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    /** Two turns re-reading a growing cached prefix; snapshot (last ResultMessage) deliberately under-counts. */
    private static PhaseCapture capture() {
        List<TurnUsage> turns = List.of(
                new TurnUsage("msg_1", "claude-opus-4-8", 2, 200, 20_000, 10_000, List.of("toolu_1")),
                new TurnUsage("msg_2", "claude-opus-4-8", 2, 100, 0, 18_000, List.of()));
        // Snapshot scalars (final ResultMessage) ≪ the Σ-per-turn truth — the bug.
        return new PhaseCapture("explore", "p", 2, 100, 64, 0, 18_000, 1000L, 900L, 0.42,
                "sess", 2, false, "done", List.of(),
                List.of(new ToolUseRecord("toolu_1", "Bash", java.util.Map.of("command", "ls"))), "done",
                List.of(), turns, List.of());
    }

    @Test
    @DisplayName("headline tokenUsage equals aggregateUsage (incl. cache), not the snapshot")
    void headlineIsAggregateNotSnapshot() {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        PhaseCapture phase = capture();

        String runId;
        try (Run run = Journal.run("exp").start()) {
            runId = run.id();
            new Rec(run).recordPhase(phase);
        }

        LLMCallEvent llm = storage.loadEvents("exp", runId).stream()
                .filter(LLMCallEvent.class::isInstance).map(LLMCallEvent.class::cast)
                .findFirst().orElseThrow();

        assertThat(llm.tokenUsage()).isEqualTo(phase.aggregateUsage());
        // The fix: cache is now carried, output is the Σ not the snapshot, and it differs from snapshot.
        assertThat(llm.tokenUsage().outputTokens()).isEqualTo(300);
        assertThat(llm.tokenUsage().cacheCreationTokens()).isEqualTo(20_000);
        assertThat(llm.tokenUsage().cacheReadTokens()).isEqualTo(28_000);
        assertThat(llm.tokenUsage()).isNotEqualTo(phase.snapshotUsage());
    }

    @Test
    @DisplayName("aggregateUsageFromEvents(persisted) equals aggregateUsage(capture)")
    void offlineClosureEqualsCapture() {
        InMemoryStorage storage = new InMemoryStorage();
        Journal.configure(storage);
        PhaseCapture phase = capture();

        String runId;
        try (Run run = Journal.run("exp").start()) {
            runId = run.id();
            new Rec(run).recordPhase(phase);
        }

        List<JournalEvent> events = storage.loadEvents("exp", runId);
        TokenUsage offline = JournalSteps.aggregateUsageFromEvents(events);
        assertThat(offline).isEqualTo(phase.aggregateUsage());
    }
}
