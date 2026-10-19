package io.github.markpollack.journal.tck.venn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.github.markpollack.journal.tck.venn.VennMatchingTest.num;
import static org.assertj.core.api.Assertions.assertThat;

/** Small values are looked for only inside the canonical record that the rest of their native record landed in. */
class VennAlignmentTest {

    private static Leaf nat(int line, String path, Object value) {
        return new Leaf("native", path, value, "native:" + line, false);
    }

    private static Leaf can(int line, String path, Object value) {
        return new Leaf("event", path, value, "events:" + line, false);
    }

    @Test
    void aSmallValueFoundInTheAlignedCanonicalRecordLands() {
        VennReport r = Venn.compare(
                List.of(nat(1, "id", "toolu_01ABCDEFGH"), nat(1, "name", "Read")),
                List.of(can(5, "id", "toolu_01ABCDEFGH"), can(5, "toolName", "Read")));

        assertThat(r.path("native", "name").orElseThrow().landed()).isEqualTo(1);
        assertThat(r.path("native", "name").orElseThrow().landedAt()).containsEntry("event toolName", 1);
    }

    @Test
    void aSmallValueMissingFromTheAlignedRecordIsNeitherEvenIfAnotherRecordHasIt() {
        VennReport r = Venn.compare(
                List.of(nat(1, "id", "toolu_01ABCDEFGH"), nat(1, "name", "Read")),
                List.of(can(5, "id", "toolu_01ABCDEFGH"), can(6, "toolName", "Read")));

        assertThat(r.path("native", "name").orElseThrow().neither()).isEqualTo(1);
    }

    @Test
    void eachNativeRecordIsAlignedToItsOwnCanonicalRecord() {
        VennReport r = Venn.compare(
                List.of(nat(1, "id", "toolu_AAAAAAAAAA"), nat(1, "name", "Read"),
                        nat(2, "id", "toolu_BBBBBBBBBB"), nat(2, "name", "Bash")),
                List.of(can(5, "id", "toolu_AAAAAAAAAA"), can(5, "toolName", "Read"),
                        can(6, "id", "toolu_BBBBBBBBBB"), can(6, "toolName", "Bash")));

        assertThat(r.gaps()).isEmpty();
        assertThat(r.count(Landing.LANDED)).isEqualTo(4);
    }

    @Test
    void theCanonicalRecordThatReceivedMostValuesWinsTheAlignment() {
        VennReport r = Venn.compare(
                List.of(nat(1, "a", "alpha alpha alpha"), nat(1, "b", "beta beta beta"), nat(1, "c", "gamma gamma gamma"),
                        nat(1, "tag", "Read")),
                List.of(can(5, "a", "alpha alpha alpha"), can(5, "b", "beta beta beta"), can(5, "tag", "Read"),
                        can(6, "c", "gamma gamma gamma"), can(6, "tag", "Bash")));

        assertThat(r.path("native", "tag").orElseThrow().landed()).isEqualTo(1);
        assertThat(r.path("native", "tag").orElseThrow().landedAt()).containsEntry("event tag", 1);
    }

    @Test
    void aCanonicalRecordCoversOnlyOneOfTwoNativeRecordsThatAreTheSame() {
        VennReport r = Venn.compare(
                List.of(nat(1, "id", "toolu_AAAAAAAAAA"), nat(1, "name", "Read"),
                        nat(2, "id", "toolu_AAAAAAAAAA"), nat(2, "name", "Read")),
                List.of(can(5, "id", "toolu_AAAAAAAAAA"), can(5, "toolName", "Read")));

        // the second record's id found no unused canonical copy, so it is missing and the record cannot be aligned
        assertThat(r.count(Landing.LANDED)).isEqualTo(2);
        assertThat(r.count(Landing.NEITHER)).isEqualTo(1);
        assertThat(r.count(Landing.LOW_INFORMATION)).isEqualTo(1);
    }

    @Test
    void booleansAndSmallNumbersAreCheckedTheSameWay() {
        VennReport r = Venn.compare(
                List.of(nat(1, "text", "a sentence of some length"), nat(1, "isError", false), nat(1, "turn", num("3"))),
                List.of(can(5, "text", "a sentence of some length"), can(5, "success", false)));

        assertThat(r.path("native", "isError").orElseThrow().landed()).isEqualTo(1);
        assertThat(r.path("native", "turn").orElseThrow().neither()).isEqualTo(1);
    }

    @Test
    void aRecordWithNothingMatchableCannotBeAlignedAndStaysLowInformation() {
        VennReport r = Venn.compare(List.of(nat(1, "role", "user"), nat(1, "sidechain", false)),
                List.of(can(5, "role", "user")));

        assertThat(r.count(Landing.LOW_INFORMATION)).isEqualTo(2);
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void aValueKeptInAVendorEventOfTheAlignedRecordIsCountedAsKeptWhole() {
        VennReport r = Venn.compare(
                List.of(nat(1, "text", "a sentence of some length"), nat(1, "role", "assistant")),
                List.of(new Leaf("vendor_event", "payload.text", "a sentence of some length", "events:9", true),
                        new Leaf("vendor_event", "payload.role", "assistant", "events:9", true)));

        assertThat(r.count(Landing.KEPT_WHOLE)).isEqualTo(2);
        assertThat(r.gaps()).isEmpty();
    }
}
