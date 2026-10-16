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
        Path zip = raw.resolve("session/session-261014-111111-abcd.zip");
        assertThat(zipText(zip, "session-261014-111111-abcd/events.jsonl")).isEqualTo("{\"kind\":\"TaskStartedEvent\"}\n");
        assertThat(zipText(zip, "session-261014-111111-abcd/task-1/x.json")).isEqualTo("{}");
    }

    @Test
    void aMissingSessionDirectoryIsRecorded(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "junie", null)) {
            assertThat(JunieSessionFiles.archive(artifacts, dir.resolve("session-none"))).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("not found");
    }

    private static String zipText(Path zip, String entry) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            var e = z.getEntry(entry);
            assertThat(e).as("entry " + entry).isNotNull();
            return new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

}
