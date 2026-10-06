package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.event.LLMCallEvent;
import io.github.markpollack.journal.event.ToolCallEvent;
import io.github.markpollack.journal.event.ToolKind;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CodexRunRecorder}.
 */
class CodexRunRecorderTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        storage = new InMemoryStorage();
        Journal.configure(storage);
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    @Test
    void phaseWithoutANameButWithAPromptIsRecorded() {
        Run run = Journal.run("exp").start();
        CodexPhaseCapture phase = new CodexPhaseCapture(null, "the prompt", "gpt-5", "0.148.0", "s1", 10, 5, 0,
                0, 0, 100L, false, "done", List.of());

        new CodexRunRecorder(run).recordPhase(phase);

        List<JournalEvent> events = storage.loadEvents("exp", run.id());
        assertThat(events).filteredOn(e -> e instanceof CustomEvent c && c.type().equals("prompt"))
                .singleElement()
                .satisfies(e -> assertThat(((CustomEvent) e).attributes()).containsEntry("text", "the prompt")
                        .doesNotContainKey("phase"));
    }

    @Test
    void toolCallDurationIsRecordedAsNotMeasured() {
        Run run = Journal.run("exp").start();
        CodexPhaseCapture phase = new CodexPhaseCapture("execute", null, "gpt-5", "0.148.0", "s1", 10, 5, 0,
                0, 0, 100L, false, "done",
                List.of(new CodexToolUseRecord("call_1", ToolKind.EXECUTE, "exec", Map.of("command", "ls"), "out",
                        false, null)));

        new CodexRunRecorder(run).recordPhase(phase);

        assertThat(storage.loadEvents("exp", run.id())).filteredOn(ToolCallEvent.class::isInstance)
                .singleElement()
                .satisfies(e -> assertThat(((ToolCallEvent) e).durationMs()).isEqualTo(-1));
    }

    @Test
    void llmCallInputTokensExcludeCachedInput() {
        Run run = Journal.run("exp").start();
        CodexPhaseCapture phase = new CodexPhaseCapture("execute", null, "gpt-5", "0.148.0", "s1", 1_000, 50, 20,
                0, 800, 100L, false, "done", List.of());

        new CodexRunRecorder(run).recordPhase(phase);

        LLMCallEvent call = storage.loadEvents("exp", run.id()).stream()
                .filter(LLMCallEvent.class::isInstance).map(LLMCallEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(call.tokenUsage().inputTokens()).isEqualTo(200);
        assertThat(call.tokenUsage().cacheReadTokens()).isEqualTo(800);
        assertThat(call.totalTokens()).isEqualTo(250);
    }
}
