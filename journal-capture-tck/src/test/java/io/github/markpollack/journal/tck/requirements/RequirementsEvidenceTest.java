package io.github.markpollack.journal.tck.requirements;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.judge.ai.requirements.EarsRequirement;
import io.github.markpollack.judge.ai.requirements.Rfc2119Requirement;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import io.github.markpollack.judge.requirement.Requirement;
import io.github.markpollack.judge.serialization.ResultJson;
import io.github.markpollack.journal.tck.ocsf.ExpectedSources;
import io.github.markpollack.journal.tck.ocsf.OcsfOracle;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.requirements.RequirementDocuments.Document;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One question per criterion: <em>does the independent oracle, run on the committed public fixtures, establish this
 * criterion's observable behavior for those fixtures?</em>
 *
 * <p>The answer is computed, never canned, and never from a subset. A criterion names the oracle properties that
 * embody it and the scenario the fixtures must exercise ({@link Evidence}). The obligations are established
 * independently of the report: every applicable fixture (one whose frozen {@code expected-sources.json} exercises
 * the scenario) must carry a check for every named property on every owner the frozen manifest implies (the root
 * core, each expected split core, and each expected artifact under each of them). Then:
 *
 * <ul>
 * <li>a configured (fixture, property, owner) check missing from the report is {@code ERROR}: an incomplete
 * instrument, never a pass;</li>
 * <li>an observed {@code DEFECTIVE} check is {@code FAIL};</li>
 * <li>an {@code UNRESOLVED} or {@code UNSUPPORTED} check (outside the outcomes a criterion explicitly accepts) is
 * {@code ABSTAIN}: not established;</li>
 * <li>no fixture exercising the named scenario, or only {@code NOT_APPLICABLE} checks (no occurrence on these
 * fixtures), is {@code ABSTAIN}: not assessed;</li>
 * <li>otherwise {@code PASS}, scoped to the fixtures and owners that actually carried an occurrence.</li>
 * </ul>
 *
 * A criterion with no {@link Evidence} is {@code ABSTAIN}: not assessed. Every rule in {@code rules.md} is likewise
 * {@code ABSTAIN} except the one with an executable file check. Two criteria about this reporter itself (UC5-AC9,
 * UC5-AC12) are established by negative controls computed in the same run, and say so.
 *
 * <p>What a {@code PASS} here means: the behavior held on those fixtures under the oracle's definition. It is not
 * live-capture evidence, not universal vendor coverage, and not a compliance verdict for the product.
 *
 * <p>The per-requirement judgments are written to {@code target/requirements-evidence/} with Agent Judge's result
 * serializers so the parts survive and can be re-aggregated; nothing in this module reads them back.
 */
class RequirementsEvidenceTest {

    static final String CODEX_STREAM = "syn-codex-exec-stream";
    static final String CLAUDE_CHILD = "claude-child-2.1.299";
    static final String CLAUDE_INTERRUPTED = "claude-child-2.1.299-interrupted";
    static final String CLAUDE_UNTESTED = "syn-claude-untested-version";
    static final String LIVE_FEATURES = "claude-live-2.1.304-features";
    static final String LIVE_DENIAL = "claude-live-2.1.304-denial";
    static final String LIVE_CANCELLED = "claude-live-2.1.304-cancelled";
    static final String LIVE_CODEX = "codex-live-0.160.1-commands";
    static final String LIVE_CODEX_CANCELLED = "codex-live-0.160.1-cancelled";
    static final List<String> FIXTURES = List.of(CODEX_STREAM, CLAUDE_CHILD, CLAUDE_INTERRUPTED, CLAUDE_UNTESTED, LIVE_FEATURES, LIVE_DENIAL, LIVE_CANCELLED, LIVE_CODEX, LIVE_CODEX_CANCELLED);

    /** The scenario a criterion's fixtures must exercise, decided from each fixture's frozen manifest, not from the report. */
    enum Scenario {
        ANY("any committed fixture"),
        INTERRUPTED_SOURCE("a fixture whose frozen expected sources declare an incomplete run (a source interrupted or cut)"),
        SPLIT_CHILD("a fixture whose frozen expected sources declare a split child core"),
        UNTESTED_VERSION("a fixture whose frozen expected sources declare a source whose CLI version, profile or route matches no compatibility entry"),
        MATCHED_COMPATIBILITY("a fixture whose frozen expected sources declare no untested-version source (every source is expected to select an entry)");

        final String description;

        Scenario(String description) {
            this.description = description;
        }

        boolean exercisedBy(ExpectedSources frozen) {
            return switch (this) {
                case ANY -> true;
                case INTERRUPTED_SOURCE -> !frozen.complete();
                case SPLIT_CHILD -> !frozen.children().isEmpty();
                case UNTESTED_VERSION -> frozen.sources().stream().anyMatch(ExpectedSources.Source::expectsNoCompatibilityMatch);
                case MATCHED_COMPATIBILITY -> frozen.sources().stream().noneMatch(ExpectedSources.Source::expectsNoCompatibilityMatch);
            };
        }
    }

    /**
     * What a criterion needs: every named property on every applicable owner of every fixture that exercises the
     * scenario, each with an accepted outcome. {@code VERIFIED} (and {@code REPRESENTATION_DIFFERS}, which is value
     * preservation with a spelling difference) are accepted everywhere; a criterion whose required behavior is an
     * honest refusal may additionally accept {@code UNSUPPORTED}.
     */
    record Evidence(List<String> properties, Scenario scenario, Set<Check.Outcome> accepted) {
        static Evidence of(Scenario scenario, String... properties) {
            return new Evidence(List.of(properties), scenario, Set.of(Check.Outcome.VERIFIED, Check.Outcome.REPRESENTATION_DIFFERS));
        }

        static Evidence acceptingUnsupported(Scenario scenario, String... properties) {
            return new Evidence(List.of(properties), scenario, Set.of(Check.Outcome.VERIFIED, Check.Outcome.REPRESENTATION_DIFFERS, Check.Outcome.UNSUPPORTED));
        }
    }

    /**
     * Criterion to evidence. Only criteria whose observable behavior an existing oracle property actually embodies
     * are listed; the rest are reported as not assessed. Adding a row is a deliberate claim that the named properties,
     * on the named scenario, establish the criterion.
     */
    static final Map<String, Evidence> CRITERION_EVIDENCE = new LinkedHashMap<>();
    static {
        // normalization criteria are assessed on fixtures whose every source selected a compatibility entry (MATCHED_COMPATIBILITY):
        // on an untested-version fixture the oracle reports every normalization family UNSUPPORTED by design, which is RULE-33's
        // evidence (UC2-AC5), not evidence about the mapping; retention and denominator criteria apply to ANY fixture
        // an untested version: the stream stays the only declared source (source.expected under the expected profile against a frozen
        // manifest that names no native session file), retention holds, and the compatibility check is UNSUPPORTED (honest no-match)
        CRITERION_EVIDENCE.put("UC1-AC5", Evidence.acceptingUnsupported(Scenario.UNTESTED_VERSION, "source.expected", "source.compatibility", "whole_record.coverage"));
        CRITERION_EVIDENCE.put("UC1-AC6", Evidence.of(Scenario.ANY, "source.denominator", "source.expected", "source.inventory"));
        CRITERION_EVIDENCE.put("UC2-AC4", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "source.compatibility"));
        CRITERION_EVIDENCE.put("UC2-AC5", Evidence.acceptingUnsupported(Scenario.UNTESTED_VERSION, "source.compatibility", "whole_record.coverage"));
        CRITERION_EVIDENCE.put("UC3-AC1", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.requests", "request.values", "population.results", "result.values", "result.owner"));
        CRITERION_EVIDENCE.put("UC3-AC2", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.responses", "response.values"));
        CRITERION_EVIDENCE.put("UC3-AC3", Evidence.of(Scenario.ANY, "whole_record.structure_value"));
        CRITERION_EVIDENCE.put("UC3-AC4", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.usage", "usage.values"));
        CRITERION_EVIDENCE.put("UC3-AC5", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.cost", "cost.values"));
        CRITERION_EVIDENCE.put("UC3-AC6", Evidence.of(Scenario.ANY, "whole_record.coverage", "whole_record.class"));
        CRITERION_EVIDENCE.put("UC3-AC7", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.decisions", "decision.values"));
        CRITERION_EVIDENCE.put("UC3-AC8", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.operations", "operation.values"));
        CRITERION_EVIDENCE.put("UC3-AC10", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "time.basis"));
        CRITERION_EVIDENCE.put("UC3-AC11", Evidence.of(Scenario.ANY, "event.order", "whole_record.framing"));
        CRITERION_EVIDENCE.put("UC3-AC13", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "population.prompts", "prompt.values"));
        CRITERION_EVIDENCE.put("UC3-AC15", Evidence.of(Scenario.MATCHED_COMPATIBILITY, "time.basis"));
        // an honestly interrupted or cut source is reported UNSUPPORTED-for-a-complete-claim by the oracle: that is the required
        // behavior; an inconsistent label would be DEFECTIVE. Established only on a fixture that was actually interrupted.
        CRITERION_EVIDENCE.put("UC4-AC3", Evidence.acceptingUnsupported(Scenario.INTERRUPTED_SOURCE, "source.state", "source.extent"));
        CRITERION_EVIDENCE.put("UC4-AC5", Evidence.acceptingUnsupported(Scenario.INTERRUPTED_SOURCE, "package.state", "acquisition.complete", "source.state", "source.extent"));
        CRITERION_EVIDENCE.put("UC4-AC6", Evidence.of(Scenario.SPLIT_CHILD, "package.children"));
        CRITERION_EVIDENCE.put("UC5-AC3", Evidence.of(Scenario.ANY, "package.readable"));
        CRITERION_EVIDENCE.put("UC5-AC4", Evidence.of(Scenario.ANY, "source.denominator", "source.expected"));
    }

    /** Properties the oracle reports once per core, on the core's label ({@code main}, {@code child:<spawn>}). */
    static final Set<String> CORE_LEVEL = Set.of("package.readable", "package.state", "acquisition.complete", "event.order", "source.denominator");

    /** Properties the oracle reports on the root core only. */
    static final Set<String> ROOT_ONLY = Set.of("package.children");

    /** The one rule with an executable, deterministic check in this module: the writer declares no Agent Judge dependency. */
    static final String WRITER_INDEPENDENCE_RULE = "RULE-23";

    /** Criteria about this reporter, established by the negative controls computed in {@link #reporterControls}. */
    static final String UNASSESSED_REPORTING_CRITERION = "UC5-AC9";
    static final String PARTIAL_EVIDENCE_CRITERION = "UC5-AC12";

    /** The committed fixtures: each one's frozen expected sources (the independent source of obligations) and its oracle report. */
    record Corpus(Map<String, ExpectedSources> frozen, Map<String, OracleReport> reports) {
        Corpus with(Map<String, OracleReport> other) {
            return new Corpus(frozen, other);
        }
    }

    /** Statuses pinned per assessed criterion, so that a change in what the fixtures establish is a deliberate edit here. */
    static final Map<String, JudgmentStatus> EXPECTED_STATUS = new TreeMap<>();
    static {
        for (String id : List.of("UC1-AC5", "UC1-AC6", "UC2-AC4", "UC2-AC5", "UC3-AC1", "UC3-AC2", "UC3-AC3", "UC3-AC4", "UC3-AC5", "UC3-AC6", "UC3-AC7", "UC3-AC8", "UC3-AC10",
                "UC3-AC11", "UC3-AC15", "UC4-AC6", "UC5-AC3", "UC5-AC4", UNASSESSED_REPORTING_CRITERION, PARTIAL_EVIDENCE_CRITERION)) {
            EXPECTED_STATUS.put(id, JudgmentStatus.PASS);
        }
        // the replay-interrupted fixture (a sanitized real stream prefix, interrupted by the fixture driver; labelled, not live)
        // exercises the interruption criteria; on the two complete runs alone they are not assessed (see the reporter controls)
        EXPECTED_STATUS.put("UC4-AC3", JudgmentStatus.PASS);
        EXPECTED_STATUS.put("UC4-AC5", JudgmentStatus.PASS);
        // no committed fixture carries a prompt occurrence (the -p stream does not echo the prompt; the labelled Codex stream has none):
        // the prompt criterion is not assessed. REQ-1's PASS of the former combined content criterion rested on responses only.
        EXPECTED_STATUS.put("UC3-AC13", JudgmentStatus.ABSTAIN);
    }

    @Test
    void everyAssessedCriterionIsEstablishedOnAllItsObligationsAndEveryOtherRequirementIsReportedNotAssessed(@TempDir Path work) throws IOException {
        Document criteriaDoc = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        Document rulesDoc = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<EarsRequirement> criteria = EarsRequirement.fromStrict(criteriaDoc.file(), criteriaDoc.revision());
        List<Rfc2119Requirement> rules = Rfc2119Requirement.fromStrict(rulesDoc.file(), rulesDoc.revision());
        Corpus corpus = verifyFixtures(work.resolve("fixtures"));

        Map<String, Judgment> criteriaJudgments = judgeCriteria(criteria, corpus);
        Map<String, Judgment> ruleJudgments = judgeRules(rules);

        // Roster: every requirement answered, none invented.
        assertThat(criteriaJudgments.keySet()).containsExactlyElementsOf(criteria.stream().map(EarsRequirement::id).toList());
        assertThat(ruleJudgments.keySet()).containsExactlyElementsOf(rules.stream().map(Rfc2119Requirement::id).toList());
        assertThat(criteriaJudgments.keySet()).containsAll(CRITERION_EVIDENCE.keySet());

        // Assessed criteria: the pinned status each, with its obligations and scope stated. No ERROR anywhere: every
        // configured check is reported by the oracle for every applicable owner.
        for (Map.Entry<String, JudgmentStatus> e : EXPECTED_STATUS.entrySet()) {
            Judgment j = criteriaJudgments.get(e.getKey());
            assertThat(j.status()).as(e.getKey() + ": " + j.reasoning()).isEqualTo(e.getValue());
            if (e.getValue() == JudgmentStatus.PASS) {
                assertThat(j.checks()).as(e.getKey() + " retains one check per fixture/property/owner").isNotEmpty();
                assertThat(j.reasoning()).as(e.getKey() + " states its scope").contains("scoped to");
            }
            else {
                assertThat(j.reasoning()).as(e.getKey() + " says why it is not assessed").contains("not assessed");
            }
        }
        assertThat(criteriaJudgments.values()).noneMatch(j -> j.status() == JudgmentStatus.ERROR || j.status() == JudgmentStatus.FAIL);

        // Everything else: not assessed, and said so. The set is pinned so that assessing one more is deliberate.
        Set<String> notAssessed = new TreeSet<>();
        criteriaJudgments.forEach((id, j) -> {
            if (j.status() == JudgmentStatus.ABSTAIN) {
                notAssessed.add(id);
                assertThat(j.reasoning()).contains("not assessed");
            }
        });
        Set<String> expectedNotAssessed = new TreeSet<>(criteria.stream().map(EarsRequirement::id).toList());
        EXPECTED_STATUS.forEach((id, s) -> {
            if (s == JudgmentStatus.PASS) {
                expectedNotAssessed.remove(id);
            }
        });
        assertThat(notAssessed).isEqualTo(expectedNotAssessed);

        // Rollup: strict conjunction, as Agent Judge's own roster rollup does it. Unassessed requirements keep it from PASS.
        assertThat(rollup(criteriaJudgments.values())).isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(rollup(ruleJudgments.values())).isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(ruleJudgments.values().stream().filter(j -> j.status() != JudgmentStatus.ABSTAIN).count())
                .as("rules with an executable check in this module").isLessThanOrEqualTo(1);

        persist("criteria", criteriaDoc, criteria, criteriaJudgments);
        persist("rules", rulesDoc, rules, ruleJudgments);
    }

    /**
     * Negative control, watched red: a frozen manifest naming an artifact the package did not declare fails the
     * denominator criterion (every obligation present, one DEFECTIVE) and errors the inventory criterion (the native
     * artifact of the renamed source is not supplied, so its inventory check is missing: an incomplete instrument is
     * reported before, and together with, the defect it did observe). The rollup is ERROR, never PASS.
     */
    @Test
    void aRenamedExpectedSourceTurnsTheDenominatorCriteriaRedAndTheRollupIsNotPass(@TempDir Path work) throws IOException {
        Document criteriaDoc = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        List<EarsRequirement> criteria = EarsRequirement.fromStrict(criteriaDoc.file(), criteriaDoc.revision());
        Path fixture = copyFixture(CLAUDE_CHILD, work.resolve("fixtures"));
        Path manifest = fixture.resolve("native").resolve("expected-sources.json");
        String frozen = Files.readString(manifest);
        String renamed = frozen.replace("\"artifact\": \"stream.jsonl\",\n   \"profile\": \"claude_stream\"",
                "\"artifact\": \"renamed.jsonl\",\n   \"profile\": \"claude_stream\"");
        assertThat(renamed).as("the mutation applied").isNotEqualTo(frozen);
        Files.writeString(manifest, renamed);
        Corpus corpus = new Corpus(Map.of(CLAUDE_CHILD, ExpectedSources.read(manifest)), Map.of(CLAUDE_CHILD, verify(fixture)));
        assertThat(corpus.reports().get(CLAUDE_CHILD).defects()).as("the oracle itself reports the omission").isNotEmpty();

        Map<String, Judgment> judgments = judgeCriteria(criteria, corpus);

        Judgment denominator = judgments.get("UC5-AC4");
        assertThat(denominator.status()).withFailMessage(denominator.reasoning()).isEqualTo(JudgmentStatus.FAIL);
        assertThat(denominator.checks()).anyMatch(c -> c.judgment().status() == JudgmentStatus.FAIL && c.id().contains("source.expected [main/renamed.jsonl]"));
        Judgment inventory = judgments.get("UC1-AC6");
        assertThat(inventory.status()).withFailMessage(inventory.reasoning()).isEqualTo(JudgmentStatus.ERROR);
        assertThat(inventory.reasoning()).contains("source.inventory [main/renamed.jsonl]").contains("DEFECTIVE check(s) observed");
        assertThat(rollup(judgments.values())).isEqualTo(JudgmentStatus.ERROR);
    }

    /** Degenerate inputs: an empty report set and a property the oracle never emitted are judge errors, not passes. */
    @Test
    void aMappingWithNoMatchingOracleCheckIsAnErrorNotAPass(@TempDir Path work) {
        Corpus corpus = verifyFixtures(work);
        Judgment unknownProperty = judge("UC9-AC9", Evidence.of(Scenario.ANY, "property.that.does.not.exist"), corpus);
        assertThat(unknownProperty.status()).isEqualTo(JudgmentStatus.ERROR);
        assertThat(unknownProperty.reasoning()).contains("missing");

        Judgment noFixtures = judge("UC9-AC9", Evidence.of(Scenario.ANY, "result.owner"), new Corpus(Map.of(), Map.of()));
        assertThat(noFixtures.status()).isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(noFixtures.reasoning()).contains("no committed fixture");

        Corpus codexOnly = new Corpus(Map.of(CODEX_STREAM, corpus.frozen().get(CODEX_STREAM)), Map.of(CODEX_STREAM, corpus.reports().get(CODEX_STREAM)));
        Judgment onlyEmptyPopulation = judge("UC3-AC5", CRITERION_EVIDENCE.get("UC3-AC5"), codexOnly);
        assertThat(onlyEmptyPopulation.status()).as("the Codex stream reports no cost; NOT_APPLICABLE everywhere is no evidence").isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(onlyEmptyPopulation.reasoning()).contains("no occurrence");
    }

    // ---- the judge -------------------------------------------------------------------------------------------------

    static Map<String, Judgment> judgeCriteria(List<EarsRequirement> criteria, Corpus corpus) {
        Map<String, Judgment> out = new LinkedHashMap<>();
        for (EarsRequirement c : criteria) {
            Evidence evidence = CRITERION_EVIDENCE.get(c.id());
            if (evidence != null) {
                out.put(c.id(), judge(c.id(), evidence, corpus));
            }
            else if (UNASSESSED_REPORTING_CRITERION.equals(c.id()) || PARTIAL_EVIDENCE_CRITERION.equals(c.id())) {
                out.put(c.id(), reporterControls(c.id(), corpus));
            }
            else {
                out.put(c.id(), Judgment.abstain("not assessed: no oracle property is mapped to " + c.id() + "; see its Tests: and Status: lines in criteria.md"));
            }
        }
        return out;
    }

    static Map<String, Judgment> judgeRules(List<Rfc2119Requirement> rules) {
        Map<String, Judgment> out = new LinkedHashMap<>();
        for (Rfc2119Requirement r : rules) {
            out.put(r.id(), r.id().equals(WRITER_INDEPENDENCE_RULE) ? writerIndependence()
                    : Judgment.abstain("not assessed: no executable check in this module establishes " + r.id() + "; its Tests: line links related tests by name only"));
        }
        return out;
    }

    /** RULE-23 by inspection of the sibling module's build file when the source tree is present; ABSTAIN otherwise. */
    static Judgment writerIndependence() {
        Path writerPom = Path.of(System.getProperty("basedir", ".")).resolve("..").resolve("journal-ocsf-writer").resolve("pom.xml").normalize();
        if (!Files.isRegularFile(writerPom)) {
            return Judgment.abstain("not assessed: " + writerPom + " is not present in this build");
        }
        try {
            String pom = Files.readString(writerPom);
            boolean clean = !pom.contains("agent-judge") && !pom.contains("spring-ai") && !pom.contains("langchain4j");
            String detail = writerPom + " declares " + (clean ? "no" : "an") + " evaluation or model dependency";
            return clean ? Judgment.builder().pass().reasoning(detail).checks(List.of(io.github.markpollack.judge.judgment.Check.pass("journal-ocsf-writer/pom.xml", detail))).build()
                    : Judgment.builder().fail().reasoning(detail).checks(List.of(io.github.markpollack.judge.judgment.Check.fail("journal-ocsf-writer/pom.xml", detail))).build();
        }
        catch (IOException e) {
            return Judgment.error("could not read " + writerPom + ": " + e.getMessage());
        }
    }

    /** The owners a property must be reported on for one fixture, from its frozen manifest alone. */
    static List<String> expectedOwners(String property, ExpectedSources frozen) {
        List<String> labels = new ArrayList<>();
        labels.add("main");
        for (ExpectedSources.Child child : frozen.children()) {
            labels.add("child:" + child.spawnToolCallUid());
        }
        List<String> owners = new ArrayList<>();
        if (ROOT_ONLY.contains(property)) {
            owners.add("main");
        }
        else if (CORE_LEVEL.contains(property)) {
            owners.addAll(labels);
        }
        else {
            for (String label : labels) {
                for (ExpectedSources.Source s : frozen.sources()) {
                    owners.add(label + "/" + s.artifact());
                }
            }
        }
        return owners;
    }

    /**
     * Conjunction over every obligation: for each fixture exercising the scenario, each property, each expected owner.
     * Missing → ERROR; any DEFECTIVE → FAIL; any outcome outside the accepted set other than NOT_APPLICABLE → ABSTAIN;
     * no applicable fixture or only NOT_APPLICABLE → ABSTAIN (not assessed); otherwise PASS, scoped.
     */
    static Judgment judge(String id, Evidence evidence, Corpus corpus) {
        List<io.github.markpollack.judge.judgment.Check> checks = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> applicable = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> verifiedOn = new ArrayList<>();
        int accepted = 0, defective = 0, unresolved = 0, notApplicable = 0;
        for (Map.Entry<String, ExpectedSources> f : corpus.frozen().entrySet()) {
            String fixture = f.getKey();
            if (!evidence.scenario().exercisedBy(f.getValue())) {
                skipped.add(fixture);
                continue;
            }
            applicable.add(fixture);
            OracleReport report = corpus.reports().get(fixture);
            for (String property : evidence.properties()) {
                for (String owner : expectedOwners(property, f.getValue())) {
                    String name = fixture + " " + property + " [" + owner + "]";
                    Check c = report == null ? null : report.check(property, owner);
                    if (c == null) {
                        missing.add(name);
                        checks.add(new io.github.markpollack.judge.judgment.Check(name, Judgment.error("required check missing from the oracle report")));
                        continue;
                    }
                    String detail = c.outcome() + (c.detail() == null || c.detail().isBlank() ? "" : ": " + c.detail());
                    if (c.outcome() == Check.Outcome.DEFECTIVE) {
                        defective++;
                        checks.add(io.github.markpollack.judge.judgment.Check.fail(name, detail));
                    }
                    else if (c.outcome() == Check.Outcome.NOT_APPLICABLE) {
                        notApplicable++;
                        checks.add(new io.github.markpollack.judge.judgment.Check(name, Judgment.abstain(detail)));
                    }
                    else if (evidence.accepted().contains(c.outcome())) {
                        accepted++;
                        verifiedOn.add(name);
                        checks.add(io.github.markpollack.judge.judgment.Check.pass(name, detail));
                    }
                    else {
                        unresolved++;
                        checks.add(new io.github.markpollack.judge.judgment.Check(name, Judgment.abstain(detail)));
                    }
                }
            }
        }
        String denominator = checks.size() + " obligation(s) over " + applicable.size() + " applicable fixture(s) " + applicable + " for " + evidence.properties()
                + " on scenario " + evidence.scenario() + ": " + accepted + " accepted (" + evidence.accepted() + "), " + defective + " DEFECTIVE, " + unresolved
                + " other, " + notApplicable + " NOT_APPLICABLE, " + missing.size() + " missing" + (skipped.isEmpty() ? "" : "; fixtures not exercising the scenario: " + skipped);
        Judgment.EnrichmentBuilder builder;
        if (applicable.isEmpty()) {
            builder = Judgment.builder().abstain().reasoning(id + ": not assessed: no committed fixture exercises " + evidence.scenario().description + "; " + denominator);
        }
        else if (!missing.isEmpty()) {
            builder = Judgment.builder().error().reasoning(id + ": incomplete instrument: " + missing.size() + " required check(s) missing from the oracle report: " + missing
                    + (defective > 0 ? "; and " + defective + " DEFECTIVE check(s) observed on the checks that are present" : "") + "; " + denominator);
        }
        else if (defective > 0) {
            builder = Judgment.builder().fail().reasoning(id + ": DEFECTIVE on the fixtures; " + denominator);
        }
        else if (unresolved > 0) {
            builder = Judgment.builder().abstain().reasoning(id + ": not established, an unresolved or unsupported check remains; " + denominator);
        }
        else if (accepted > 0) {
            builder = Judgment.builder().pass().reasoning(id + ": established by the independent oracle, scoped to " + verifiedOn.size() + " obligation(s) with an occurrence: "
                    + verifiedOn + "; " + denominator);
        }
        else {
            builder = Judgment.builder().abstain().reasoning(id + ": not assessed: no occurrence on the committed fixtures; " + denominator);
        }
        return builder.checks(checks).metadata("fixtures", List.copyOf(applicable)).metadata("properties", evidence.properties())
                .metadata("scenario", evidence.scenario().name()).metadata("accepted_outcomes", evidence.accepted().stream().map(Enum::name).sorted().toList()).build();
    }

    /**
     * The reporter's own criteria, established by negative controls computed on the real reports in this run: one
     * required property removed, one required owner removed, prompt evidence omitted while responses pass, and a
     * scenario no fixture exercises. Each must stop a full-criterion PASS; the positive control must still pass.
     */
    static Judgment reporterControls(String id, Corpus corpus) {
        List<io.github.markpollack.judge.judgment.Check> checks = new ArrayList<>();
        Evidence requests = CRITERION_EVIDENCE.get("UC3-AC1");
        control(checks, "positive control UC3-AC1 on the unmodified reports", judge("UC3-AC1", requests, corpus), JudgmentStatus.PASS);
        control(checks, "one required property (result.owner) removed from every report",
                judge("UC3-AC1", requests, corpus.with(map(corpus.reports(), r -> r.filtered(c -> !c.property().equals("result.owner"))))), JudgmentStatus.ERROR);
        String childOwner = childOwner(corpus);
        if (childOwner != null) {
            control(checks, "one required owner (" + childOwner + ") removed from every report",
                    judge("UC3-AC1", requests, corpus.with(map(corpus.reports(), r -> r.filtered(c -> !c.owner().equals(childOwner))))), JudgmentStatus.ERROR);
        }
        Corpus noPrompts = corpus.with(map(corpus.reports(), r -> r.filtered(c -> !c.property().equals("prompt.values") && !c.property().equals("population.prompts"))));
        control(checks, "prompt evidence omitted: the response criterion UC3-AC2 still passes", judge("UC3-AC2", CRITERION_EVIDENCE.get("UC3-AC2"), noPrompts), JudgmentStatus.PASS);
        control(checks, "prompt evidence omitted: the prompt criterion UC3-AC13 is an error", judge("UC3-AC13", CRITERION_EVIDENCE.get("UC3-AC13"), noPrompts), JudgmentStatus.ERROR);
        control(checks, "a scenario the fixtures in hand do not exercise (interrupted source, on the complete runs only) is not assessed",
                judge("UC4-AC5", CRITERION_EVIDENCE.get("UC4-AC5"), completeRunsOnly(corpus)), JudgmentStatus.ABSTAIN);
        control(checks, "the same scenario on the corpus with the replay-interrupted fixture is established, scoped to that fixture",
                judge("UC4-AC5", CRITERION_EVIDENCE.get("UC4-AC5"), corpus), JudgmentStatus.PASS);
        boolean allHeld = checks.stream().allMatch(c -> c.judgment().status() == JudgmentStatus.PASS);
        String reasoning = id + ": established by " + checks.size() + " negative/positive control(s) computed on the committed fixtures' real oracle reports in this run,"
                + " scoped to this reporter's reduction (evidence about the reporter, not about the product); " + (allHeld ? "every control held" : "a control did not hold");
        return (allHeld ? Judgment.builder().pass() : Judgment.builder().fail()).reasoning(reasoning).checks(checks).metadata("fixtures", List.copyOf(corpus.reports().keySet())).build();
    }

    private static void control(List<io.github.markpollack.judge.judgment.Check> checks, String name, Judgment observed, JudgmentStatus expected) {
        String detail = "expected " + expected + ", observed " + observed.status() + ": " + observed.reasoning();
        checks.add(observed.status() == expected ? io.github.markpollack.judge.judgment.Check.pass(name, detail) : io.github.markpollack.judge.judgment.Check.fail(name, detail));
    }

    /** The corpus restricted to fixtures whose frozen expected sources declare a complete run. */
    static Corpus completeRunsOnly(Corpus corpus) {
        Map<String, ExpectedSources> frozen = new LinkedHashMap<>();
        Map<String, OracleReport> reports = new LinkedHashMap<>();
        corpus.frozen().forEach((name, f) -> {
            if (f.complete()) {
                frozen.put(name, f);
                reports.put(name, corpus.reports().get(name));
            }
        });
        return new Corpus(frozen, reports);
    }

    /** The first split child's artifact owner across the corpus, from the frozen manifests, or {@code null}. */
    static String childOwner(Corpus corpus) {
        for (ExpectedSources frozen : corpus.frozen().values()) {
            for (ExpectedSources.Child child : frozen.children()) {
                return "child:" + child.spawnToolCallUid() + "/" + child.artifact();
            }
        }
        return null;
    }

    static Map<String, OracleReport> map(Map<String, OracleReport> reports, java.util.function.UnaryOperator<OracleReport> f) {
        Map<String, OracleReport> out = new LinkedHashMap<>();
        reports.forEach((k, v) -> out.put(k, f.apply(v)));
        return out;
    }

    static JudgmentStatus rollup(Iterable<Judgment> judgments) {
        boolean fail = false, abstain = false;
        for (Judgment j : judgments) {
            switch (j.status()) {
                case ERROR -> {
                    return JudgmentStatus.ERROR;
                }
                case FAIL -> fail = true;
                case ABSTAIN, NOT_APPLICABLE -> abstain = true;
                case PASS -> {
                }
            }
        }
        return fail ? JudgmentStatus.FAIL : abstain ? JudgmentStatus.ABSTAIN : JudgmentStatus.PASS;
    }

    // ---- fixtures and persistence ----------------------------------------------------------------------------------

    static Corpus verifyFixtures(Path work) {
        Map<String, ExpectedSources> frozen = new LinkedHashMap<>();
        Map<String, OracleReport> reports = new LinkedHashMap<>();
        for (String name : FIXTURES) {
            Path fixture = copyFixture(name, work);
            frozen.put(name, ExpectedSources.beside(fixture.resolve("native")));
            reports.put(name, verify(fixture));
        }
        return new Corpus(frozen, reports);
    }

    static OracleReport verify(Path fixture) {
        return OcsfOracle.verify(PackageReader.open(fixture.resolve("package").resolve("events.jsonl")), fixture.resolve("native"));
    }

    static Path copyFixture(String name, Path work) {
        try {
            Path source = Path.of(RequirementsEvidenceTest.class.getResource("/ocsf/fixtures/" + name + "/spec.json").toURI()).getParent();
            Path target = work.resolve(name);
            try (Stream<Path> s = Files.walk(source)) {
                for (Path p : s.toList()) {
                    Path t = target.resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(t);
                    }
                    else {
                        Files.createDirectories(t.getParent());
                        Files.copy(p, t);
                    }
                }
            }
            return target;
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Path tempDir() {
        try {
            return Files.createTempDirectory("requirements-evidence");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One JSON document per requirement set: identity, revision, source digest and the full judgment with its checks. */
    static void persist(String name, Document doc, List<? extends Requirement<?>> requirements, Map<String, Judgment> judgments) throws IOException {
        Path dir = Path.of(System.getProperty("basedir", ".")).resolve("target").resolve("requirements-evidence");
        Files.createDirectories(dir);
        ObjectMapper mapper = new ObjectMapper().registerModule(ResultJson.module()).enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("document", doc.resource());
        out.put("revision", doc.revision());
        out.put("question", "does the independent oracle, run on the committed public fixtures, establish this requirement's observable behavior for those fixtures?");
        out.put("fixtures", FIXTURES);
        out.put("evidence_class", "committed public fixtures: labelled synthetic Codex exec stream; sanitized real Claude stream with one child core; "
                + "the same stream's 10-record prefix replay-interrupted by the fixture driver (labelled); the same stream with its reported version edited to one no "
                + "compatibility entry covers (labelled synthetic); five sanitized real live recordings of ACCEPT-1 (Claude Code 2.1.304 feature, denial and cancelled "
                + "runs; Codex CLI 0.160.1 command and cancelled runs) replayed through the writer. Recorded replay and labelled synthetic; the live captures themselves are private steward evidence.");
        out.put("reduction", "a criterion passes only when every named property is reported for every owner its fixtures' frozen expected sources imply and the named scenario was exercised; "
                + "missing check: ERROR; DEFECTIVE: FAIL; unresolved/unsupported: ABSTAIN; no occurrence or no exercising fixture: ABSTAIN (not assessed)");
        out.put("rollup", rollup(judgments.values()).name());
        Map<String, Integer> byStatus = new TreeMap<>();
        for (Judgment j : judgments.values()) {
            byStatus.merge(j.status().name(), 1, Integer::sum);
        }
        out.put("by_status", byStatus);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Requirement<?> r : requirements) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.id());
            item.put("revision", r.revision());
            item.put("source_sha256", r.source().artifact().sha256());
            item.put("text", r.text());
            item.put("status", judgments.get(r.id()).status().name());
            item.put("judgment", judgments.get(r.id()));
            items.add(item);
        }
        out.put("requirements", items);
        Files.writeString(dir.resolve(name + "-status.json"), mapper.writeValueAsString(out), StandardCharsets.UTF_8);
    }
}
