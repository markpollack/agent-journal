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
    @DisplayName("a stream keeps every line byte for byte, including malformed JSON and non-ASCII, and numbers them from 1")
    void streamIsVerbatim(@TempDir Path dir) throws Exception {
        List<String> lines = List.of("{\"type\":\"system\",\"subtype\":\"init\"}", "{\"type\":\"assistant\",\"message\":{\"text\":\"héllo 😀\"}",
                "not json at all", "", "{\"a\":1}   ");
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "claude-code", "2.1.299")) {
            RawStream stream = artifacts.openStream("invoke");
            for (int i = 0; i < lines.size(); i++) {
                assertThat(stream.append(lines.get(i))).isEqualTo(i + 1L);
            }
        }
        byte[] written = Files.readAllBytes(raw.resolve("invoke.stream.jsonl"));
        String expected = String.join("\n", lines) + "\n";
        assertThat(written).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));

        JsonNode entry = artifact(manifest(raw), "invoke.stream.jsonl");
        assertThat(entry.get("status").asText()).isEqualTo("complete");
        assertThat(entry.get("lines").asLong()).isEqualTo(5);
        assertThat(entry.get("bytes").asLong()).isEqualTo(written.length);
        assertThat(entry.get("sha256").asText()).isEqualTo(sha(written));
    }

    @Test
    @DisplayName("a run killed mid-stream leaves every appended line and a manifest that says the stream was open")
    void crashLeavesReplayablePrefix(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        RawArtifacts artifacts = RawArtifacts.open(raw, "codex", null);
        RawStream stream = artifacts.openStream("phase-1");
        stream.append("{\"n\":1}");
        stream.append("{\"n\":2}");
        // no close(): the process "dies" here

        assertThat(Files.readAllLines(raw.resolve("phase-1.stream.jsonl"))).containsExactly("{\"n\":1}", "{\"n\":2}");
        JsonNode m = manifest(raw);
        assertThat(m.get("closed").asBoolean()).isFalse();
        assertThat(artifact(m, "phase-1.stream.jsonl").get("status").asText()).isEqualTo("open");
        assertThat(m.get("vendorVersion").isNull()).isTrue();
        artifacts.close();
    }

    @Test
    @DisplayName("messages the adapter could not keep verbatim are counted by type in the manifest")
    void unavailableMessagesAreCounted(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "claude-code", "2.1.299")) {
            RawStream stream = artifacts.openStream("invoke");
            stream.append("{}");
            stream.noteUnavailable("rate_limit_event");
            stream.noteUnavailable("rate_limit_event");
            stream.noteUnavailable("control_request");
        }
        JsonNode unavailable = artifact(manifest(raw), "invoke.stream.jsonl").get("unavailable");
        assertThat(unavailable.get("rate_limit_event").asLong()).isEqualTo(2);
        assertThat(unavailable.get("control_request").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("a session file is copied byte for byte with its checksum, size, source path and modification time")
    void sessionFileCopy(@TempDir Path dir) throws Exception {
        Path source = dir.resolve("vendor").resolve("abc.jsonl");
        Files.createDirectories(source.getParent());
        byte[] content = "{\"a\":1}\n{\"b\":\"é\"}\n\u0000binary-ish".getBytes(StandardCharsets.UTF_8);
        Files.write(source, content);
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "claude-code", "2.1.299")) {
            boolean ok = artifacts.copySessionFile(source, "abc.jsonl", "transcript", Map.of("sessionId", "abc"));
            assertThat(ok).isTrue();
        }
        assertThat(Files.readAllBytes(raw.resolve("session/abc.jsonl"))).isEqualTo(content);
        JsonNode entry = artifact(manifest(raw), "session/abc.jsonl");
        assertThat(entry.get("status").asText()).isEqualTo("complete");
        assertThat(entry.get("sha256").asText()).isEqualTo(sha(content));
        assertThat(entry.get("bytes").asLong()).isEqualTo(content.length);
        assertThat(entry.get("sourcePath").asText()).isEqualTo(source.toAbsolutePath().toString());
        assertThat(entry.get("sourceModified").asText()).isNotBlank();
        assertThat(entry.get("role").asText()).isEqualTo("transcript");
        assertThat(entry.get("vendorRefs").get("sessionId").asText()).isEqualTo("abc");
    }

    @Test
    @DisplayName("a session file that cannot be copied is recorded as failed with a reason, and nothing is thrown")
    void failedCopyIsExplained(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "codex", "0.147.0")) {
            assertThat(artifacts.copySessionFile(dir.resolve("missing.jsonl"), "rollout.jsonl", "rollout", null)).isFalse();
            artifacts.recordSessionFileNotCaptured("subagent-transcript", "two files matched the session id");
        }
        JsonNode m = manifest(raw);
        JsonNode missing = artifact(m, "session/rollout.jsonl");
        assertThat(missing.get("status").asText()).isEqualTo("failed");
        assertThat(missing.get("reason").asText()).contains("not found");
        assertThat(Files.exists(raw.resolve("session/rollout.jsonl"))).isFalse();
        boolean found = false;
        for (JsonNode a : m.get("artifacts")) {
            if ("subagent-transcript".equals(a.path("role").asText())) {
                found = true;
                assertThat(a.get("status").asText()).isEqualTo("failed");
                assertThat(a.get("reason").asText()).contains("two files matched");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("names that could escape the raw directory are refused")
    void unsafeNamesRefused(@TempDir Path dir) throws Exception {
        try (RawArtifacts artifacts = RawArtifacts.open(dir.resolve("raw"), "claude-code", null)) {
            assertThatThrownBy(() -> artifacts.openStream("../evil")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> artifacts.openStream("a/b")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> artifacts.openStream("..")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> artifacts.copySessionFile(dir, "../x", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            artifacts.openStream("ok");
            assertThatThrownBy(() -> artifacts.openStream("ok")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("a line with an embedded line break is kept whole and counted in the manifest")
    void embeddedLineBreaksAreCounted(@TempDir Path dir) throws Exception {
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "junie", null)) {
            RawStream s = artifacts.openStream("p");
            s.append("{\"a\":\n1}");
        }
        assertThat(Files.readString(raw.resolve("p.stream.jsonl"))).isEqualTo("{\"a\":\n1}\n");
        assertThat(artifact(manifest(raw), "p.stream.jsonl").get("linesWithEmbeddedLineBreaks").asLong()).isEqualTo(1);
    }

}
