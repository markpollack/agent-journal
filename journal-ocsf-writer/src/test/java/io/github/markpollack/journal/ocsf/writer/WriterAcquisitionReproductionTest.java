package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.manifest;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction of the review's writer case (FR-02): six whole records plus 40 bytes of the seventh, EOF, then
 * {@code complete()}. The writer finalizes storage ({@code COMPLETED}) and declares acquisition incomplete; the
 * UNCHANGED oracle reports every check without a blocking outcome and the acceptance exit is 0. The assertions
 * record that false acceptance; the repair commit turns them into the required rejection.
 */
class WriterAcquisitionReproductionTest {

    @Test
    void fr02EofInsideARecordIsFinalizedHonestlyButStillAcceptedAsComplete(@TempDir Path work) {
        Path core = work.resolve("core");
        Path stream = nativeOf(PAIR, "stream.jsonl");
        byte[] all = WriterTestSupport.bytes(stream);
        int cut = Replay.prefixBytes(stream, 6).length + 40;
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(PAIR), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStream()), all, 11, options().clock(), cut, true);
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        Jv m = manifest(core);
        assertThat(m.path("package", "state").text()).isEqualTo("COMPLETED");
        assertThat(m.path("recording", "acquisition", "status").text()).isEqualTo("incomplete");
        Path nat = work.resolve("native");
        try {
            Files.createDirectories(nat);
            Files.write(nat.resolve("stream.jsonl"), Arrays.copyOf(all, cut));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).isEmpty();
        assertThat(report.withOutcome(Check.Outcome.UNRESOLVED)).isEmpty();
        assertThat(report.withOutcome(Check.Outcome.UNSUPPORTED)).isEmpty();
        assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).as("FALSE ACCEPTANCE (FR-02, writer case)").isEqualTo(OcsfReaderTool.EXIT_OK);
    }
}
