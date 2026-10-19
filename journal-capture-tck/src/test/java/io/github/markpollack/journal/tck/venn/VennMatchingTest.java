package io.github.markpollack.journal.tck.venn;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VennMatchingTest {

    static Leaf n(String path, Object value) {
        return new Leaf("native", path, value, "n:1", false);
    }

    static Leaf c(String path, Object value) {
        return new Leaf("event", path, value, "c:1", false);
    }

    static Leaf kept(String path, Object value) {
        return new Leaf("vendor_event", path, value, "c:1", true);
    }

    static BigDecimal num(String s) {
        return Flattener.normalize(new BigDecimal(s));
    }

    @Test
    void aValueFoundAnywhereOnTheOtherSideLandsAndTheMappingIsReported() {
        VennReport r = Venn.compare(List.of(n("message.content[].text", "the assistant said hello")),
                List.of(c("output", "the assistant said hello")));

        PathResult p = r.path("native", "message.content[].text").orElseThrow();
        assertThat(p.landed()).isEqualTo(1);
        assertThat(p.landedAt()).containsEntry("event output", 1);
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void aValueFoundNowhereIsNeither() {
        VennReport r = Venn.compare(List.of(n("a", "something only the vendor had")), List.of(c("b", "other text entirely")));

        assertThat(r.path("native", "a").orElseThrow().neither()).isEqualTo(1);
        assertThat(r.gaps()).extracting(PathResult::path).containsExactly("a");
    }

    @Test
    void textThatTheJournalJoinedStillLandsBySubstring() {
        VennReport r = Venn.compare(List.of(n("t[]", "first paragraph"), n("t[]", "second paragraph")),
                List.of(c("text", "first paragraph\nsecond paragraph")));

        assertThat(r.path("native", "t[]").orElseThrow().landed()).isEqualTo(2);
    }

    @Test
    void oneCanonicalValueCannotCoverTwoNativeValues() {
        VennReport r = Venn.compare(List.of(n("a", "repeated sentence here"), n("b", "repeated sentence here")),
                List.of(c("only", "repeated sentence here")));

        assertThat(r.count(Landing.LANDED)).isEqualTo(1);
        assertThat(r.count(Landing.NEITHER)).isEqualTo(1);
    }

    @Test
    void twoCanonicalCopiesCoverTwoNativeValues() {
        VennReport r = Venn.compare(List.of(n("a", "repeated sentence here"), n("b", "repeated sentence here")),
                List.of(c("x", "repeated sentence here"), c("y", "repeated sentence here")));

        assertThat(r.count(Landing.LANDED)).isEqualTo(2);
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void aStretchOfTextInsideALongerStringCanBeUsedOnlyOnce() {
        VennReport r = Venn.compare(List.of(n("a", "needle text"), n("b", "needle text")),
                List.of(c("hay", "before needle text after")));

        assertThat(r.count(Landing.LANDED)).isEqualTo(1);
        assertThat(r.count(Landing.NEITHER)).isEqualTo(1);
    }

    @Test
    void theSameTextTwiceInOneCanonicalStringCoversBoth() {
        VennReport r = Venn.compare(List.of(n("a", "needle text"), n("b", "needle text")),
                List.of(c("hay", "needle text and again needle text")));

        assertThat(r.count(Landing.LANDED)).isEqualTo(2);
    }

    @Test
    void shortStringsBooleansAndSmallIntegersProveNothingByValue() {
        VennReport r = Venn.compare(List.of(n("role", "user"), n("ok", true), n("n", num("7")), n("m", num("99"))),
                List.of(c("role", "user"), c("ok", true), c("n", num("7")), c("m", num("99"))));

        assertThat(r.count(Landing.LOW_INFORMATION)).isEqualTo(4);
        assertThat(r.count(Landing.LANDED)).isZero();
        assertThat(r.count(Landing.NEITHER)).isZero();
    }

    @Test
    void numbersMatchExactlyOnceNormalizedAndOnlyWhenTheyCarryInformation() {
        VennReport r = Venn.compare(List.of(n("tokens", num("47555")), n("cost", num("0.0054706")), n("big", num("1000"))),
                List.of(c("in", num("47555.0")), c("cost", num("0.0054706")), c("big", num("1e3"))));

        assertThat(r.count(Landing.LANDED)).isEqualTo(3);
    }

    @Test
    void aNumberOffByOneIsNotAMatch() {
        VennReport r = Venn.compare(List.of(n("tokens", num("47555"))), List.of(c("in", num("47554"))));

        assertThat(r.count(Landing.NEITHER)).isEqualTo(1);
    }

    @Test
    void aTypedFieldIsPreferredToAKeptWholeCopy() {
        VennReport r = Venn.compare(List.of(n("text", "the same long sentence")),
                List.of(kept("payload.text", "the same long sentence"), c("output", "the same long sentence")));

        PathResult p = r.path("native", "text").orElseThrow();
        assertThat(p.landed()).isEqualTo(1);
        assertThat(p.keptWhole()).isZero();
    }

    @Test
    void aValueOnlyInAKeptWholeEventIsCountedSeparately() {
        VennReport r = Venn.compare(List.of(n("text", "the same long sentence")),
                List.of(kept("payload.text", "the same long sentence")));

        PathResult p = r.path("native", "text").orElseThrow();
        assertThat(p.keptWhole()).isEqualTo(1);
        assertThat(p.landedAt()).containsEntry("vendor_event payload.text", 1);
        assertThat(r.gaps()).isEmpty();
    }

    @Test
    void canonicalValuesNoNativeValueAccountedForAreReportedAsUnexplained() {
        VennReport r = Venn.compare(List.of(n("a", "matched text value")),
                List.of(c("out", "matched text value"), c("invented", "a placeholder nobody declared"), c("tiny", "no")));

        assertThat(r.unexplained()).containsOnlyKeys("event invented");
    }

    @Test
    void gapsAreRankedByHowManyValuesAreMissing() {
        VennReport r = Venn.compare(
                List.of(n("rare", "only one missing"), n("common", "missing value one"), n("common", "missing value two"),
                        n("common", "missing value three")),
                List.of());

        assertThat(r.gaps()).extracting(PathResult::path).containsExactly("common", "rare");
    }

    @Test
    void theSameInputGivesTheSameReport() {
        List<Leaf> nat = List.of(n("a", "alpha alpha alpha"), n("b", "beta beta beta"), n("c", num("12345")));
        List<Leaf> can = List.of(c("x", "alpha alpha alpha"), c("y", num("12345")));

        assertThat(Venn.compare(nat, can)).isEqualTo(Venn.compare(nat, can));
    }
}
