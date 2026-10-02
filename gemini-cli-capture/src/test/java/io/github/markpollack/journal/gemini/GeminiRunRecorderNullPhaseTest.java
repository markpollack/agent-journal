package io.github.markpollack.journal.gemini;

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
 * Tests for {@link GeminiRunRecorder}.
 */
class GeminiRunRecorderNullPhaseTest {

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
        GeminiPhaseCapture phase = new GeminiPhaseCapture(null, "the prompt", "gemini-2.5-pro", 10, 5, 15, 100L,
                0.01, false, "SUCCESS", "done");

        new GeminiRunRecorder(run).recordPhase(phase);

        List<JournalEvent> events = storage.loadEvents("exp", run.id());
        assertThat(events).filteredOn(e -> e instanceof CustomEvent c && c.type().equals("prompt"))
                .singleElement()
                .satisfies(e -> assertThat(((CustomEvent) e).attributes()).containsEntry("text", "the prompt"));
    }
}
