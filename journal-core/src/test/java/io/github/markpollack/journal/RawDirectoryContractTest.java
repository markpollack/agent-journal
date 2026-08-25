package io.github.markpollack.journal;

import io.github.markpollack.journal.storage.InMemoryStorage;
import io.github.markpollack.journal.storage.JournalStorage;
import io.github.markpollack.journal.storage.JsonFileStorage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code raw/} run-directory contract (J5, 1.9.0).
 *
 * <p>
 * <strong>What this is and, just as importantly, what it is not.</strong> The journal reserves and
 * locates a place for verbatim provider artifacts — the Claude Code {@code .jsonl} session log and
 * its equivalents — inside the run directory, keyed by run id, so raw stays findable from the run
 * record. It deliberately does <em>not</em> copy anything there: the owner ruled that copying the
 * provider session file belongs to {@code agent-experiment}, which knows what it launched and when.
 * These tests therefore assert the <em>contract</em> (a resolvable, run-scoped location that is not
 * conjured into existence) and explicitly assert the absence of a copier, so a later reader does
 * not mistake the missing half for an oversight.
 *
 * <p>
 * The motivation is the one-shot nature of capture: nearly every gap found in the 2026-08-24
 * measurement audit was plausibly already present in the raw provider log and was discarded at
 * parse time. Raw converts "we cannot answer that" into "re-derive it".
 */
@DisplayName("run directory contract: raw/")
class RawDirectoryContractTest {

    @Test
    @DisplayName("a file-backed run resolves a raw/ directory scoped to that run")
    void fileStorageResolvesRunScopedRawDirectory(@TempDir Path tempDir) {
        JsonFileStorage storage = new JsonFileStorage(tempDir);

        Optional<Path> raw = storage.rawDirectory("exp-1", "run-1");

        assertThat(raw).isPresent();
        assertThat(raw.get()).isEqualTo(
                tempDir.resolve("experiments").resolve("exp-1").resolve("runs").resolve("run-1").resolve("raw"));
        // Keyed to the run, so two runs never share an archive.
        assertThat(storage.rawDirectory("exp-1", "run-2")).get().isNotEqualTo(raw.get());
    }

    @Test
    @DisplayName("resolving raw/ does not create it — absent means nothing was archived")
    void resolvingRawDoesNotCreateIt(@TempDir Path tempDir) {
        JsonFileStorage storage = new JsonFileStorage(tempDir);

        Path raw = storage.rawDirectory("exp-1", "run-1").orElseThrow();

        // If resolving created the directory, an empty raw/ would be ambiguous: "archival ran and
        // found nothing" or "archival never ran". Leaving it absent keeps that distinction.
        assertThat(Files.exists(raw)).isFalse();
    }

    @Test
    @DisplayName("a storage with no filesystem reports no raw location rather than inventing one")
    void inMemoryStorageHasNoRawLocation() {
        JournalStorage storage = new InMemoryStorage();

        assertThat(storage.rawDirectory("exp-1", "run-1")).isEmpty();
    }

    @Test
    @DisplayName("the copier is explicitly not in this release")
    void journalDoesNotArchiveRawItself(@TempDir Path tempDir) {
        Journal.configure(new JsonFileStorage(tempDir));
        try {
            Run run = Journal.run("exp").start();
            run.close();

            Path raw = Journal.storage().rawDirectory(run.experiment().id(), run.id()).orElseThrow();

            // A full run lifecycle archives nothing. This assertion is the guard on the scope
            // boundary: if the journal ever starts writing here, that is a deliberate decision
            // that has to come with agent-experiment's copier being retired, not a silent drift.
            assertThat(Files.exists(raw)).isFalse();
        }
        finally {
            Journal.reset();
        }
    }
}
