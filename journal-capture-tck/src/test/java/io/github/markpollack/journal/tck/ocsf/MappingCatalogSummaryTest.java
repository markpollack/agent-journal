package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catalog's coverage summary (one state per concept and route) must not report {@code verified} unless every
 * obligation the concept places on the route's independently known population holds: each listed property, for each
 * owner the frozen expected sources establish (the root and every expected child core, at the scope the oracle
 * reports the property), with an occurrence of the concept's scenario in the native source. These controls take the
 * independent oracle's real report on a committed fixture and remove, misattribute or degrade single results.
 *
 * <p>Owners here are the oracle's: {@code main/stream.jsonl} for the root core's stream and
 * {@code child:<spawn id>/stream.jsonl} for the sub-agent core split from the same stream. Expected owners come from
 * the fixture's {@code expected-sources.json}, never from the checks that survive in the report.
 */
class MappingCatalogSummaryTest {

    static final MappingCatalogTest.Route CHILD_ROUTE = MappingCatalogTest.ROUTES.get(0);
    static final MappingCatalogTest.Route LIVE_FEATURES_ROUTE = MappingCatalogTest.ROUTES.get(1);
    static final MappingCatalogTest.Route LIVE_DENIAL_ROUTE = MappingCatalogTest.ROUTES.get(2);
    static final String MAIN = "main/stream.jsonl";
    static final String CHILD = "child:" + OcsfFixtures.SPAWN + "/stream.jsonl";

    static Json concept(String id) {
        for (Json e : MappingCatalogTest.catalog()) {
            if (id.equals(SchemaCatalog.text(e, "id"))) {
                return e;
            }
        }
        throw new AssertionError("no catalog concept " + id);
    }

    record Run(Path fixture, OracleReport report) {
    }

    static Run run(MappingCatalogTest.Route route, Path work) {
        Path fixture = OcsfFixtures.copy(route.fixture(), work.resolve(route.fixture()));
        return new Run(fixture, OcsfFixtures.verify(fixture));
    }

    static String summary(String concept, MappingCatalogTest.Route route, Run run, OracleReport report) {
        return MappingCatalogTest.summarize(concept(concept), route, run.fixture(), report);
    }

    static boolean is(Check c, String owner, String... properties) {
        return c.owner().equals(owner) && Set.of(properties).contains(c.property());
    }

    @Test
    void controlEveryPropertyForEveryExpectedOwnerVerifies(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        assertThat(run.report().check("request.values", MAIN).outcome()).isEqualTo(Outcome.VERIFIED);
        assertThat(run.report().check("request.values", CHILD).outcome()).isEqualTo(Outcome.VERIFIED);
        assertThat(summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, run.report())).isEqualTo("verified");
    }

    @Test
    void aMissingRequiredPropertyBlocksVerificationAndIsNamed(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        OracleReport dropped = run.report().filtered(c -> !c.property().equals("request.values"));
        assertThat(dropped.check("population.requests", MAIN).outcome()).isEqualTo(Outcome.VERIFIED);
        String state = summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, dropped);
        assertThat(state).startsWith("blocked").contains("request.values [" + MAIN + "]").contains("request.values [" + CHILD + "]");
    }

    @Test
    void aResultForAnUnrelatedOwnerCannotEstablishTheTargetOwnersObligation(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        // the root core's request checks are gone; the child core's identical property names remain VERIFIED
        OracleReport misattributed = run.report().filtered(c -> !is(c, MAIN, "population.requests", "request.values"));
        assertThat(misattributed.check("request.values", CHILD).outcome()).isEqualTo(Outcome.VERIFIED);
        String state = summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, misattributed);
        assertThat(state).startsWith("blocked").contains("population.requests [" + MAIN + "]").contains("request.values [" + MAIN + "]")
                .doesNotContain("[" + CHILD + "]");
    }

    @Test
    void anOmittedExpectedOwnerCannotShrinkTheDenominator(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        // every check of the expected child core is absent: the summary must not judge the root alone
        OracleReport childless = run.report().filtered(c -> !c.owner().startsWith("child:"));
        String state = summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, childless);
        assertThat(state).startsWith("blocked").contains("[" + CHILD + "]").doesNotContain("[" + MAIN + "]");
    }

    @Test
    void mixedResultsDefectsDominateThenMissingThenUnresolved(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        OracleReport defect = run.report().replaced(c -> is(c, CHILD, "request.values"),
                c -> new Check(c.category(), c.property(), c.owner(), Outcome.DEFECTIVE, "tool_name differs"));
        assertThat(summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, defect)).startsWith("defective");
        OracleReport unresolved = run.report().replaced(c -> is(c, CHILD, "request.values"),
                c -> new Check(c.category(), c.property(), c.owner(), Outcome.UNRESOLVED, "reference not resolved"));
        assertThat(summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, unresolved)).startsWith("unresolved").contains("request.values");
        // a missing result next to an unresolved one: the missing obligation blocks, it is not reported as merely unresolved
        OracleReport both = unresolved.filtered(c -> !is(c, MAIN, "request.values"));
        assertThat(summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, both)).startsWith("blocked").contains("request.values [" + MAIN + "]");
        // a good sibling property cannot hide a defect in another owner's population
        OracleReport populationDefect = run.report().replaced(c -> is(c, CHILD, "population.requests"),
                c -> new Check(c.category(), c.property(), c.owner(), Outcome.DEFECTIVE, "1 expected, 0 observed"));
        assertThat(summary("tool-request-identity-and-arguments", CHILD_ROUTE, run, populationDefect)).startsWith("defective");
    }

    @Test
    void zeroApplicableOccurrencesAreUnexercisedButADroppedCheckIsNot(@TempDir Path work) {
        Run run = run(LIVE_DENIAL_ROUTE, work);
        // the denial recording spawns no sub-agent: the root's package.children check reports no occurrence
        assertThat(run.report().check("package.children", "main").outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(summary("child-runs", LIVE_DENIAL_ROUTE, run, run.report())).startsWith("unexercised");
        OracleReport dropped = run.report().filtered(c -> !c.property().equals("package.children"));
        assertThat(summary("child-runs", LIVE_DENIAL_ROUTE, run, dropped)).startsWith("blocked").contains("package.children [main]");
    }

    @Test
    void aBroadPropertyPassingIsNotAnOccurrenceOfTheConceptsScenario(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        // response.values is VERIFIED on this fixture, but its stream carries no redacted reasoning, media or unknown block,
        // and the run is complete: none of these concepts was exercised by it
        assertThat(run.report().check("response.values", MAIN).outcome()).isEqualTo(Outcome.VERIFIED);
        assertThat(summary("redacted-or-encrypted-reasoning", CHILD_ROUTE, run, run.report())).startsWith("unexercised");
        assertThat(summary("media-and-multimodal-blocks", CHILD_ROUTE, run, run.report())).startsWith("unexercised");
        assertThat(summary("unknown-block-kinds", CHILD_ROUTE, run, run.report())).startsWith("unexercised");
        assertThat(summary("interrupted-run-and-never-observed-result", CHILD_ROUTE, run, run.report())).startsWith("unexercised");
        // the thinking blocks the stream does carry still count
        assertThat(summary("reasoning-blocks-and-signature", CHILD_ROUTE, run, run.report())).isEqualTo("verified");
    }

    @Test
    void aDeclaredGlobalInvariantIsJudgedOnceAtItsDeclaredScope(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        // package.children is reported by the root core only; the child core is not asked to report it
        assertThat(run.report().check("package.children", "main").outcome()).isEqualTo(Outcome.VERIFIED);
        assertThat(run.report().checks()).noneMatch(c -> c.property().equals("package.children") && c.owner().startsWith("child:"));
        assertThat(summary("child-runs", CHILD_ROUTE, run, run.report())).isEqualTo("verified");
    }

    @Test
    void retainedOnlyNeedsTheRetentionEvidenceNotJustTheLabel(@TempDir Path work) {
        Run run = run(CHILD_ROUTE, work);
        assertThat(summary("retained-only-known-meaning-families", CHILD_ROUTE, run, run.report())).startsWith("retained-only");
        OracleReport noRetention = run.report().filtered(c -> !is(c, MAIN, "whole_record.class"));
        assertThat(summary("retained-only-known-meaning-families", CHILD_ROUTE, run, noRetention)).startsWith("blocked")
                .contains("whole_record.class [" + MAIN + "]");
        OracleReport lost = run.report().replaced(c -> is(c, MAIN, "whole_record.coverage"),
                c -> new Check(c.category(), c.property(), c.owner(), Outcome.DEFECTIVE, "record 7 not retained"));
        assertThat(summary("retained-only-known-meaning-families", CHILD_ROUTE, run, lost)).startsWith("defective");
    }

    @Test
    void aSourceAvailabilityClaimIsScopedToItsNamedRoute(@TempDir Path work) {
        // an absence recorded on the 2.1.299 sdk recording; the 2.1.304 stdout recording shares the artifact name only
        Json entry = NeutralJson.parse("""
                {"id": "scoped-availability-control", "concept": "a field the 2.1.299 sdk stream lacks", "disposition": "normalized",
                 "oracle": [], "native_evidence": [{"cli": "claude", "artifact": "stream.jsonl", "route": "claude-code/stream-json/2.1.299-sdk",
                 "records": "1-27", "field": "no such field", "availability": "absent"}]}
                """);
        Run old = run(CHILD_ROUTE, work.resolve("old"));
        assertThat(MappingCatalogTest.summarize(entry, CHILD_ROUTE, old.fixture(), old.report())).isEqualTo("unavailable-at-source");
        Run live = run(LIVE_FEATURES_ROUTE, work.resolve("live"));
        assertThat(MappingCatalogTest.summarize(entry, LIVE_FEATURES_ROUTE, live.fixture(), live.report())).startsWith("unresolved")
                .contains("claude-code/stream-json/2.1.304-stdout");
    }
}
