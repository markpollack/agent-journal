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
        Path zip = raw.resolve("session/" + id + ".zip");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            assertThat(z.getInputStream(z.getEntry("conversations/" + id + ".db")).readAllBytes()).containsExactly('S', 'Q', 'L', 0, 1, 2);
            assertThat(z.getInputStream(z.getEntry("conversations/" + id + ".db-wal")).readAllBytes()).containsExactly(9, 8, 7);
        }
        assertThat(zipText(zip, "brain/" + id + "/task.md")).isEqualTo("notes");
        assertThat(zipText(zip, "annotations/" + id + ".pbtxt")).isEqualTo("a: 1");
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

    private static String zipText(Path zip, String entry) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            var e = z.getEntry(entry);
            assertThat(e).as("entry " + entry).isNotNull();
            return new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

}
