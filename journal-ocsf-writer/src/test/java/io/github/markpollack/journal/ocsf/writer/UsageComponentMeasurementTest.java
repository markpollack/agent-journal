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

    static int status(Jv observation, String name) {
        Jv v = component(observation, name).member("measurement_status_id");
        return v == null ? -1 : integer(v);
    }

    /**
     * Reproduced at ee0f6e0 on the unchanged writer: this input was stated as a measured observation with plain
     * reported zeros and an unconditional uncached derivation. Repaired: the zeros the deserializer also writes for an
     * omitted member are Possibly default, the derivation that uses one is Conditional, and every number is kept.
     */
    @Test
    void aPossiblyDefaultedZeroInsideNonZeroUsageIsMarkedAndItsDerivationConditional(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0}");
        assertThat(integer(o.member("measurement_status_id"))).as("an update arrived").isEqualTo(1);
        assertThat(status(o, "cache_write_tokens")).isEqualTo(2);
        assertThat(status(o, "reasoning_tokens")).isEqualTo(2);
        assertThat(status(o, "cache_read_tokens")).isEqualTo(1);
        assertThat(status(o, "output_tokens")).isEqualTo(1);
        assertThat(integer(o.member("uncached_input_tokens"))).as("the number is kept").isEqualTo(200);
        assertThat(integer(component(o, "uncached_input_tokens").member("component_basis_id"))).isEqualTo(2);
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(3);
        assertThat(component(o, "uncached_input_tokens").member("derivation").text()).contains("cache_write_input_tokens is Possibly default").contains("input not read from cache");
        assertThat(integer(o.member("cache_write_tokens"))).isZero();
        assertThat(o.member("basis").text()).contains("codex.usage.component_measurement.v1");
    }

    /** A zero in a required member of an updated observation is a measured zero, beside defaulted zeros in the same observation. */
    @Test
    void aZeroRequiredMemberIsMeasuredBesideDefaultedZeros(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1200,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,\"output_tokens\":0,\"reasoning_output_tokens\":0}");
        assertThat(status(o, "output_tokens")).isEqualTo(1);
        assertThat(status(o, "cache_read_tokens")).isEqualTo(2);
        assertThat(status(o, "cache_write_tokens")).isEqualTo(2);
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(3);
        assertThat(component(o, "uncached_input_tokens").member("derivation").text()).contains("cached_input_tokens and cache_write_input_tokens are Possibly default");
    }

    /** All zeros: no update is established, so every component, required or not, is Possibly default. */
    @Test
    void anAllZeroObservationHasOnlyPossiblyDefaultComponents(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":0,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,\"output_tokens\":0,\"reasoning_output_tokens\":0}");
        assertThat(integer(o.member("measurement_status_id"))).isEqualTo(2);
        for (String c : List.of("cache_read_tokens", "cache_write_tokens", "output_tokens", "reasoning_tokens")) {
            assertThat(status(o, c)).as(c).isEqualTo(2);
        }
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(3);
    }

    /** A non-zero cache write cannot be a default: it is measured, and with unknown inclusion the uncached tranche is unavailable without a status. */
    @Test
    void aNonZeroCacheWriteIsMeasuredAndTheUnprovenTrancheUnavailable(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":60,\"output_tokens\":50,\"reasoning_output_tokens\":10}");
        assertThat(status(o, "cache_write_tokens")).isEqualTo(1);
        assertThat(integer(component(o, "uncached_input_tokens").member("component_basis_id"))).isEqualTo(3);
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(-1);
        assertThat(o.member("uncached_input_tokens")).isNull();
    }

    /** An absent operand: no uncached value, no cache-read component, present members keep their status. */
    @Test
    void anAbsentOperandLeavesTheTrancheUnavailableAndTheComponentAbsent(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":5}");
        assertThat(integer(component(o, "uncached_input_tokens").member("component_basis_id"))).isEqualTo(3);
        assertThat(o.member("cache_read_tokens")).isNull();
        assertThat(o.member("components").elements()).noneMatch(c -> "cache_read_tokens".equals(c.member("name").text()));
        assertThat(status(o, "cache_write_tokens")).isEqualTo(2);
        assertThat(status(o, "reasoning_tokens")).isEqualTo(1);
    }

    /** The experimental rollout reading keeps the components without measurement statuses (it follows in SESSION-1). */
    @Test
    void theRolloutReadingStatesNoComponentMeasurementYet() {
        Jv.Obj into = new Jv.Obj();
        UsageTranches.codex(into, Jv.parse("{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0,\"total_tokens\":1050}"), "");
        assertThat(into.member("components").elements()).allMatch(c -> c.member("measurement_status_id") == null);
    }
}
