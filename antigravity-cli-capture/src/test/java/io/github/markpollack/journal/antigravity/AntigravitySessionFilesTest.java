package io.github.markpollack.journal.antigravity;

import io.github.markpollack.journal.raw.RawArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AntigravitySessionFilesTest {

    @Test
    void copiesTheDatabaseItsWriteAheadLogTheWorkingDirectoryAndTheAnnotations(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        String id = "3b5ce9fe-8208-4d22-a067-7f0a35e7201f";
        Files.createDirectories(data.resolve("conversations"));
        Files.createDirectories(data.resolve("brain").resolve(id));
        Files.createDirectories(data.resolve("annotations"));
        Files.write(data.resolve("conversations/" + id + ".db"), new byte[] {'S', 'Q', 'L', 0, 1, 2});
        Files.write(data.resolve("conversations/" + id + ".db-wal"), new byte[] {9, 8, 7});
        Files.writeString(data.resolve("brain/" + id + "/task.md"), "notes");
        Files.writeString(data.resolve("annotations/" + id + ".pbtxt"), "a: 1");
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "antigravity", "1.2.6")) {
            assertThat(AntigravitySessionFiles.archive(artifacts, id, data)).isTrue();
        }
        assertThat(Files.readAllBytes(raw.resolve("session/" + id + ".db"))).containsExactly('S', 'Q', 'L', 0, 1, 2);
        assertThat(Files.readAllBytes(raw.resolve("session/" + id + ".db-wal"))).containsExactly(9, 8, 7);
        assertThat(Files.readString(raw.resolve("session/brain-" + id + "/task.md"))).isEqualTo("notes");
        assertThat(Files.readString(raw.resolve("session/" + id + ".pbtxt"))).isEqualTo("a: 1");
    }

    @Test
    void noDatabaseMeansNothingIsCopiedAndTheManifestSaysSo(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "antigravity", null)) {
            assertThat(AntigravitySessionFiles.archive(artifacts, "abc", dir.resolve("data"))).isFalse();
            assertThat(AntigravitySessionFiles.archive(artifacts, null, dir.resolve("data"))).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("no database at").contains("no usable conversation id");
    }

}
