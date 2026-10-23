package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The oracle profile's own reading of Codex exec-stream usage components, independent of the writer. At rust-v0.160.1 an
 * omitted provider {@code cache_write_tokens}, {@code input_tokens_details} or {@code output_tokens_details} becomes a 0
 * in the printed usage, so a zero in cache writes, cache reads or reasoning does not establish a measured zero.
 */
class UsageComponentMeasurementProfileTest {

    /**
     * Reproduction on the unchanged profile: it reads the possibly defaulted zeros as plain reported components and the
     * uncached tranche as an unconditional derivation, agreeing with the writer, so neither side can tell the two source
     * situations apart.
     */
    @Test
    void reproductionTheProfileReadsAPossiblyDefaultedZeroAsAPlainReportedComponent() {
        ExpectedFacts.Tranches t = TrancheReading.codex(NeutralJson.parse(
                "{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0}"), "");
        assertThat(t.bases()).isEqualTo(Map.of("uncached_input_tokens", 2, "cache_read_tokens", 1, "cache_write_tokens", 1, "output_tokens", 1, "reasoning_tokens", 1));
        assertThat(t.values().get("uncached_input_tokens").scalarText()).isEqualTo("200");
    }
}
