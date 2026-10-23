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

    static ExpectedFacts.Tranches read(String usage) {
        return TrancheReading.codex(NeutralJson.parse(usage), "", true);
    }

    /**
     * Reproduced at ee0f6e0 on the unchanged profile (plain reported zeros, unconditional derivation). Repaired: the
     * profile's own reading marks the zeros the deserializer also writes for an omitted member Possibly default, every
     * other member of the running total a Lower bound, and keeps the uncached difference only as a conditional value.
     */
    @Test
    void theProfileMarksAPossiblyDefaultedZeroAndItsConditionalDerivation() {
        ExpectedFacts.Tranches t = read("{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":0}");
        assertThat(t.bases()).isEqualTo(Map.of("uncached_input_tokens", 3, "cache_read_tokens", 1, "cache_write_tokens", 1, "output_tokens", 1, "reasoning_tokens", 1));
        assertThat(t.values()).doesNotContainKey("uncached_input_tokens");
        assertThat(t.conditionals().get("uncached_input_tokens").scalarText()).isEqualTo("200");
        assertThat(t.statuses()).isEqualTo(Map.of("uncached_input_tokens", 3, "cache_read_tokens", 4, "cache_write_tokens", 2, "output_tokens", 4, "reasoning_tokens", 2));
    }

    @Test
    void theProfileReadsARequiredZeroAsALowerBoundAndAllZerosAsDefault() {
        assertThat(read("{\"input_tokens\":1200,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,\"output_tokens\":0,\"reasoning_output_tokens\":0}").statuses())
                .isEqualTo(Map.of("uncached_input_tokens", 3, "cache_read_tokens", 2, "cache_write_tokens", 2, "output_tokens", 4, "reasoning_tokens", 2));
        assertThat(read("{\"input_tokens\":0,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,\"output_tokens\":0,\"reasoning_output_tokens\":0}").statuses())
                .isEqualTo(Map.of("uncached_input_tokens", 3, "cache_read_tokens", 2, "cache_write_tokens", 2, "output_tokens", 2, "reasoning_tokens", 2));
    }

    /**
     * Reproduced on the unchanged a7673e3 profile, which read this cache write 60 as Measured. The same running total arises
     * when a second response omitted its cache writes or a third carried no usage, so the profile states a Lower bound.
     */
    @Test
    void theProfileReadsAPositiveRunningTotalAsALowerBoundNotAMeasurement() {
        ExpectedFacts.Tranches t = read("{\"input_tokens\":2000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":60,\"output_tokens\":90,\"reasoning_output_tokens\":10}");
        assertThat(t.values().get("cache_write_tokens").scalarText()).isEqualTo("60");
        assertThat(t.statuses()).isEqualTo(Map.of("cache_read_tokens", 4, "cache_write_tokens", 4, "output_tokens", 4, "reasoning_tokens", 4));
        assertThat(t.conditionals()).isEmpty();
    }

    @Test
    void theProfileLeavesAnUnprovenOrAbsentTrancheWithoutStatus() {
        ExpectedFacts.Tranches writes = read("{\"input_tokens\":1000,\"cached_input_tokens\":800,\"cache_write_input_tokens\":60,\"output_tokens\":50,\"reasoning_output_tokens\":10}");
        assertThat(writes.bases().get("uncached_input_tokens")).isEqualTo(3);
        assertThat(writes.statuses()).doesNotContainKey("uncached_input_tokens").containsEntry("cache_write_tokens", 4);
        ExpectedFacts.Tranches absent = read("{\"input_tokens\":1000,\"cache_write_input_tokens\":0,\"output_tokens\":50,\"reasoning_output_tokens\":5}");
        assertThat(absent.bases()).containsEntry("uncached_input_tokens", 3).doesNotContainKey("cache_read_tokens");
        assertThat(absent.statuses()).doesNotContainKeys("uncached_input_tokens", "cache_read_tokens").containsEntry("cache_write_tokens", 2);
        assertThat(absent.conditionals()).isEmpty();
        // Anthropic usage: no declared rule, so no status at all
        assertThat(TrancheReading.anthropic(NeutralJson.parse("{\"input_tokens\":200,\"cache_read_input_tokens\":800,\"cache_creation_input_tokens\":0,\"output_tokens\":50}")).statuses()).isEmpty();
    }
}
