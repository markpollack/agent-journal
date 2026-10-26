package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeTranscript;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared mapping helpers and the block/decision/usage conventions on the events the Claude mappers write for the
 * pair fixture: block kinds and ids, bare-string content, thinking text, rendered blocks, decision ids, integral-only
 * usage components, model references, attribution scope, time formatting and native record classification.
 */
class MapperSupportTest {

    @Test
    void blocksCarryTheirIdsTextsAndKindsAndBareStringsAreOneTextBlock(@TempDir Path work) {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.openEvidenceLog(core, run(PAIR), SchemaPin.bundled(), options(), WriterTestSupport.PAIR_LOG)) {
            Replay.feedBytes(w.source(claudeStream()), nativeOf(PAIR, "stream.jsonl"), 4096, options().clock());
            Replay.feedBytes(w.source(claudeTranscript()), nativeOf(PAIR, "transcript.jsonl"), 4096, options().clock());
            w.complete();
        }
        List<Jv> events = new ArrayList<>();
        for (String line : WriterTestSupport.lines(core.resolve("events.jsonl"))) {
            events.add(Jv.parse(line));
        }
        int toolUse = 0;
        int toolResult = 0;
        int thinking = 0;
        int bare = 0;
        int rendered = 0;
        for (Jv e : events) {
            for (Jv b : e.member("content_blocks") == null ? List.<Jv>of() : e.member("content_blocks").elements()) {
                String type = b.member("block_type").text();
                switch (type) {
                    case "tool_use" -> {
                        toolUse++;
                        assertThat(b.member("tool_call_uid").text()).isEqualTo(b.path("data", "id").text());
                        assertThat(((Jv.Num) b.member("block_kind_id")).value().intValue()).isEqualTo(3);
                    }
                    case "tool_result" -> {
                        toolResult++;
                        assertThat(b.member("tool_call_uid").text()).isEqualTo(b.path("data", "tool_use_id").text());
                        assertThat(((Jv.Num) b.member("block_kind_id")).value().intValue()).isEqualTo(4);
                    }
                    case "thinking" -> {
                        thinking++;
                        assertThat(b.member("text").text()).isEqualTo(b.path("data", "thinking").text());
                        assertThat(((Jv.Num) b.member("block_kind_id")).value().intValue()).isEqualTo(2);
                    }
                    case "string" -> {
                        assertThat(b.member("text").text()).isEqualTo(b.member("data").text());
                        if (List.of("user/tool_result", "task_started", "system/task_notification").contains(String.valueOf(MapperSupport.text(e, "context_kind")))) {
                            assertThat(b.member("basis")).as("Task content has no message-content basis").isNull();
                        }
                        else {
                            bare++;
                            assertThat(b.member("basis").text()).isEqualTo("message.content is a bare string");
                        }
                    }
                    case "rendered" -> {
                        rendered++;
                        assertThat(b.member("text").text()).isEqualTo(b.path("data", "content").text());
                    }
                    case "text" -> assertThat(b.member("text").text()).isEqualTo(b.path("data", "text").text());
                    default -> {
                    }
                }
                assertThat(b.member("block_kind").text()).isEqualTo(MapperSupport.BLOCK_KIND.get(((Jv.Num) b.member("block_kind_id")).value().intValue()));
            }
        }
        assertThat(toolUse).as("root core: stream records 5 and 16 plus transcript record 13 (record 20 belongs to the child core)").isEqualTo(3);
        assertThat(toolResult).as("tool_result blocks become tool_call Result events, never content blocks of a message").isEqualTo(0);
        // the block conventions on content the mappers do not route through a message: a direct call
        Jv.Arr direct = MapperSupport.blocks(new MappingContracts(), ClaudeStreamMapper.CONTRACT, Jv.parse(
                "[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_x\",\"content\":\"r\"},{\"type\":\"text\",\"text\":\"t\"},{\"type\":\"thinking\",\"thinking\":\"th\"},{\"type\":\"tool_use\",\"id\":\"toolu_y\",\"name\":\"Read\",\"input\":{}},{\"no_type\":true},{\"type\":\"image\"}]"));
        assertThat(direct.elements()).hasSize(6);
        assertThat(direct.elements().get(0).member("tool_call_uid").text()).isEqualTo("toolu_x");
        assertThat(((Jv.Num) direct.elements().get(0).member("block_kind_id")).value().intValue()).isEqualTo(4);
        assertThat(direct.elements().get(0).member("text")).isNull();
        assertThat(direct.elements().get(1).member("text").text()).isEqualTo("t");
        assertThat(direct.elements().get(2).member("text").text()).isEqualTo("th");
        assertThat(direct.elements().get(3).member("tool_call_uid").text()).isEqualTo("toolu_y");
        assertThat(direct.elements().get(4).member("block_type").text()).isEqualTo("(untyped)");
        assertThat(direct.elements().get(4).member("tool_call_uid")).isNull();
        assertThat(((Jv.Num) direct.elements().get(5).member("block_index")).value().intValue()).isEqualTo(5);
        assertThat(MapperSupport.blocks(new MappingContracts(), ClaudeStreamMapper.CONTRACT, null).elements()).isEmpty();
        Jv.Arr renderedDirect = MapperSupport.renderedBlocks(Jv.parse("[{\"content\":\"a\"},{\"other\":1}]"));
        assertThat(renderedDirect.elements().get(0).member("text").text()).isEqualTo("a");
        assertThat(renderedDirect.elements().get(1).member("text")).isNull();
        assertThat(((Jv.Num) renderedDirect.elements().get(1).member("block_index")).value().intValue()).isEqualTo(1);
        assertThat(MapperSupport.renderedBlocks(null).elements()).isEmpty();
        assertThat(thinking).as("the sanitized stream carries thinking blocks").isPositive();
        assertThat(bare).as("transcript prompt record 3 and stream task-notification message record 27 are bare strings").isEqualTo(2);
        assertThat(rendered).as("three rendered attachments: records 4, 5 and 15; credential_org renders nothing").isEqualTo(3);
        // rendered blocks are indexed from zero in order
        for (Jv e : events) {
            if (e.member("context_kind") != null && e.member("content_blocks") != null) {
                List<Jv> blocks = e.member("content_blocks").elements();
                for (int i = 0; i < blocks.size(); i++) {
                    assertThat(((Jv.Num) blocks.get(i).member("block_index")).value().intValue()).isEqualTo(i);
                }
            }
        }
    }

    @Test
    void decisionIdsUsageComponentsModelRefsAttributionAndTimeAreAsPromised() {
        assertThat(MapperSupport.decisionId("allow")).isEqualTo(1);
        assertThat(MapperSupport.decisionId("accept")).isEqualTo(1);
        assertThat(MapperSupport.decisionId("deny")).isEqualTo(2);
        assertThat(MapperSupport.decisionId("denied")).isEqualTo(2);
        assertThat(MapperSupport.decisionId("ask")).isEqualTo(99);
        assertThat(MapperSupport.decisionId(null)).isEqualTo(99);

        Jv.Obj into = new Jv.Obj();
        UsageTranches.anthropic(into, Jv.parse("{\"input_tokens\":12,\"output_tokens\":1.5,\"cache_read_input_tokens\":\"7\",\"cache_creation_input_tokens\":null}"));
        assertThat(into.members().keySet()).as("only integral numbers are components; a decimal, a string, a null and an absent value stay absent").containsExactly("uncached_input_tokens", "components");
        assertThat(Jv.write(into.get("uncached_input_tokens"))).isEqualTo("12");
        assertThat(Jv.write(into.get("components"))).isEqualTo("[{\"name\":\"uncached_input_tokens\",\"component_basis_id\":1,\"component_basis\":\"Reported\",\"native_pointers\":[\"/input_tokens\"]}]");
        Jv.Obj none = new Jv.Obj();
        UsageTranches.anthropic(none, null);
        assertThat(none.members()).isEmpty();

        Jv.Obj model = MapperSupport.modelRef("m", 1, "vendor");
        assertThat(model.get("provider_kind").text()).isEqualTo("vendor");
        assertThat(model.get("provider_basis").text()).isEqualTo("Reported");
        assertThat(MapperSupport.modelRef("m", 3, null).get("provider_kind")).isNull();

        Jv.Obj att = MapperSupport.attribution(2, 2, List.of("a", "b"), "basis", "turn-1");
        assertThat(att.get("outcome").text()).isEqualTo("Ambiguous");
        assertThat(att.get("method").text()).isEqualTo("Derived by rule");
        assertThat(att.get("scope_uid").text()).isEqualTo("turn-1");
        assertThat(att.get("candidates").elements()).hasSize(2);
        assertThat(MapperSupport.attribution(3, 2, List.of(), "basis", null).get("scope_uid")).isNull();

        Jv.Obj usage = MapperSupport.usage(2, 1, "b", new Jv.Obj(), null, null);
        assertThat(usage.get("model_ref")).isNull();
        assertThat(usage.get("data").isNull()).isTrue();
        assertThat(usage.get("usage_status").text()).isEqualTo("Provisional");
        assertThat(usage.get("scope").text()).isEqualTo("Model response");

        long ms = MapperSupport.rfc3339ToMs("2026-10-21T09:00:00.123Z");
        assertThat(ms).isEqualTo(Instant.parse("2026-10-21T09:00:00.123Z").toEpochMilli());
        assertThat(MapperSupport.msToRfc3339(ms)).isEqualTo("2026-10-21T09:00:00.123Z");
        assertThat(MapperSupport.text(Jv.parse("{\"a\":{\"b\":null}}"), "a", "b")).isNull();
        assertThat(MapperSupport.text(Jv.parse("{\"a\":{\"b\":\"c\"}}"), "a", "b")).isEqualTo("c");
        assertThat(MapperSupport.text(null, "a")).isNull();
    }

    @Test
    void nativeRecordsAreClassifiedByBytesAndTerminator() {
        Instant t = Instant.EPOCH;
        assertThat(NativeRecord.classify(1, "{\"a\":1}".getBytes(), "LF", t).status()).isEqualTo(NativeRecord.Status.PARSED);
        assertThat(NativeRecord.classify(1, "{\"a\":".getBytes(), "LF", t).status()).as("terminated but not JSON").isEqualTo(NativeRecord.Status.MALFORMED);
        assertThat(NativeRecord.classify(1, "{\"a\":".getBytes(), "none", t).status()).as("unterminated and not JSON: the source ended first").isEqualTo(NativeRecord.Status.INCOMPLETE);
        assertThat(NativeRecord.classify(1, "[1]".getBytes(), "LF", t).status()).as("not an object").isEqualTo(NativeRecord.Status.MALFORMED);
        assertThat(NativeRecord.classify(1, "[1]".getBytes(), "none", t).status()).isEqualTo(NativeRecord.Status.INCOMPLETE);
        assertThat(NativeRecord.classify(1, new byte[] {'{', (byte) 0xff, '}'}, "LF", t).status()).isEqualTo(NativeRecord.Status.UNDECODABLE);
        assertThat(NativeRecord.classify(1, new byte[] {'{', (byte) 0xff}, "none", t).status()).isEqualTo(NativeRecord.Status.INCOMPLETE);
        assertThat(NativeRecord.classify(1, new byte[0], "LF", t).status()).isEqualTo(NativeRecord.Status.BLANK);
        assertThat(NativeRecord.classify(1, "{\"a\":1}".getBytes(), "none", t).status()).as("unterminated but whole").isEqualTo(NativeRecord.Status.PARSED);
    }
}
