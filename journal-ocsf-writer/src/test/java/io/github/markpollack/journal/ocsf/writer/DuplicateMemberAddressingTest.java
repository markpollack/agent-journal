package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OracleReport;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exact addressing of repeated object members. The Codex exec-stream contract interprets one duplicate form only: the
 * {@code id} member repeated, twice, inside the {@code item} object of a web_search {@code item.started} or
 * {@code item.completed} line. A root member literally named {@code item.id} is a different location (RFC 6901
 * {@code /item.id}, not {@code /item/id}); a selector that joins member names with dots cannot tell them apart.
 */
class DuplicateMemberAddressingTest {

    /** SYNTHETIC (review finding): a normal nested item.id, plus two root members literally named "item.id". */
    static final String DOTTED_ROOT_LITERALS = "{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\",\"type\":\"web_search\",\"query\":\"\",\"action\":{\"type\":\"other\"}},"
            + "\"item.id\":\"a\",\"item.id\":\"b\"}";

    /** SYNTHETIC, in the vendor's key order: the declared form the pinned emitter writes (ThreadItem.id, then the flattened WebSearchItem.id). */
    static final String DECLARED = "{\"type\":\"item.started\",\"item\":{\"id\":\"item_2\",\"type\":\"web_search\",\"id\":\"search-2\",\"query\":\"\",\"action\":{\"type\":\"other\"}}}";

    static NativeRecord record(String line) {
        return NativeRecord.classify(1, line.getBytes(StandardCharsets.UTF_8), "LF", Instant.EPOCH);
    }

    @Test
    void aRootMemberLiterallyNamedItemIdIsNotTheDeclaredForm() {
        NativeRecord r = record(DOTTED_ROOT_LITERALS);
        assertThat(r.status()).as("strict parsing refuses the repeated root member").isEqualTo(NativeRecord.Status.MALFORMED);
        assertThat(CodexItems.declaredWebSearch(r)).as("the repeat is the root's own member \"item.id\" (/item.id), not item's id (/item/id)").isNull();
        assertThat(CodexItems.declaredWebSearch(record(DECLARED))).as("control: the declared form").isNotNull();
    }

    /**
     * The same line in a whole stream, judged by the independent oracle: the dotted-literal line is retained verbatim
     * and states no tool call, the declared line states one under its first id, and the package has no defect.
     */
    @Test
    void theWholeOracleAcceptsTheDottedLiteralLineOnlyAsAnOpaqueRecord(@TempDir Path work) throws IOException {
        List<String> lines = List.of("{\"type\":\"thread.started\",\"thread_id\":\"00000000-0000-4000-8000-0000000d0b1e\"}", DOTTED_ROOT_LITERALS, DECLARED);
        Path nativeDir = work.resolve("native");
        Files.createDirectories(nativeDir);
        Files.writeString(nativeDir.resolve("stream.jsonl"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        WriterTestSupport.freeze(nativeDir, run(CODEX_STREAM).runUid(), true, "stream.jsonl", "codex_stream");
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CODEX_STREAM), SchemaPin.bundled(), options())) {
            Replay.feedRecords(w.source(codexStream()), nativeDir.resolve("stream.jsonl"), options().clock());
            w.complete();
        }
        List<Jv> toolCalls = new ArrayList<>();
        for (String line : WriterTestSupport.eventLines(core.resolve("events.jsonl"))) {
            Jv e = Jv.parse(line);
            if ("Tool Call".equals(e.member("class_name").text())) {
                toolCalls.add(e);
            }
        }
        OracleReport report = WriterTestSupport.verify(core, nativeDir);
        // both facts are reported together: what the writer emitted and what the whole oracle says about it
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(toolCalls.stream().map(e -> ((Jv.Num) e.path("source_ref", "record_index")).value().intValue()).toList())
                .as("only the declared line (record 3) states a tool call").containsExactly(3);
        soft.assertThat(WriterTestSupport.defects(report)).as("the whole oracle on the package").isEmpty();
        soft.assertAll();
    }
}
