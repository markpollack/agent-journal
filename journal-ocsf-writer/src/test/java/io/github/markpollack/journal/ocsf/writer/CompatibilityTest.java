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
        assertThat(c.entries()).hasSize(7);
        MappingContracts contracts = new MappingContracts();
        for (Compatibility.Entry e : c.entries()) {
            assertThat(contracts.uid(e.contract())).as(e.id() + " contract resource").isNotBlank();
            assertThat(contracts.contract(e.contract()).path("applies_to", "compatibility_entries").elements()).extracting(Jv::text).as(e.id() + " contract names its entry").contains(e.id());
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
        assertThat(low.entry().id()).isEqualTo("claude-code/stream-json/2.1.299-sdk");
        Compatibility.Selection mid = c.select(CLAUDE, "claude_stream", "sdk", "2.1.303");
        assertThat(mid.status()).isEqualTo(Compatibility.Status.TESTED);
        assertThat(mid.entry().id()).isEqualTo("claude-code/stream-json/2.1.303-sdk");
        Compatibility.Selection high = c.select(CLAUDE, "claude_stream", "stdout", "2.1.304");
        assertThat(high.status()).isEqualTo(Compatibility.Status.TESTED);
        assertThat(high.entry().id()).isEqualTo("claude-code/stream-json/2.1.304-stdout");
        assertThat(high.entry()).as("one entry per exactly tested combination").isNotSameAs(low.entry()).isNotSameAs(mid.entry());
        assertThat(high.entry().contract()).as("the three exact entries share the one reviewed contract and mapper").isEqualTo(low.entry().contract()).isEqualTo(mid.entry().contract());
        Compatibility.Selection codex = c.select(CODEX, "codex_stream", "stdout", "0.160.1");
        assertThat(codex.status()).isEqualTo(Compatibility.Status.TESTED);
        assertThat(codex.entry().id()).isEqualTo("codex-cli/exec-json-stream/0.160.1");
        assertThat(codex.normalizes()).isTrue();
        assertThat(codex.contractName("codex-cli-0.160.1-exec-stream")).isEqualTo("codex-cli-0.160.1-exec-stream");
    }

    /** Owner disposition 4 (2026-10-21): the bundled registry names exact tested combinations only; an untested neighbour or another route is no match. */
    @Test
    void anUntestedIntermediateVersionOrAnotherRouteIsNoMatchInTheBundledRegistry() {
        Compatibility c = Compatibility.bundled();
        for (String[] v : new String[][] {{"sdk", "2.1.300"}, {"sdk", "2.1.301"}, {"sdk", "2.1.302"}, {"stdout", "2.1.299"}, {"stdout", "2.1.303"}, {"sdk", "2.1.304"}, {"stdout", "2.1.301"}}) {
            Compatibility.Selection s = c.select(CLAUDE, "claude_stream", v[0], v[1]);
            assertThat(s.status()).as(v[1] + " on " + v[0]).isEqualTo(Compatibility.Status.NO_MATCH);
            assertThat(s.entry()).isNull();
            assertThat(s.detail()).as(v[1] + " on " + v[0]).contains("nearest entry is not selected").contains("route " + v[0]);
        }
        assertThat(c.select(CLAUDE, "claude_stream", "stdout", "2.1.299").detail()).as("the keyed entries on stdout").contains("claude-code/stream-json/2.1.304-stdout [2.1.304, 2.1.304]").doesNotContain("2.1.299-sdk");
        assertThat(c.select(CLAUDE, "claude_stream", "sdk", "2.1.301").detail()).contains("claude-code/stream-json/2.1.299-sdk [2.1.299, 2.1.299]").contains("claude-code/stream-json/2.1.303-sdk [2.1.303, 2.1.303]");
        for (Compatibility.Entry e : c.entries()) {
            assertThat(e.minVersion()).as(e.id() + " is one exactly tested version").isEqualTo(e.maxVersion());
            assertThat(e.routes()).as(e.id() + " names one route").hasSize(1);
            assertThat(e.tested(e.minVersion(), e.routes().get(0))).as(e.id() + " is tested on its route").isTrue();
        }
    }

    /** A reviewed range remains expressible: a version inside it that no tested entry names is in_range, and a tested version on another route of the same entry is in_range too. */
    @Test
    void aVersionInsideAReviewedRangeThatNoTestedEntryNamesIsInRangeNotTested() {
        Compatibility c = Compatibility.of(Jv.parse(registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout","sdk"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[{"version":"1.0","route":"stdout"}]}""")));
        Compatibility.Selection s = c.select("X", "p", "stdout", "1.2");
        assertThat(s.status()).isEqualTo(Compatibility.Status.IN_RANGE);
        assertThat(s.entry().id()).isEqualTo("a");
        assertThat(s.detail()).contains("not individually tested");
        Compatibility.Selection otherRoute = c.select("X", "p", "sdk", "1.0");
        assertThat(otherRoute.status()).as("1.0 is tested on stdout only").isEqualTo(Compatibility.Status.IN_RANGE);
        assertThat(otherRoute.detail()).contains("another route");
        assertThat(c.select("X", "p", "stdout", "1.0").status()).isEqualTo(Compatibility.Status.TESTED);
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
            assertThat(s.detail()).contains("nearest entry is not selected").contains("claude-code/stream-json/2.1.304-stdout [2.1.304, 2.1.304]");
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
        assertThat(s.detail()).contains("claude-code/stream-json/2.1.304-stdout [2.1.304, 2.1.304]").doesNotContain("-sdk");
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
    void everyOtherMisconfigurationIsRejectedWhenTheRegistryLoads() {
        String noRoutes = registry("""
                {"id":"a","cli":"X","profile":"p","routes":[],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(noRoutes))).hasMessageContaining("a: no routes");
        String noSchema = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(noSchema))).hasMessageContaining("source_specification and schema are required");
        String testedOffRoute = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[{"version":"1.2","route":"sdk"}]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(testedOffRoute))).hasMessageContaining("tested route sdk is not one of the entry's routes [stdout]");
        String blankId = registry("""
                {"id":"","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(blankId))).hasMessageContaining("lacks id");
        String missingMapper = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","tested":[]}""");
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(missingMapper))).hasMessageContaining("entry a lacks mapper");
        String scopedTwice = "{\"kind\":\"agent-journal/compatibility-registry\",\"version\":\"0.1\",\"entries\":["
                + "{\"id\":\"a\",\"cli\":\"X\",\"profile\":\"opaque\",\"routes\":[\"stdout\"],\"min_version\":\"1.0\",\"max_version\":\"1.5\",\"source_specification\":{},\"schema\":{},\"contract\":\"c\",\"mapper\":\"m\",\"tested\":[]}],"
                + "\"not_version_scoped\":{\"opaque\":\"bytes only\"}}";
        assertThatThrownBy(() -> Compatibility.of(Jv.parse(scopedTwice))).hasMessageContaining("declared not version-scoped and cannot also have an entry");
        String disjoint = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]},
                {"id":"b","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.6","max_version":"2.0","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThat(Compatibility.of(Jv.parse(disjoint)).entries()).as("disjoint ranges on one route").hasSize(2);
        String otherCli = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]},
                {"id":"b","cli":"Y","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[]}""");
        assertThat(Compatibility.of(Jv.parse(otherCli)).entries()).as("the same range under another CLI does not overlap").hasSize(2);
        String noTestedMember = registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m"}""");
        assertThat(Compatibility.of(Jv.parse(noTestedMember)).entries().get(0).tested()).as("an absent tested member is an empty list").isEmpty();
    }

    @Test
    void theDeclarationStatesEachStatusAndOnlyWhatTheSelectionKnows() {
        Compatibility c = Compatibility.bundled();
        Jv.Obj tested = Compatibility.declaration(c.select(CLAUDE, "claude_stream", "stdout", "2.1.304"), "stream.jsonl", "reported", 0, "abc");
        assertThat(tested.get("status").text()).isEqualTo("tested");
        assertThat(tested.get("normalizes").text()).isEqualTo("yes");
        assertThat(tested.get("entry_id").text()).isEqualTo("claude-code/stream-json/2.1.304-stdout");
        assertThat(tested.get("min_version").text()).isEqualTo("2.1.304");
        assertThat(tested.get("note").text()).contains("exact tested combination");
        assertThat(tested.get("tested").elements()).hasSize(1);
        assertThat(tested.get("schema_sha256_in_use").text()).isEqualTo("abc");
        assertThat(Jv.write(tested.get("records_before_selection"))).isEqualTo("0");

        Jv.Obj unknown = Compatibility.declaration(c.select(CLAUDE, "claude_stream", "stdout", null), "stream.jsonl", "not known at launch", 0, "abc");
        assertThat(unknown.get("status").text()).isEqualTo("version_unknown");
        assertThat(unknown.get("version").isNull()).isTrue();
        assertThat(unknown.get("normalizes").text()).startsWith("provisionally");
        assertThat(unknown.get("entry_id")).isNull();

        Jv.Obj noMatch = Compatibility.declaration(c.select(CLAUDE, "claude_stream", null, "2.1.304"), "stream.jsonl", "reported", 3, "abc");
        assertThat(noMatch.get("status").text()).isEqualTo("no_match");
        assertThat(noMatch.get("route").text()).isEqualTo("unspecified");
        assertThat(noMatch.get("normalizes").text()).isEqualTo("no");
        assertThat(Jv.write(noMatch.get("records_before_selection"))).isEqualTo("3");

        Jv.Obj opaque = Compatibility.declaration(c.select(CLAUDE, "opaque_text", "stdout", "2.1.304"), "notes.txt", "reported", 0, "abc");
        assertThat(opaque.get("status").text()).isEqualTo("not_version_scoped");
        assertThat(opaque.get("normalizes").text()).isEqualTo("no");

        Compatibility noNote = Compatibility.of(Jv.parse(registry("""
                {"id":"a","cli":"X","profile":"p","routes":["stdout"],"min_version":"1.0","max_version":"1.5","source_specification":{},"schema":{},"contract":"c","mapper":"m","tested":[{"version":"1.0","route":"stdout"}]}""")));
        Jv.Obj inRange = Compatibility.declaration(noNote.select("X", "p", "stdout", "1.2"), "s", "reported", 0, "abc");
        assertThat(inRange.get("status").text()).isEqualTo("in_range");
        assertThat(inRange.get("normalizes").text()).isEqualTo("yes");
        assertThat(inRange.get("note")).as("no note is declared when the entry has none").isNull();
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
