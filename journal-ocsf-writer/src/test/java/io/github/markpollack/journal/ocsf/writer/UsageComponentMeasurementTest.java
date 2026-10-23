package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.codexStream;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Component-level measurement of Codex exec-stream usage. At rust-v0.160.1 the provider usage deserializer defaults an
 * omitted {@code cache_write_tokens} to 0 ({@code #[serde(default)]}, codex-api sse/responses.rs and protocol
 * TokenUsage), an omitted {@code input_tokens_details} to cached 0 and cache write 0 ({@code unwrap_or_default}) and an
 * omitted {@code output_tokens_details} to reasoning 0 ({@code unwrap_or(0)}); the exec stream then always prints all
 * five members. A zero in those three components therefore does not establish a measured zero, even when the other
 * components show that a usage update arrived.
 */
class UsageComponentMeasurementTest {

    static final String HEAD = "{\"type\":\"thread.started\",\"thread_id\":\"thread-synthetic\"}\n{\"type\":\"turn.started\"}\n";

    /** The usage observation the writer states for one synthetic turn.completed carrying {@code usage}. */
    static Jv usage(Path work, String usage) throws IOException {
        Path in = work.resolve("stream.jsonl");
        Files.writeString(in, HEAD + "{\"type\":\"turn.completed\",\"usage\":" + usage + "}\n", StandardCharsets.UTF_8);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CODEX_STREAM), SchemaPin.bundled(), options())) {
            Replay.feedRecords(w.source(codexStream()), in, options().clock());
            assertThat(w.complete()).isEqualTo(OcsfRunWriter.State.COMPLETED);
        }
        List<String> lines = WriterTestSupport.eventLines(core.resolve("events.jsonl"));
        return Jv.parse(lines.get(lines.size() - 1)).member("usage_observations").elements().get(0);
    }

    static Jv component(Jv observation, String name) {
        for (Jv c : observation.member("components").elements()) {
            if (name.equals(c.member("name").text())) {
                return c;
            }
        }
        throw new AssertionError("no component " + name);
    }

    static int integer(Jv v) {
        return ((Jv.Num) v).value().intValue();
    }

    /**
     * Reproduction on the unchanged writer: non-zero usage with a cache-write 0 (and reasoning 0) is stated as a
     * measured observation, the zero components as plain reported values and the uncached tranche as an unconditional
     * derivation, so a provider that omitted cache writes and one that measured none produce the same canonical facts.
     */
    @Test
    void reproductionAPossiblyDefaultedZeroInsideNonZeroUsageIsStatedLikeAMeasuredZero(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0}");
        assertThat(integer(o.member("measurement_status_id"))).isEqualTo(1);
        Jv writes = component(o, "cache_write_tokens");
        assertThat(integer(writes.member("component_basis_id"))).isEqualTo(1);
        assertThat(writes.member("measurement_status_id")).as("no component-level measurement status").isNull();
        Jv uncached = component(o, "uncached_input_tokens");
        assertThat(integer(uncached.member("component_basis_id"))).isEqualTo(2);
        assertThat(integer(o.member("uncached_input_tokens"))).isEqualTo(200);
        assertThat(uncached.member("measurement_status_id")).as("the derivation is stated without its dependence on the possibly defaulted operand").isNull();
        assertThat(component(o, "reasoning_tokens").member("measurement_status_id")).isNull();
    }
}
