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
 * components show that a usage update arrived. The printed usage is the thread running total, which adds a response's
 * usage only when its completion carried one, so no member, however large, establishes the complete quantity.
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
     * reported zeros and an unconditional uncached derivation. Repaired at a7673e3 (zeros the deserializer also writes for
     * an omitted member are Possibly default), and again for the requirements reconciliation: the positive members of the
     * running total are only lower bounds, and the uncached difference is a conditional value, not the canonical tranche.
     */
    @Test
    void aPossiblyDefaultedZeroInsideNonZeroUsageIsMarkedAndItsDerivationConditional(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0}");
        assertThat(integer(o.member("measurement_status_id"))).as("an update arrived, into a running total").isEqualTo(4);
        assertThat(status(o, "cache_write_tokens")).isEqualTo(2);
        assertThat(status(o, "reasoning_tokens")).isEqualTo(2);
        assertThat(status(o, "cache_read_tokens")).isEqualTo(4);
        assertThat(status(o, "output_tokens")).isEqualTo(4);
        assertThat(o.member("uncached_input_tokens")).as("a conditional result is never the canonical tranche").isNull();
        Jv uncached = component(o, "uncached_input_tokens");
        assertThat(integer(uncached.member("component_basis_id"))).isEqualTo(3);
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(3);
        assertThat(integer(uncached.member("conditional_value"))).as("the formula's result is kept apart").isEqualTo(200);
        assertThat(uncached.member("derivation").text()).contains("codex.usage.uncached_input.v2").contains("only if cache_write_input_tokens is a measured 0")
                .contains("never the canonical tranche");
        assertThat(integer(o.member("cache_write_tokens"))).isZero();
        assertThat(o.member("basis").text()).contains("codex.usage.component_measurement.v2").contains("adds only the responses whose completion carried usage");
    }

    /**
     * A zero in a required member is not the deserializer's default, but the running total only adds the responses whose
     * usage arrived, so it is a lower bound like any other non-defaultable member; the defaultable zeros beside it are
     * Possibly default. The two zeros stay distinguishable.
     */
    @Test
    void aZeroRequiredMemberIsALowerBoundNotADefaultBesideDefaultedZeros(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1200,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,\"output_tokens\":0,\"reasoning_output_tokens\":0}");
        assertThat(status(o, "output_tokens")).isEqualTo(4);
        assertThat(status(o, "cache_read_tokens")).isEqualTo(2);
        assertThat(status(o, "cache_write_tokens")).isEqualTo(2);
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(3);
        assertThat(integer(component(o, "uncached_input_tokens").member("conditional_value"))).isEqualTo(1200);
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
        assertThat(o.member("uncached_input_tokens")).isNull();
    }

    /** A non-zero cache write cannot be a default but is only a lower bound; with unknown inclusion the uncached tranche is unavailable with no conditional value. */
    @Test
    void aNonZeroCacheWriteIsALowerBoundAndTheUnprovenTrancheUnavailable(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":60,\"output_tokens\":50,\"reasoning_output_tokens\":10}");
        assertThat(status(o, "cache_write_tokens")).isEqualTo(4);
        assertThat(integer(component(o, "uncached_input_tokens").member("component_basis_id"))).isEqualTo(3);
        assertThat(status(o, "uncached_input_tokens")).isEqualTo(-1);
        assertThat(component(o, "uncached_input_tokens").member("conditional_value")).isNull();
        assertThat(o.member("uncached_input_tokens")).isNull();
    }

    /**
     * Reproduced on the unchanged a7673e3 writer, which stated this cache write 60 Measured on the assumption that a
     * provider reports a member on every response or on none. The same printed running total arises when one response
     * reported 60 cache-write tokens and a second omitted the member (written as 0), or when a third response's completion
     * carried no usage at all (added nothing): positivity cannot establish that every contribution was reported, so every
     * positive member is a Lower bound, the observation too, and the number is kept.
     */
    @Test
    void aPositiveRunningTotalWithAnOmittedContributionIsALowerBoundNotAMeasurement(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":2000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":60,\"output_tokens\":90,\"reasoning_output_tokens\":10}");
        assertThat(integer(o.member("cache_write_tokens"))).as("the reported number is kept").isEqualTo(60);
        for (String c : List.of("cache_read_tokens", "cache_write_tokens", "output_tokens", "reasoning_tokens")) {
            assertThat(status(o, c)).as(c + " is at least its value, never established complete").isEqualTo(4);
        }
        assertThat(integer(o.member("measurement_status_id"))).isEqualTo(4);
        assertThat(o.member("components").elements()).noneMatch(c -> c.member("measurement_status_id") instanceof Jv.Num n && n.value().intValue() == 1);
    }

    /** An absent operand: no uncached value, no cache-read component, present members keep their status. */
    @Test
    void anAbsentOperandLeavesTheTrancheUnavailableAndTheComponentAbsent(@TempDir Path work) throws IOException {
        Jv o = usage(work, "{\"input_tokens\":1000,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":5}");
        assertThat(integer(component(o, "uncached_input_tokens").member("component_basis_id"))).isEqualTo(3);
        assertThat(component(o, "uncached_input_tokens").member("conditional_value")).isNull();
        assertThat(o.member("cache_read_tokens")).isNull();
        assertThat(o.member("components").elements()).noneMatch(c -> "cache_read_tokens".equals(c.member("name").text()));
        assertThat(status(o, "cache_write_tokens")).isEqualTo(2);
        assertThat(status(o, "reasoning_tokens")).isEqualTo(4);
    }

    /**
     * The experimental rollout reading states no measurement status for its copied components yet (SESSION-1), but its
     * uncached difference rests on the same possibly defaulted cache-write 0, so it too is only a conditional value.
     */
    @Test
    void theRolloutReadingStatesNoComponentMeasurementYetButKeepsTheUncachedConditional() {
        Jv.Obj into = new Jv.Obj();
        UsageTranches.codex(into, Jv.parse("{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0,\"total_tokens\":1050}"), "");
        assertThat(into.member("components").elements()).filteredOn(c -> !"uncached_input_tokens".equals(c.member("name").text()))
                .allMatch(c -> c.member("measurement_status_id") == null);
        assertThat(into.member("uncached_input_tokens")).isNull();
        Jv uncached = component(into, "uncached_input_tokens");
        assertThat(integer(uncached.member("measurement_status_id"))).isEqualTo(3);
        assertThat(integer(uncached.member("conditional_value"))).isEqualTo(200);
        assertThat(into.member("basis")).as("the exec-stream running-total rule is not claimed for the rollout").isNull();
    }
}
