package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.raw.RawArtifacts;
import io.github.markpollack.journal.raw.RawStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodexExecStreamCaptureTest {

    @Test
    void aRealExecJsonStreamIsKeptLineForLineInTheRawStream(@TempDir Path dir) throws Exception {
        Path fixture = Path.of(CodexExecStreamCaptureTest.class.getResource("/fixtures/real-streams/exec-json-ls.jsonl").toURI());
        Path rawDir = dir.resolve("raw");
        long copied;
        try (RawArtifacts raw = RawArtifacts.open(rawDir, "codex", "0.160.1");
                BufferedReader in = Files.newBufferedReader(fixture)) {
            RawStream stream = raw.openStream("run");
            copied = stream.copyFrom(in);
        }
        List<String> expected = Files.readAllLines(fixture);
        assertThat(copied).isEqualTo(expected.size()).isEqualTo(6);
        assertThat(Files.readAllLines(rawDir.resolve("run.stream.jsonl"))).isEqualTo(expected);
        assertThat(Files.readString(rawDir.resolve("manifest.json"))).contains("\"lines\" : 6").contains("\"status\" : \"complete\"");
    }

}
