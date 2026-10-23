package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX_STREAM;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_FEATURES;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
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
