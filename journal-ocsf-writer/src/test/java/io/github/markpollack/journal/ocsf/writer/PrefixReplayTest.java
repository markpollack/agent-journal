package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexRollout;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.notVerified;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prefix-by-prefix replay: after every record the writer flushes, and the independent oracle judges the open
 * package against exactly the native prefix fed so far (its expected populations are recomputed from that prefix,
 * never from the writer). Every prefix must be free of defects: no invented completion, no missing or extra
 * population, correct ownership, order and retention at each point.
 */
class PrefixReplayTest {

    @Test
    void everyPrefixOfTheCodexRolloutIsJudgedDefectFreeByTheOracle(@TempDir Path work) throws Exception {
        List<String> summary = prefixes(work, CODEX, "rollout.jsonl", codexRollout());
        assertThat(summary).as(String.join("\n", summary)).allMatch(s -> s.endsWith("OK"));
        assertThat(summary.size()).isEqualTo(39);
    }

    @Test
    void everyPrefixOfTheCodexExecStreamIsJudgedDefectFreeByTheOracle(@TempDir Path work) throws Exception {
        List<String> summary = prefixes(work, CODEX_STREAM, "stream.jsonl", codexStream());
        assertThat(summary).as(String.join("\n", summary)).allMatch(s -> s.endsWith("OK"));
        assertThat(summary.size()).isEqualTo(12);
    }

    @Test
    void everyPrefixOfTheClaudeStreamIsJudgedDefectFreeByTheOracle(@TempDir Path work) throws Exception {
        List<String> summary = prefixes(work, PAIR, "stream.jsonl", claudeStream());
        assertThat(summary).as(String.join("\n", summary)).allMatch(s -> s.endsWith("OK"));
        assertThat(summary.size()).isEqualTo(27);
    }

    private static List<String> prefixes(Path work, String fixture, String artifact, SourceDescriptor descriptor) throws Exception {
        Path core = work.resolve("core");
        Path source = nativeOf(fixture, artifact);
        List<LineFramer.Framed> lines = Replay.lines(WriterTestSupport.bytes(source));
        List<String> summary = new ArrayList<>();
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(fixture), SchemaPin.bundled(), options())) {
            SourceInput in = w.source(descriptor);
            for (int k = 1; k <= lines.size(); k++) {
                LineFramer.Framed f = lines.get(k - 1);
                in.record(f.raw(), f.ending(), options().clock().instant());
                w.flush();
                Path nat = work.resolve("native-" + k);
                Files.createDirectories(nat);
                Files.write(nat.resolve(artifact), Replay.prefixBytes(source, k));
                OracleReport report = verify(core, nat);
                List<Check> problems = new ArrayList<>(report.defects());
                problems.addAll(report.withOutcome(Check.Outcome.UNSUPPORTED).stream().filter(c -> !c.property().equals("reconstruction.bytes") && !c.property().equals("package.state")).toList());
                summary.add("prefix " + k + ": " + report.summary().get("NORMALIZATION") + (problems.isEmpty() ? " OK" : " PROBLEMS " + problems + " " + notVerified(report)));
            }
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
        }
        return summary;
    }
}
