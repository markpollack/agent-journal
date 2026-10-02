package io.github.markpollack.journal;

import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that what a run records reaches its storage, not only its in-memory state.
 */
class RunPersistenceTest {

    private InMemoryStorage storage;

    @BeforeEach
    void setUp() {
        Journal.reset();
        storage = new InMemoryStorage();
        Journal.configure(storage);
    }

    @AfterEach
    void tearDown() {
        Journal.reset();
    }

    private RunData stored(Run run) {
        return storage.loadRun(run.experiment().id(), run.id()).orElseThrow();
    }

    @Test
    void failWithAnExceptionThatHasNoMessageSavesTheRunAsFailed() {
        Run run = Journal.run("exp").start();

        run.fail(new RuntimeException());

        RunData saved = stored(run);
        assertThat(saved.status()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.summary().isSuccess()).isFalse();
        assertThat(saved.summary().values()).doesNotContainKey("error");
        assertThat(saved.summary().get("errorType", String.class)).isEqualTo("java.lang.RuntimeException");
        assertThat(saved.endTime()).isNotNull();
    }
}
