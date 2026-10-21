package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The compatibility registry's selection rule (RULE-10, RULE-33): exact and bounded matches, inclusive boundary
 * versions, one unique match, rejection of overlapping or ambiguous entries at load, no match outside every range
 * (the nearest entry is not selected), an unknown version, a profile outside version scoping, and the bundled
 * registry's own consistency with the contracts and mappers it names.
 */
class CompatibilityTest {

    static final String CLAUDE = "Claude Code";
    static final String CODEX = "Codex CLI";

    @Test
    void theBundledRegistryLoadsAndNamesContractsMappersAndSpecificationsThatExist() {
        Compatibility c = Compatibility.bundled();
        assertThat(c.entries()).hasSize(4);
        MappingContracts contracts = new MappingContracts();
        for (Compatibility.Entry e : c.entries()) {
            assertThat(contracts.uid(e.contract())).as(e.id() + " contract resource").isNotBlank();
            assertThat(contracts.contract(e.contract()).path("applies_to", "compatibility_entry").text()).as(e.id() + " contract names its entry").isEqualTo(e.id());
            assertThatCode(() -> Class.forName(e.mapper())).as(e.id() + " mapper class").doesNotThrowAnyException();
            assertThat(SourceMapper.forProfile(e.profile()).contractName()).as(e.id() + " profile's mapper executes the entry's contract").isEqualTo(e.contract());
            assertThat(e.schema().member("compiled_sha256").text()).as(e.id() + " schema is the pinned compiled schema").isEqualTo(SchemaPin.bundled().compiledSha256());
            assertThat(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("..").resolve(e.sourceSpecification().member("path").text()))).as(e.id() + " source specification exists").isTrue();
            assertThat(e.tested()).as(e.id() + " has tested evidence").isNotEmpty();
        }
        assertThat(contracts.uid(Compatibility.UNTESTED_CONTRACT)).isEqualTo("agent-journal/untested-compatibility/0.1");
    }

    private static org.assertj.core.api.ThrowableAssert.ThrowingCallable asCallable(Runnable r) {
        return r::run;
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable c) {
        return org.assertj.core.api.Assertions.assertThatCode(c);
    }

    @Test
    void exactTestedVersionsAndInclusiveBoundariesSelectTheOneEntry() {
        Compatibility c = Compatibility.bundled();
        Compatibility.Selection low = c.select(CLAUDE, "claude_stream", "sdk", "2.1.299");
        assertThat(low.status()).isEqualTo(Compatibility.Status.TESTED);
        assertThat(low.entry().id()).isEqualTo("claude-code/stream-json/2.1.299");
        Compatibility.Selection high = c.select(CLAUDE, "claude_stream", "stdout", "2.1.304");
        assertThat(high.status()).as("the inclusive maximum").isEqualTo(Compatibility.Status.TESTED);
        assertThat(high.entry()).isSameAs(low.entry());
        Compatibility.Selection codex = c.select(CODEX, "codex_stream", "stdout", "0.160.1");
        assertThat(codex.status()).isEqualTo(Compatibility.Status.TESTED);
        assertThat(codex.entry().id()).isEqualTo("codex-cli/exec-json-stream/0.160.1");
        assertThat(codex.normalizes()).isTrue();
        assertThat(codex.contractName("codex-cli-0.160.1-exec-stream")).isEqualTo("codex-cli-0.160.1-exec-stream");
    }

    @Test
    void aVersionInsideTheRangeThatNoTestedEntryNamesIsInRangeNotTested() {
        Compatibility c = Compatibility.bundled();
        Compatibility.Selection s = c.select(CLAUDE, "claude_stream", "sdk", "2.1.301");
        assertThat(s.status()).isEqualTo(Compatibility.Status.IN_RANGE);
        assertThat(s.entry().id()).isEqualTo("claude-code/stream-json/2.1.299");
        assertThat(s.detail()).contains("not individually tested");
        Compatibility.Selection otherRoute = c.select(CLAUDE, "claude_stream", "stdout", "2.1.299");
        assertThat(otherRoute.status()).as("2.1.299 is tested on the sdk route only").isEqualTo(Compatibility.Status.IN_RANGE);
        assertThat(otherRoute.detail()).contains("another route");
    }

    @Test
    void outsideEveryRangeOrOffEveryKeyIsNoMatchAndTheNearestEntryIsNotSelected() {
        Compatibility c = Compatibility.bundled();
        for (String v : new String[] {"2.1.298", "2.1.305", "2.2.0", "3.0.0", "1.9.99"}) {
            Compatibility.Selection s = c.select(CLAUDE, "claude_stream", "stdout", v);
            assertThat(s.status()).as(v).isEqualTo(Compatibility.Status.NO_MATCH);
            assertThat(s.entry()).isNull();
            assertThat(s.normalizes()).isFalse();
            assertThat(s.contractName("claude-code-2.1.303-stream")).isEqualTo(Compatibility.UNTESTED_CONTRACT);
            assertThat(s.detail()).contains("nearest entry is not selected").contains("[2.1.299, 2.1.304]");
        }
        assertThat(c.select(CODEX, "codex_stream", "stdout", "0.160.2").status()).isEqualTo(Compatibility.Status.NO_MATCH);
        assertThat(c.select(CODEX, "codex_stream", "stdout", "0.160.0").status()).isEqualTo(Compatibility.Status.NO_MATCH);
        // a route the entry does not cover is a no match even at a tested version
        Compatibility.Selection route = c.select(CODEX, "codex_stream", "sdk", "0.160.1");
        assertThat(route.status()).isEqualTo(Compatibility.Status.NO_MATCH);
        assertThat(route.detail()).contains("no entry is keyed on");
        assertThat(c.select(CLAUDE, "claude_stream", null, "2.1.304").status()).as("an unspecified route").isEqualTo(Compatibility.Status.NO_MATCH);
        assertThat(c.select("Other CLI", "claude_stream", "stdout", "2.1.304").status()).as("another product under the same profile").isEqualTo(Compatibility.Status.NO_MATCH);
    }

    @Test
    void anUnknownVersionIsPendingAndAnUnscopedProfileHasNoEntry() {
        Compatibility c = Compatibility.bundled();
        Compatibility.Selection s = c.select(CLAUDE, "claude_stream", "stdout", null);
        assertThat(s.status()).isEqualTo(Compatibility.Status.VERSION_UNKNOWN);
        assertThat(s.entry()).isNull();
        assertThat(s.normalizes()).as("records are mapped provisionally and retained whole until the stream reports the version").isTrue();
        assertThat(s.detail()).contains("claude-code/stream-json/2.1.299 [2.1.299, 2.1.304]");
        Compatibility.Selection opaque = c.select(CLAUDE, "opaque_text", "stdout", "2.1.304");
        assertThat(opaque.status()).isEqualTo(Compatibility.Status.NOT_VERSION_SCOPED);
    }

    @Test
    void overlappingEntriesAreRejectedWhenTheRegistryLoads() {
        String two = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]},
                {"id":"b","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.5","max_version":"2.0","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(two))).isInstanceOf(IllegalStateException.class).hasMessageContaining("overlap").hasMessageContaining("1.5");
        String disjointRoutes = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]},
                {"id":"b","cli":"X","profile":"p","routes":["sdk"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThat(Compatibility.of(Jv.parse(disjointRoutes)).entries()).as("same range on different routes does not overlap").hasSize(2);
        String inverted = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"2.0","max_version":"1.0","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(inverted))).hasMessageContaining("above max_version");
        String testedOutside = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[{"version":"1.6","route":"stdout"}]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(testedOutside))).hasMessageContaining("outside");
        String duplicate = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]},
                {"id":"a","cli":"Y","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(duplicate))).hasMessageContaining("duplicate entry id");
    }

    @Test
    void versionsCompareNumericallyPerComponent() {
        assertThat(Compatibility.compare("2.1.299", "2.1.304")).isNegative();
        assertThat(Compatibility.compare("2.1.304", "2.1.304")).isZero();
        assertThat(Compatibility.compare("2.1.1000", "2.1.999")).as("numeric, not lexical").isPositive();
        assertThat(Compatibility.compare("2.1", "2.1.0")).isZero();
        assertThat(Compatibility.compare("0.160.1", "0.9.9")).isPositive();
    }

    private static String registry(String entries) {
        return "{\"kind\":\"agent-journal/compatibility-registry\",\"version\":\"0.1\",\"entries\":[" + entries + "]}";
    }
}
