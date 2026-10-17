package io.github.markpollack.journal.raw;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("level-0 raw files: stream, session copy, manifest")
class RawArtifactsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode manifest(Path raw) throws IOException {
        return JSON.readTree(raw.resolve(RawArtifacts.MANIFEST_FILE).toFile());
    }

    private static JsonNode artifact(JsonNode manifest, String path) {
        for (JsonNode a : manifest.get("artifacts")) {
            if (path.equals(a.path("path").asText(null))) {
                return a;
            }
        }
        throw new AssertionError("no artifact " + path + " in " + manifest);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    @DisplayName("a zip name that could escape the raw directory is refused")
    void unsafeNamesRefused(@TempDir Path dir) throws Exception {
        try (RawArtifacts artifacts = RawArtifacts.open(dir.resolve("raw"), "claude-code", null)) {
            assertThatThrownBy(() -> artifacts.zipSessionFiles("../x.zip", null, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static String zipText(Path zip, String entry) throws IOException {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            var e = z.getEntry(entry);
            assertThat(e).as("entry " + entry).isNotNull();
            return new String(z.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("session files go into one zip that mirrors the vendor layout, with every entry's size, checksum and source in the manifest")
    void sessionArchive(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("vendor").resolve("sess-1");
        Files.createDirectories(src.resolve("subagents"));
        Files.createDirectories(src.resolve("tool-results"));
        Files.writeString(src.resolve("subagents/agent-a.jsonl"), "{\"x\":1}\n");
        String big = "a very long result \u00e9\n".repeat(1000);
        Files.writeString(src.resolve("tool-results/toolu_1.txt"), big);
        Path transcript = dir.resolve("vendor").resolve("sess-1.jsonl");
        byte[] bin = {0, 1, 2, (byte) 0xff, 10, 13};
        Files.write(transcript, bin);
        Path outside = dir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Files.createSymbolicLink(src.resolve("link.txt"), outside);

        Path raw = dir.resolve("raw");
        boolean ok;
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "claude-code", "2.1.299")) {
            ok = artifacts.zipSessionFiles("sess-1.zip", "claude-session", Map.of("sessionId", "sess-1"), List.of(
                    new RawArtifacts.ZipSource(transcript, "sess-1.jsonl"),
                    new RawArtifacts.ZipSource(src, "sess-1"),
                    new RawArtifacts.ZipSource(dir.resolve("vendor/missing"), "missing")));
        }
        assertThat(ok).as("a skipped source means not everything was archived").isFalse();

        Path zip = raw.resolve("session/sess-1.zip");
        assertThat(zipText(zip, "sess-1/tool-results/toolu_1.txt")).isEqualTo(big);
        assertThat(zipText(zip, "sess-1/subagents/agent-a.jsonl")).isEqualTo("{\"x\":1}\n");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip.toFile())) {
            assertThat(z.getInputStream(z.getEntry("sess-1.jsonl")).readAllBytes()).isEqualTo(bin);
        }

        JsonNode a = artifact(manifest(raw), "session/sess-1.zip");
        assertThat(a.get("status").asText()).isEqualTo("complete");
        assertThat(a.get("sha256").asText()).isEqualTo(sha(Files.readAllBytes(zip)));
        assertThat(a.get("bytes").asLong()).isEqualTo(Files.size(zip));
        assertThat(a.get("vendorRefs").get("sessionId").asText()).isEqualTo("sess-1");
        JsonNode entries = a.get("entries");
        assertThat(entries).hasSize(3); // the transcript and two files under the directory; the symbolic link is skipped
        for (JsonNode e : entries) {
            if (e.get("name").asText().equals("sess-1/tool-results/toolu_1.txt")) {
                assertThat(e.get("sha256").asText()).isEqualTo(sha(big.getBytes(StandardCharsets.UTF_8)));
                assertThat(e.get("bytes").asLong()).isEqualTo(big.getBytes(StandardCharsets.UTF_8).length);
            }
        }
        java.util.Set<String> reasons = new java.util.HashSet<>();
        for (JsonNode k : a.get("skipped")) {
            reasons.add(k.get("name").asText() + ":" + k.get("reason").asText());
        }
        assertThat(reasons).containsExactlyInAnyOrder("sess-1/link.txt:symbolic link not followed", "missing:not found");
    }

    @Test
    @DisplayName("an archive with nothing to archive is recorded as failed, and the same name cannot be used twice")
    void emptyAndDuplicateArchives(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "junie", null)) {
            assertThat(artifacts.zipSessionFiles("a.zip", null, null, List.of(new RawArtifacts.ZipSource(dir.resolve("nope"), "nope")))).isFalse();
            assertThat(artifacts.zipSessionFiles("a.zip", null, null, List.of())).isFalse();
            assertThatThrownBy(() -> artifacts.zipSessionFiles("notzip.txt", null, null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        JsonNode m = manifest(raw);
        int failed = 0;
        for (JsonNode a : m.get("artifacts")) {
            if ("failed".equals(a.get("status").asText())) {
                failed++;
            }
        }
        assertThat(failed).isEqualTo(2);
        assertThat(m.toString()).contains("nothing to archive").contains("already stored");
    }

}
