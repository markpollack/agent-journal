package io.github.markpollack.journal;

import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.RunData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void setSummaryWritesTheValueToStorageWhileTheRunIsOpen() {
        Run run = Journal.run("exp").start();

        run.setSummary("filesChanged", 5);

        RunData saved = stored(run);
        assertThat(saved.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(saved.summary().values()).containsEntry("filesChanged", 5);
    }

    @Test
    void finishWithAStatusThatIsNotTerminalIsRejectedAndLeavesTheRunOpen() {
        Run run = Journal.run("exp").start();

        assertThatThrownBy(() -> run.finish(RunStatus.RUNNING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run.finish(RunStatus.INIT)).isInstanceOf(IllegalArgumentException.class);

        RunData saved = stored(run);
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(saved.endTime()).isNull();
    }

    @Test
    void textArtifactIsSavedAsUtf8WhateverThePlatformCharset() {
        Run run = Journal.run("exp").start();
        String text = "caf\u00e9 \u2713 \u65e5\u672c";

        run.logArtifact("notes.txt", text);

        byte[] saved = storage.loadArtifact(run.experiment().id(), run.id(), "notes.txt").orElseThrow();
        assertThat(saved).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void finishingAsFailedOrCrashedRecordsSuccessFalse() {
        Run failed = Journal.run("exp").start();
        Run crashed = Journal.run("exp").start();

        failed.finish(RunStatus.FAILED);
        crashed.finish(RunStatus.CRASHED);

        assertThat(stored(failed).summary().values()).containsEntry("success", false);
        assertThat(stored(crashed).summary().values()).containsEntry("success", false);
    }

    @Test
    void finishingAsFailedKeepsASuccessValueTheCallerSet() {
        Run run = Journal.run("exp").start();
        run.setSummary("success", true);

        run.finish(RunStatus.FAILED);

        assertThat(stored(run).summary().values()).containsEntry("success", true);
    }

    @Test
    void aRunStartedAfterConfiguringNewStorageWritesTheExperimentToThatStorage() {
        Journal.run("exp").start().close();
        InMemoryStorage second = new InMemoryStorage();
        Journal.configure(second);

        Journal.run("exp").start().close();

        assertThat(second.loadExperiment("exp")).isPresent();
    }

    @Test
    void experimentLookupAfterConfiguringNewStorageWritesTheExperimentToThatStorage() {
        Journal.experiment("exp");
        InMemoryStorage second = new InMemoryStorage();
        Journal.configure(second);

        Journal.experiment("exp");

        assertThat(second.loadExperiment("exp")).isPresent();
    }
}
