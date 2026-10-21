package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.notVerified;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.open;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * LABELLED SYNTHETIC block kinds the two real runs do not contain: a redacted (encrypted) thinking block, an image
 * block and an unknown block type, each in the shape of a Claude stream assistant record. The writer must keep the
 * block exactly (kind Other/Image by the declared table, native block in data, number spellings intact) and the
 * independent oracle must find it exact; nothing is interpreted beyond the declared kind table.
 */
class UnexercisedBlockKindsTest {

    private static final String[] RECORDS = {
        "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_syn_blocks\",\"model\":\"claude-synthetic\",\"role\":\"assistant\",\"stop_reason\":null,"
                + "\"content\":[{\"type\":\"redacted_thinking\",\"data\":\"EncryptedOpaquePayload==\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2}},"
                + "\"session_id\":\"syn-session\",\"uuid\":\"u-1\",\"timestamp\":\"2026-10-21T00:00:01.000Z\"}",
        "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_syn_blocks\",\"model\":\"claude-synthetic\",\"role\":\"assistant\",\"stop_reason\":null,"
                + "\"content\":[{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"iVBORw0KGgo=\"}}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2}},"
                + "\"session_id\":\"syn-session\",\"uuid\":\"u-2\",\"timestamp\":\"2026-10-21T00:00:02.000Z\"}",
        "{\"type\":\"assistant\",\"message\":{\"id\":\"msg_syn_blocks\",\"model\":\"claude-synthetic\",\"role\":\"assistant\",\"stop_reason\":\"end_turn\","
                + "\"content\":[{\"type\":\"server_tool_use_result\",\"payload\":{\"n\":1.50,\"big\":9007199254740993,\"flag\":false,\"none\":null,\"empty\":{}}}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2}},"
                + "\"session_id\":\"syn-session\",\"uuid\":\"u-3\",\"timestamp\":\"2026-10-21T00:00:03.000Z\"}"};

    private Path core;
    private Path nativeDir;

    @BeforeEach
    void write(@TempDir Path work) throws Exception {
        core = work.resolve("core");
        nativeDir = work.resolve("native");
        Files.createDirectories(nativeDir);
        Files.writeString(nativeDir.resolve("stream.jsonl"), String.join("\n", RECORDS) + "\n", StandardCharsets.UTF_8);
        RunDescriptor run = new RunDescriptor("syn-block-kinds", 0, "syn-session", Instant.parse("2026-10-21T00:00:00Z"),
                new RunDescriptor.Cli("Claude Code", "2.1.303", "Anthropic", 1, "Native"), null,
                new Jv.Obj().put("label", "LABELLED SYNTHETIC: block kinds absent from the real runs"));
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run, SchemaPin.bundled(), options())) {
            SourceInput in = w.source(claudeStream());
            for (String r : RECORDS) {
                in.record(r.getBytes(StandardCharsets.UTF_8), "LF", options().clock().instant());
            }
            in.end(SourceInput.End.EOF, options().clock().instant());
            w.complete();
        }
    }

    @Test
    void labelledSyntheticRedactedThinkingBlockIsRetainedWholeWithKindOther() {
        Json block = block(1);
        assertThat(block.member("block_type").text()).isEqualTo("redacted_thinking");
        assertThat(block.member("block_kind_id").scalarText()).isEqualTo("99");
        assertThat(block.member("text")).as("no readable text is invented for an opaque payload").isNull();
        assertThat(block.path("data", "data").text()).isEqualTo("EncryptedOpaquePayload==");
        judged();
    }

    @Test
    void labelledSyntheticImageBlockKeepsItsBase64AndOrder() {
        Json block = block(2);
        assertThat(block.member("block_type").text()).isEqualTo("image");
        assertThat(block.member("block_kind").text()).isEqualTo("Image");
        assertThat(block.path("data", "source", "data").text()).isEqualTo("iVBORw0KGgo=");
        assertThat(block.member("block_index").scalarText()).isEqualTo("0");
        judged();
    }

    @Test
    void labelledSyntheticUnknownBlockTypeIsKindOtherWithExactData() {
        Json block = block(3);
        assertThat(block.member("block_type").text()).isEqualTo("server_tool_use_result");
        assertThat(block.member("block_kind_id").scalarText()).isEqualTo("99");
        Json payload = block.path("data", "payload");
        assertThat(((Json.JNumber) payload.member("n")).text()).isEqualTo("1.50");
        assertThat(((Json.JNumber) payload.member("big")).text()).isEqualTo("9007199254740993");
        assertThat(payload.member("flag")).isEqualTo(new Json.JBool(false));
        assertThat(payload.member("none").isNull()).isTrue();
        assertThat(((Json.JObject) payload.member("empty")).members()).isEmpty();
        judged();
    }

    private Json block(int record) {
        OcsfPackage p = open(core);
        for (var e : p.events("model_response")) {
            if (e.recordIndex() == record) {
                List<Json> blocks = e.elements("content_blocks");
                assertThat(blocks).hasSize(1);
                return blocks.get(0);
            }
        }
        throw new AssertionError("no response event for record " + record);
    }

    private void judged() {
        OracleReport report = verify(core, nativeDir);
        assertThat(report.defects()).as(notVerified(report).toString()).isEmpty();
        assertThat(report.outcome("response.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.representation", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("reconstruction.bytes", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }
}
