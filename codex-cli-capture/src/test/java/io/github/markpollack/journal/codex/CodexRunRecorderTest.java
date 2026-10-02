package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.Journal;
import io.github.markpollack.journal.Run;
import io.github.markpollack.journal.event.CustomEvent;
import io.github.markpollack.journal.event.JournalEvent;
import io.github.markpollack.journal.storage.InMemoryStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
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
                .satisfies(e -> assertThat(((CustomEvent) e).attributes()).containsEntry("text", "the prompt"));
    }
}
