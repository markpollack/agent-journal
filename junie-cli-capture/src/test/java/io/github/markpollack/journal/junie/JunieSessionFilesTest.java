package io.github.markpollack.journal.junie;

import io.github.markpollack.journal.raw.RawArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JunieSessionFilesTest {

    @Test
    void copiesTheWholeSessionDirectory(@TempDir Path dir) throws Exception {
        Path session = dir.resolve("sessions").resolve("session-261014-111111-abcd");
        Files.createDirectories(session.resolve("task-1"));
        Files.writeString(session.resolve("events.jsonl"), "{\"kind\":\"TaskStartedEvent\"}\n");
        Files.writeString(session.resolve("transcript.md"), "# transcript");
        Files.writeString(session.resolve("task-1/x.json"), "{}");
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "junie", "26.10.12")) {
            assertThat(JunieSessionFiles.archive(artifacts, session)).isTrue();
        }
        assertThat(Files.readString(raw.resolve("session/session-261014-111111-abcd/events.jsonl")))
                .isEqualTo("{\"kind\":\"TaskStartedEvent\"}\n");
        assertThat(Files.exists(raw.resolve("session/session-261014-111111-abcd/task-1/x.json"))).isTrue();
    }

    @Test
    void aMissingSessionDirectoryIsRecorded(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "junie", null)) {
            assertThat(JunieSessionFiles.archive(artifacts, dir.resolve("session-none"))).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("source directory not found");
    }

}
