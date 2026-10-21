package io.github.markpollack.journal.claude.ocsf;

import io.github.markpollack.claude.agent.sdk.parsing.MessageParser;
import io.github.markpollack.claude.agent.sdk.parsing.ParsedMessage;
import io.github.markpollack.journal.claude.PhaseCapture;
import io.github.markpollack.journal.claude.SessionLogParser;
import io.github.markpollack.journal.ocsf.writer.OcsfRunWriter;
import io.github.markpollack.journal.ocsf.writer.RunDescriptor;
import io.github.markpollack.journal.ocsf.writer.SchemaPin;
import io.github.markpollack.journal.ocsf.writer.SourceDescriptor;
import io.github.markpollack.journal.ocsf.writer.SourceInput;
import io.github.markpollack.journal.ocsf.writer.WriterOptions;
import io.github.markpollack.journal.tck.ocsf.AnalysisViews;
import io.github.markpollack.journal.tck.ocsf.OcsfOracle;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capture-facing seam, shown on the real capture path with deterministic input: the SDK hands the capture an
 * {@code Iterator<ParsedMessage>} whose messages carry their wire line ({@code rawJson}); a tee on that iterator
 * hands each wire line to the incremental writer's {@link SourceInput} as the production parser consumes it. The
 * production parser is unchanged and still produces its {@link PhaseCapture}; the writer persists the OCSF package
 * beside it, readable before the iterator is exhausted. NON-PRODUCTION: nothing in the shipped recorder does this.
 */
class OcsfWriterSeamTest {

    private static final String FIXTURE = "/fixtures/real-streams/foreground-subagent-one-result/stream.jsonl";

    /** A tee: every message the parser pulls is handed to the writer first, with the time it was pulled. */
    static Iterator<ParsedMessage> tee(Iterator<ParsedMessage> source, SourceInput sink, Clock clock, Consumer<ParsedMessage> observed) {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                boolean more = source.hasNext();
                if (!more) {
                    sink.end(SourceInput.End.EOF, clock.instant());
                }
                return more;
            }

            @Override
            public ParsedMessage next() {
                ParsedMessage m = source.next();
                String raw = m instanceof ParsedMessage.RegularMessage r ? r.rawJson() : null;
                if (raw != null) {
                    sink.record(raw.getBytes(StandardCharsets.UTF_8), "LF", clock.instant());
                }
                observed.accept(m);
                return m;
            }
        };
    }

    @Test
    void theProductionParserAndTheIncrementalWriterConsumeTheSameSdkIterator(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(Path.of(OcsfWriterSeamTest.class.getResource(FIXTURE).toURI()));
        MessageParser parser = new MessageParser();
        List<ParsedMessage> messages = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                messages.add(ParsedMessage.RegularMessage.of(parser.parseMessage(line), line));
                delivered.add(line);
            }
            catch (RuntimeException unknownType) {
                // the released SDK skips lines it does not parse; the writer sees exactly what the SDK delivers (stated as the surface)
            }
        }
        Path core = work.resolve("core");
        RunDescriptor run = new RunDescriptor("seam-foreground-subagent", 0, null, Instant.parse("2026-10-14T18:08:46Z"),
                new RunDescriptor.Cli("Claude Code", "2.1.299", "Anthropic", 1, "Native"), null, null);
        Clock clock = Clock.systemUTC();
        PhaseCapture capture;
        int seenBeforeEnd;
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run, SchemaPin.bundled(), WriterOptions.defaults().withClock(clock).withAutoFlush(4))) {
            SourceInput in = w.source(new SourceDescriptor("stream.jsonl", 1, "claude_stream", "sdk",
                    "SDK-delivered ParsedMessage.rawJson lines, teed at the Iterator<ParsedMessage> seam; lines the SDK does not parse are not delivered", null));
            List<ParsedMessage> seen = new ArrayList<>();
            Iterator<ParsedMessage> teed = tee(messages.iterator(), in, clock, m -> {
                seen.add(m);
                if (seen.size() == 12) {
                    // mid-iteration: the package on disk is already readable and answers queries
                    OcsfPackage prefix = PackageReader.open(core.resolve("events.jsonl"));
                    assertThat(prefix.refused()).isFalse();
                    assertThat(prefix.manifest.packageState()).isEqualTo("RECORDING");
                    assertThat(prefix.events.size()).isBetween(8, 14);
                    assertThat(AnalysisViews.all(prefix).path("tool_exchanges", "calls").elements()).hasSize(1);
                }
            });
            capture = SessionLogParser.parse(teed, "seam", "synthetic prompt");
            seenBeforeEnd = seen.size();
            assertThat(in.records()).isEqualTo(delivered.size());
            w.complete();
        }
        assertThat(seenBeforeEnd).isEqualTo(messages.size());
        // the production capture is what it always was
        assertThat(capture.toolUses()).hasSize(2);
        assertThat(capture.subagents()).hasSize(1);
        assertThat(capture.sourceRecords()).hasSize(delivered.stream().filter(l -> l.contains("\"type\":\"assistant\"") || l.contains("\"type\":\"user\"")).toList().size());
        // the writer's package is complete for the delivered surface, with the child routed to its own core
        Path nat = work.resolve("native");
        Files.createDirectories(nat);
        Files.writeString(nat.resolve("stream.jsonl"), String.join("\n", delivered) + "\n", StandardCharsets.UTF_8);
        OcsfPackage pkg = PackageReader.open(core.resolve("events.jsonl"));
        assertThat(pkg.manifest.packageState()).isEqualTo("COMPLETED");
        assertThat(pkg.children).hasSize(1);
        OracleReport report = OcsfOracle.verify(pkg, nat);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("package.state", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }
}
