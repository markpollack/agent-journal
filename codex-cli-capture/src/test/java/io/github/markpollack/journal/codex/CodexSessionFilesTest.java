package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.raw.RawArtifacts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexSessionFilesTest {

    @Test
    void copiesRootAndChildRolloutsUnderTheirOwnNamesAndRecordsThePathsItCouldNotUse(@TempDir Path dir) throws Exception {
        Path root = dir.resolve("rollout-2026-10-14T10-00-00-aaa.jsonl");
        Path child = dir.resolve("rollout-2026-10-14T10-00-05-bbb.jsonl");
        Files.writeString(root, "{\"type\":\"session_meta\"}\n");
        Files.writeString(child, "{\"type\":\"session_meta\",\"child\":true}\n");
        CodexRollouts rollouts = new CodexRollouts(List.of(
                new CodexRollout("aaa", null, root.toString(), List.of()),
                new CodexRollout("bbb", "aaa", child.toString(), List.of()),
                new CodexRollout("ccc", "aaa", null, List.of())));
        Path raw = dir.resolve("raw");
        try (RawArtifacts artifacts = RawArtifacts.open(raw, "codex", "0.147.0")) {
            assertThat(CodexSessionFiles.archive(artifacts, rollouts)).isFalse();
        }
        assertThat(Files.readString(raw.resolve("session/" + root.getFileName()))).isEqualTo("{\"type\":\"session_meta\"}\n");
        assertThat(Files.readString(raw.resolve("session/" + child.getFileName()))).contains("child");
        String manifest = Files.readString(raw.resolve("manifest.json"));
        assertThat(manifest).contains("\"parentThreadId\" : \"aaa\"").contains("thread ccc is not known");
    }

}
