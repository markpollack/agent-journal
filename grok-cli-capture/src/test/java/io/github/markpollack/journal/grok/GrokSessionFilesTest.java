package io.github.markpollack.journal.grok;

import io.github.markpollack.journal.raw.RawArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GrokSessionFilesTest {

    private static final String ID = "01a02b07-d35f-7ef0-9a34-f653b580541c";

    private static Path sessions(Path dir) throws Exception {
        Path folder = dir.resolve("sessions").resolve("%2Ftmp%2Fwork");
        Path session = folder.resolve(ID);
        Files.createDirectories(session);
        Files.writeString(session.resolve("chat_history.jsonl"), "{\"role\":\"user\"}\n");
        Files.writeString(session.resolve("updates.jsonl"), "{\"type\":\"text\"}\n");
        Files.writeString(session.resolve("summary.json"), "{\"id\":\"" + ID + "\"}");
        Files.writeString(folder.resolve("prompt_history.jsonl"), "prompts of every session in this folder\n");
        Files.createDirectories(dir.resolve("sessions").resolve("%2Ftmp%2Fother").resolve("01a0-another-session"));
        return dir.resolve("sessions");
    }

    @Test
    void archivesTheWholeSessionDirectoryAndNotThePerFolderPromptHistory(@TempDir Path dir) throws Exception {
        Path root = sessions(dir);
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "grok", "1.0.46")) {
            assertThat(GrokSessionFiles.archive(artifacts, ID, root)).isTrue();
        }

        Path zip = raw.resolve("session/" + ID + ".zip");
        assertThat(zipText(zip, ID + "/chat_history.jsonl")).isEqualTo("{\"role\":\"user\"}\n");
        assertThat(zipText(zip, ID + "/updates.jsonl")).isEqualTo("{\"type\":\"text\"}\n");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            assertThat(z.stream().map(java.util.zip.ZipEntry::getName)).noneMatch(n -> n.contains("prompt_history"));
        }
        // the manifest lists the zip with the SHA-256 of its bytes
        String manifest = Files.readString(raw.resolve("manifest.json"));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        assertThat(manifest).contains(sha).contains(ID).contains("complete");
    }

    @Test
    void noSessionIdIsRecordedAndNothingIsArchived(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "grok", null)) {
            assertThat(GrokSessionFiles.archive(artifacts, null, sessions(dir))).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("no session id");
        assertThat(raw.resolve("session")).doesNotExist();
    }

    @Test
    void anUnknownSessionIsRecordedWithTheReason(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "grok", null)) {
            assertThat(GrokSessionFiles.archive(artifacts, "01a0-not-there", sessions(dir))).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("no session directory named 01a0-not-there");
    }

    @Test
    void aSessionIdFoundInTwoFoldersIsAmbiguousAndNothingIsGuessed(@TempDir Path dir) throws Exception {
        Path root = sessions(dir);
        Files.createDirectories(root.resolve("%2Ftmp%2Fother").resolve(ID));
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "grok", null)) {
            assertThat(GrokSessionFiles.archive(artifacts, ID, root)).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("ambiguous: 2");
        assertThat(raw.resolve("session")).doesNotExist();
    }

    @Test
    void anUnsafeSessionIdIsRefused(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "grok", null)) {
            assertThat(GrokSessionFiles.archive(artifacts, "../../etc", sessions(dir))).isFalse();
        }
        assertThat(Files.readString(raw.resolve("manifest.json"))).contains("not usable as a file name");
    }

    private static String zipText(Path zip, String entry) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            var e = z.getEntry(entry);
            assertThat(e).as("entry " + entry).isNotNull();
            return new String(z.getInputStream(e).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
