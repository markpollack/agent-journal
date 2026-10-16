package io.github.markpollack.journal.claude;

import io.github.markpollack.journal.raw.RawArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeSessionFilesTest {

    private static Path session(Path root, String project, String sid) throws Exception {
        Path proj = root.resolve(project);
        Files.createDirectories(proj.resolve(sid).resolve("subagents"));
        Files.createDirectories(proj.resolve(sid).resolve("tool-results"));
        Files.writeString(proj.resolve(sid + ".jsonl"), "{\"type\":\"user\"}\n{\"type\":\"assistant\"}\n");
        Files.writeString(proj.resolve(sid).resolve("subagents/agent-1.jsonl"), "{\"sub\":1}\n");
        Files.writeString(proj.resolve(sid).resolve("subagents/agent-1.meta.json"), "{}");
        Files.writeString(proj.resolve(sid).resolve("tool-results/toolu_9.txt"), "big result");
        return proj;
    }

    @Test
    void copiesTheTranscriptAndTheSessionDirectoryKeepingTheLayout(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("projects");
        session(root, "-home-me-proj", "abc-123");
        session(root, "-home-me-other", "zzz-999");
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "claude-code", "2.1.299")) {
            assertThat(ClaudeSessionFiles.archive(artifacts, "abc-123", root)).isTrue();
        }
        assertThat(Files.readString(raw.resolve("session/abc-123.jsonl"))).isEqualTo("{\"type\":\"user\"}\n{\"type\":\"assistant\"}\n");
        assertThat(Files.readString(raw.resolve("session/abc-123/subagents/agent-1.jsonl"))).isEqualTo("{\"sub\":1}\n");
        assertThat(Files.readString(raw.resolve("session/abc-123/tool-results/toolu_9.txt"))).isEqualTo("big result");
        assertThat(Files.exists(raw.resolve("session/zzz-999.jsonl"))).isFalse();
    }

    @Test
    void anAmbiguousMissingOrAbsentSessionIdCopiesNothingAndSaysWhy(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("projects");
        session(root, "-a", "dup");
        session(root, "-b", "dup");
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "claude-code", null)) {
            assertThat(ClaudeSessionFiles.archive(artifacts, "dup", root)).isFalse();
            assertThat(ClaudeSessionFiles.archive(artifacts, "gone", root)).isFalse();
            assertThat(ClaudeSessionFiles.archive(artifacts, null, root)).isFalse();
            assertThat(ClaudeSessionFiles.archive(artifacts, "../escape", root)).isFalse();
        }
        String manifest = Files.readString(raw.resolve("manifest.json"));
        assertThat(manifest).contains("ambiguous: 2 transcripts").contains("no transcript named gone.jsonl")
                .contains("reported no session id").contains("not usable as a file name");
        assertThat(Files.exists(raw.resolve("session"))).isFalse();
    }

}
