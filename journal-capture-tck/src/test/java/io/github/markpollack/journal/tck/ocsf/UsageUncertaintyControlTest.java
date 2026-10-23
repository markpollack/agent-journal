package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX_STREAM;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_FEATURES;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.eventLines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The independent oracle's controls for what a usage value establishes, separate from what the source emitted. On the
 * labelled synthetic Codex exec stream (record 12, the thread running total: input 2^53+1, cached 24576, cache writes 0,
 * output 143, reasoning 7) the profile states the uncached tranche unavailable with the difference only as its
 * conditional value, the positive members as lower bounds and the cache-write 0 as possibly the deserializer's default.
 * Each control corrupts one statement of the saved package, leaving the native bytes and every number unchanged, and the
 * oracle must name it: equal numbers do not make a conditional value canonical, a derivation reported, a lower bound a
 * measurement, or caller-supplied data source-reported.
 */
class UsageUncertaintyControlTest {

    static final String STREAM = "main/stream.jsonl";

    /** The thread usage observation of the Codex exec stream's turn completion. */
    static Json usage(java.util.List<Json> events) {
        return at(events, "prompt_turn", 2, 12).member("usage_observations").elements().get(0);
    }

    static Json component(Json usage, int index) {
        return usage.member("components").elements().get(index);
    }

    @Test
    void theUnchangedPackageStatesTheConditionalValueAndTheLowerBoundsAndIsAccepted(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        assertThat(verify(fixture).outcome("usage.values", STREAM)).isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void aConditionalValueWrittenAsTheCanonicalTrancheIsADefect(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        editEvents(pkg(fixture), events -> obj(usage(events)).members().put("uncached_input_tokens", num("9007199254716417")));
        OracleReport report = verify(fixture);
        assertThat(report.outcome("usage.values", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("usage.values", STREAM).detail()).contains("uncached_input_tokens present although the source does not establish it");
    }

    @Test
    void aConditionalDerivationRelabelledReportedOrDerivedIsADefect(@TempDir Path work) {
        Path reported = copy(CODEX_STREAM, work.resolve("reported"));
        editEvents(pkg(reported), events -> obj(component(usage(events), 0)).members().put("component_basis_id", num("1")));
        assertThat(verify(reported).check("usage.values", STREAM).detail()).contains("uncached_input_tokens component_basis_id 1 != 3");
        Path derived = copy(CODEX_STREAM, work.resolve("derived"));
        editEvents(pkg(derived), events -> obj(component(usage(events), 0)).members().put("component_basis_id", num("2")));
        assertThat(verify(derived).check("usage.values", STREAM).detail()).contains("uncached_input_tokens component_basis_id 2 != 3");
    }

    @Test
    void aConditionalStatusOrValueDroppedOrStatedMeasuredIsADefect(@TempDir Path work) {
        Path dropped = copy(CODEX_STREAM, work.resolve("dropped"));
        editEvents(pkg(dropped), events -> obj(component(usage(events), 0)).members().remove("conditional_value"));
        assertThat(verify(dropped).check("usage.values", STREAM).detail()).contains("uncached_input_tokens conditional_value null != 9007199254716417");
        Path measured = copy(CODEX_STREAM, work.resolve("measured"));
        editEvents(pkg(measured), events -> obj(component(usage(events), 0)).members().put("measurement_status_id", num("1")));
        assertThat(verify(measured).check("usage.values", STREAM).detail()).contains("uncached_input_tokens measurement_status_id 1 != 3");
    }

    /** A positive running total relabelled Measured: the number is unchanged, the claimed completeness is the defect. */
    @Test
    void aLowerBoundRelabelledMeasuredIsADefectThoughTheNumberIsUnchanged(@TempDir Path work) {
        Path component = copy(CODEX_STREAM, work.resolve("component"));
        editEvents(pkg(component), events -> obj(component(usage(events), 1)).members().put("measurement_status_id", num("1")));
        OracleReport report = verify(component);
        assertThat(report.outcome("usage.values", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("usage.values", STREAM).detail()).contains("cache_read_tokens measurement_status_id 1 != 4");
        Path observation = copy(CODEX_STREAM, work.resolve("observation"));
        editEvents(pkg(observation), events -> obj(usage(events)).members().put("measurement_status_id", num("1")));
        assertThat(verify(observation).check("usage.values", STREAM).detail()).contains("measurement_status_id 1 != 4");
        Path defaulted = copy(CODEX_STREAM, work.resolve("defaulted"));
        editEvents(pkg(defaulted), events -> obj(component(usage(events), 2)).members().put("measurement_status_id", num("4")));
        assertThat(verify(defaulted).check("usage.values", STREAM).detail()).as("a zero the deserializer also writes is not a lower bound of a measurement")
                .contains("cache_write_tokens measurement_status_id 4 != 2");
    }

    /**
     * The lower bound in its direction: the thread's actual output is at least the reported 143 and may be larger (a
     * response whose usage never arrived adds nothing). A saved value raised to an assumed complete total (here as if a
     * missing response's 50 were added) is a defect, though the claimed quantity could be the true one.
     */
    @Test
    void aLowerBoundRaisedToAnAssumedCompleteTotalIsADefect(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        assertThat(usage(eventLines(pkg(fixture).resolve("events.jsonl"))).member("output_tokens").scalarText()).isEqualTo("143");
        editEvents(pkg(fixture), events -> obj(usage(events)).members().put("output_tokens", num("193")));
        OracleReport report = verify(fixture);
        assertThat(report.outcome("usage.values", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("usage.values", STREAM).detail()).contains("output_tokens VALUE").contains("number value differs");
    }

    /**
     * The economics invariant on the saved observation, without a price feed: equal prices for uncached input and cache
     * writes make the allocation of a possibly omitted cache write immaterial to their subtotal (the conditional value
     * plus the writes gives the same amount for any split), unequal prices do not; and either way the operands are lower
     * bounds of a running total that may miss whole responses, so the saved statuses allow only a lower bound of the
     * charge, never an exact bill. Relabelling the statuses so that an exact bill would follow is itself a defect.
     */
    @Test
    void equalPricesRemoveTheAllocationUncertaintyButNotTheMissingContributions(@TempDir Path work) {
        Path fixture = copy(CODEX_STREAM, work);
        Json u = usage(eventLines(pkg(fixture).resolve("events.jsonl")));
        BigDecimal conditional = new BigDecimal(component(u, 0).member("conditional_value").scalarText());
        BigDecimal writes = new BigDecimal(u.member("cache_write_tokens").scalarText());
        assertThat(component(u, 0).member("measurement_status_id").scalarText()).isEqualTo("3");
        assertThat(component(u, 2).member("measurement_status_id").scalarText()).as("the cache-write 0 may be the deserializer default").isEqualTo("2");
        BigDecimal omitted = new BigDecimal("1000"); // a cache write the provider may have reported and the deserializer zeroed
        BigDecimal price = new BigDecimal("0.000003");
        BigDecimal measuredZero = conditional.multiply(price).add(writes.multiply(price));
        BigDecimal omittedInsideInput = conditional.subtract(omitted).multiply(price).add(writes.add(omitted).multiply(price));
        assertThat(omittedInsideInput).as("equal prices: the split between the two tranches does not change their subtotal").isEqualByComparingTo(measuredZero);
        BigDecimal writePrice = new BigDecimal("0.00000375");
        assertThat(conditional.subtract(omitted).multiply(price).add(writes.add(omitted).multiply(writePrice)))
                .as("unequal prices: the split matters, so the estimate stays conditional").isNotEqualByComparingTo(conditional.multiply(price).add(writes.multiply(writePrice)));
        assertThat(u.member("measurement_status_id").scalarText()).as("the running total may miss whole responses: any estimate is a lower bound").isEqualTo("4");
        assertThat(component(u, 1).member("measurement_status_id").scalarText()).as("the cached operand of the conditional value is itself a lower bound").isEqualTo("4");
        Path relabelled = copy(CODEX_STREAM, work.resolve("relabelled"));
        editEvents(pkg(relabelled), events -> {
            obj(usage(events)).members().put("measurement_status_id", num("1"));
            obj(component(usage(events), 1)).members().put("measurement_status_id", num("1"));
        });
        assertThat(verify(relabelled).outcome("usage.values", STREAM)).as("statuses that would license an exact bill are refused").isEqualTo(Outcome.DEFECTIVE);
    }

    /**
     * A prompt the capture's caller supplied is recorded with caller provenance, never as something the CLI reported; the
     * same text added to the stream's reported settings is a context value defect, although the text itself is the prompt.
     */
    @Test
    void callerSuppliedDataPresentedAsASourceReportedSettingIsADefect(@TempDir Path work) {
        Path fixture = copy(LIVE_FEATURES, work);
        editEvents(pkg(fixture), events -> {
            Json init = at(events, "stream.jsonl", "session_context", 1, 1);
            obj(init.member("settings")).members().put("prompt", str("the caller-supplied prompt text"));
        });
        OracleReport report = verify(fixture);
        assertThat(report.outcome("context.values", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("context.values", STREAM).detail()).contains("settings.prompt present but the record reports no such setting");
    }
}
