package io.github.markpollack.journal.tck.ocsf;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;

/**
 * A numeric source time (epoch milliseconds) is expected in the text the writer records for it: RFC 3339 UTC with exactly
 * three fraction digits, also on a whole second, where {@code Instant.toString()} would drop the fraction.
 */
class SourceFactsTimeTest {

    @Test
    void aWholeSecondKeepsItsThreeFractionDigits() {
        ExpectedFacts f = new ExpectedFacts("events.jsonl");
        SourceFacts.time(f, 55, new Json.JNumber("1794934509000"));
        assertThat(f.nativeTime.get(55)).isEqualTo("2026-11-17T16:55:09.000Z");
        assertThat(SourceFacts.millisTime(1794934509000L)).isEqualTo("2026-11-17T16:55:09.000Z");
    }

    @Test
    void otherMillisecondsAndTextAreUnchanged() {
        ExpectedFacts f = new ExpectedFacts("events.jsonl");
        SourceFacts.time(f, 1, new Json.JNumber("1794934509120"));
        SourceFacts.time(f, 2, new Json.JString("2026-11-17T16:55:09Z"));
        assertThat(f.nativeTime.get(1)).isEqualTo("2026-11-17T16:55:09.120Z");
        assertThat(f.nativeTime.get(2)).isEqualTo("2026-11-17T16:55:09Z");
    }
}
