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
        // the declared line keeps both ids in its verbatim record; the written events themselves have no repeated member
        // anywhere (every line parses under strict, recursive duplicate detection)
        List<String> written = WriterTestSupport.eventLines(core.resolve("events.jsonl"));
        assertThat(written).anySatisfy(l -> assertThat(Jv.parse(l).member("raw_data") == null ? null : Jv.parse(l).member("raw_data").text()).isEqualTo(DECLARED));
        for (String l : WriterTestSupport.lines(core.resolve("events.jsonl"))) {
            assertThat(Jv.parseRecordingDuplicates(l).repeated()).as(l).isEmpty();
        }
    }

    /** One SYNTHETIC line, whether it is the declared form, and the RFC 6901 pointer of its first repeated member. */
    record Case(String name, String line, boolean declared, String pointer) {
    }

    static final List<Case> CASES = List.of(
            new Case("declared, started", DECLARED, true, "/item/id"),
            new Case("declared, completed", "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\",\"type\":\"web_search\",\"id\":\"search-2\",\"query\":\"q\",\"action\":{\"type\":\"search\",\"query\":\"q\"}}}", true, "/item/id"),
            new Case("root members literally named item.id, with a nested id", DOTTED_ROOT_LITERALS, false, "/item.id"),
            new Case("root members literally named item.id, no nested id", "{\"type\":\"item.started\",\"item\":{\"type\":\"web_search\",\"query\":\"\"},\"item.id\":\"a\",\"item.id\":\"b\"}", false, "/item.id"),
            new Case("a member of item literally named item.id", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_3\",\"type\":\"web_search\",\"item.id\":\"x\",\"item.id\":\"y\"}}", false, "/item/item.id"),
            new Case("a name with a slash", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_4\",\"type\":\"web_search\",\"a/b\":\"x\",\"a/b\":\"y\"}}", false, "/item/a~1b"),
            new Case("a name with a tilde", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_5\",\"type\":\"web_search\",\"i~d\":\"x\",\"i~d\":\"y\"}}", false, "/item/i~0d"),
            new Case("id repeated at the root", "{\"type\":\"item.started\",\"id\":\"x\",\"id\":\"y\",\"item\":{\"id\":\"item_6\",\"type\":\"web_search\"}}", false, "/id"),
            new Case("id repeated inside the action", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_7\",\"type\":\"web_search\",\"action\":{\"id\":\"x\",\"id\":\"y\"}}}", false, "/item/action/id"),
            new Case("id repeated inside an array element", "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_8\",\"type\":\"web_search\",\"results\":[{\"id\":\"x\",\"id\":\"y\"}]}}", false, "/item/results/0/id"),
            new Case("three ids", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_9\",\"type\":\"web_search\",\"id\":\"s\",\"id\":\"t\"}}", false, "/item/id"),
            new Case("a second id that is not a string", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_10\",\"type\":\"web_search\",\"id\":10}}", false, "/item/id"),
            new Case("another item type", "{\"type\":\"item.completed\",\"item\":{\"id\":\"item_11\",\"type\":\"agent_message\",\"id\":\"msg-11\",\"text\":\"x\"}}", false, "/item/id"),
            new Case("another record type", "{\"type\":\"turn.completed\",\"item\":{\"id\":\"item_12\",\"type\":\"web_search\",\"id\":\"s\"}}", false, "/item/id"),
            new Case("an unrelated repeat beside the declared one", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_13\",\"type\":\"web_search\",\"id\":\"s\",\"query\":\"a\",\"query\":\"b\"}}", false, "/item/id"),
            new Case("the item object itself repeated", "{\"type\":\"item.started\",\"item\":{\"id\":\"item_14\",\"type\":\"web_search\"},\"item\":{\"id\":\"item_15\",\"type\":\"web_search\",\"id\":\"s\"}}", false, "/item/id"));

    /**
     * Literal dots, slash and tilde names, other containers, types and counts, an unrelated repeat and a repeated
     * container: only the two declared lines are interpreted, every line stays refused by strict parsing, and each
     * repeat is reported at its exact RFC 6901 location with every value.
     */
    @Test
    void onlyTheExactDeclaredLocationIsInterpretedAndEveryRepeatHasItsExactLocation() {
        for (Case c : CASES) {
            NativeRecord r = record(c.line());
            assertThat(r.status()).as(c.name() + ": strict parsing").isEqualTo(NativeRecord.Status.MALFORMED);
            Jv.WithDuplicates w = CodexItems.declaredWebSearch(r);
            assertThat(w != null).as(c.name()).isEqualTo(c.declared());
            Jv.WithDuplicates all = Jv.parseRecordingDuplicates(c.line());
            assertThat(all.repeated().stream().map(Jv.Repeated::pointer).toList()).as(c.name()).contains(c.pointer());
            if (c.declared()) {
                assertThat(all.repeated()).hasSize(1);
                assertThat(all.repeated().get(0).values()).as("both ids, in source order").hasSize(2);
                assertThat(w.value().path("item", "id")).as("the tree keeps the first id").isEqualTo(all.repeated().get(0).values().get(0));
            }
        }
        // a repeat inside the second of two equal names is told apart from the first by its container's occurrence
        Jv.WithDuplicates twice = Jv.parseRecordingDuplicates(CASES.get(15).line());
        assertThat(twice.repeated()).extracting(Jv.Repeated::container).contains(Jv.Location.ROOT.member("item", 2), Jv.Location.ROOT);
        assertThat(twice.repeated()).noneMatch(x -> x.container().equals(Jv.Location.ROOT.member("item", 1)));
    }
}
