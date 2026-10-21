package io.github.markpollack.journal.tck.requirements;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.judge.ai.requirements.EarsRequirement;
import io.github.markpollack.judge.ai.requirements.Rfc2119Requirement;
import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import io.github.markpollack.judge.requirement.Requirement;
import io.github.markpollack.judge.serialization.ResultJson;
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
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One question per criterion: <em>does the independent oracle, run on the committed public fixtures, establish this
 * criterion's observable behavior for those fixtures?</em>
 *
 * <p>The answer is computed, never canned. A criterion is mapped to the oracle properties that embody it; the oracle
 * runs on the two committed public fixtures (a labelled synthetic Codex exec stream and a sanitized real Claude stream
 * with one child core); each (fixture, property, owner) outcome becomes one retained {@code Check}; the criterion's
 * status is the conjunction of those checks. A criterion with no mapping is {@code ABSTAIN}: not assessed, never
 * passed. A mapping that names a property the oracle did not emit is {@code ERROR}: a misconfigured judge, not a
 * failing subject. Every rule in {@code rules.md} is likewise {@code ABSTAIN} except the one with an executable
 * file check, so the roster rollup for both documents is {@code ABSTAIN} today, and this test asserts exactly that.
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
    static final List<String> FIXTURES = List.of(CODEX_STREAM, CLAUDE_CHILD);

    /**
     * Criterion to oracle properties. A property is matched on every owner (main core, child core, each source).
     * Only criteria whose observable behavior an existing oracle property actually embodies are listed; the rest are
     * reported as not assessed. Adding a row is a deliberate claim that the named property establishes the criterion.
     */
    static final Map<String, List<String>> CRITERION_PROPERTIES = new LinkedHashMap<>();
    static {
        CRITERION_PROPERTIES.put("UC1-AC6", List.of("source.denominator", "source.expected", "source.inventory"));
        CRITERION_PROPERTIES.put("UC3-AC1", List.of("population.requests", "request.values", "population.results", "result.values", "result.owner"));
        CRITERION_PROPERTIES.put("UC3-AC2", List.of("population.responses", "response.values"));
        CRITERION_PROPERTIES.put("UC3-AC3", List.of("whole_record.structure_value"));
        CRITERION_PROPERTIES.put("UC3-AC4", List.of("population.usage", "usage.values"));
        CRITERION_PROPERTIES.put("UC3-AC5", List.of("population.cost", "cost.values"));
        CRITERION_PROPERTIES.put("UC3-AC6", List.of("whole_record.coverage", "whole_record.class"));
        CRITERION_PROPERTIES.put("UC3-AC7", List.of("population.decisions", "decision.values"));
        CRITERION_PROPERTIES.put("UC3-AC8", List.of("population.operations", "operation.values"));
        CRITERION_PROPERTIES.put("UC3-AC10", List.of("time.basis"));
        CRITERION_PROPERTIES.put("UC3-AC11", List.of("event.order", "whole_record.framing"));
        CRITERION_PROPERTIES.put("UC4-AC5", List.of("package.state", "acquisition.complete", "source.state", "source.extent"));
        CRITERION_PROPERTIES.put("UC4-AC6", List.of("package.children"));
        CRITERION_PROPERTIES.put("UC5-AC3", List.of("package.readable"));
        CRITERION_PROPERTIES.put("UC5-AC4", List.of("source.denominator", "source.expected"));
    }

    /** The one rule with an executable, deterministic check in this module: the writer declares no Agent Judge dependency. */
    static final String WRITER_INDEPENDENCE_RULE = "RULE-23";

    @Test
    void mappedCriteriaAreEstablishedOnTheCommittedFixturesAndEveryOtherRequirementIsReportedNotAssessed(@TempDir Path work) throws IOException {
        Document criteriaDoc = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        Document rulesDoc = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<EarsRequirement> criteria = EarsRequirement.from(criteriaDoc.file(), criteriaDoc.revision());
        List<Rfc2119Requirement> rules = Rfc2119Requirement.from(rulesDoc.file(), rulesDoc.revision());
        Map<String, OracleReport> reports = verifyFixtures(work.resolve("fixtures"));

        Map<String, Judgment> criteriaJudgments = judgeCriteria(criteria, reports);
        Map<String, Judgment> ruleJudgments = judgeRules(rules);

        // Roster: every requirement answered, none invented.
        assertThat(criteriaJudgments.keySet()).containsExactlyElementsOf(criteria.stream().map(EarsRequirement::id).toList());
        assertThat(ruleJudgments.keySet()).containsExactlyElementsOf(rules.stream().map(Rfc2119Requirement::id).toList());
        assertThat(criteriaJudgments.keySet()).containsAll(CRITERION_PROPERTIES.keySet());

        // Mapped criteria: each PASS on the controls, with its denominator stated.
        for (String id : CRITERION_PROPERTIES.keySet()) {
            Judgment j = criteriaJudgments.get(id);
            assertThat(j.status()).as(id + ": " + j.reasoning()).isEqualTo(JudgmentStatus.PASS);
            assertThat(j.checks()).as(id + " retains one check per fixture/property/owner").isNotEmpty();
            assertThat(j.reasoning()).as(id + " states its denominator").contains("VERIFIED on").contains("fixture");
        }

        // Everything else: not assessed, and said so. The list is pinned so that assessing one more is deliberate.
        Set<String> notAssessed = new TreeSet<>();
        criteriaJudgments.forEach((id, j) -> {
            if (j.status() == JudgmentStatus.ABSTAIN) {
                notAssessed.add(id);
                assertThat(j.reasoning()).contains("not assessed");
            }
        });
        Set<String> expectedNotAssessed = new TreeSet<>(criteria.stream().map(EarsRequirement::id).toList());
        expectedNotAssessed.removeAll(CRITERION_PROPERTIES.keySet());
        assertThat(notAssessed).isEqualTo(expectedNotAssessed);
        assertThat(criteriaJudgments.values()).noneMatch(j -> j.status() == JudgmentStatus.ERROR || j.status() == JudgmentStatus.FAIL);

        // Rollup: strict conjunction, as Agent Judge's own roster rollup does it. Unassessed requirements keep it from PASS.
        assertThat(rollup(criteriaJudgments.values())).isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(rollup(ruleJudgments.values())).isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(ruleJudgments.values().stream().filter(j -> j.status() != JudgmentStatus.ABSTAIN).count())
                .as("rules with an executable check in this module").isLessThanOrEqualTo(1);

        persist("criteria", criteriaDoc, criteria, criteriaJudgments);
        persist("rules", rulesDoc, rules, ruleJudgments);
    }

    /** Negative control, watched red: a frozen manifest naming an artifact the package did not declare fails the denominator criteria. */
    @Test
    void aRenamedExpectedSourceTurnsTheDenominatorCriteriaRedAndTheRollupFails(@TempDir Path work) throws IOException {
        Document criteriaDoc = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        List<EarsRequirement> criteria = EarsRequirement.from(criteriaDoc.file(), criteriaDoc.revision());
        Path fixture = copyFixture(CLAUDE_CHILD, work.resolve("fixtures"));
        Path manifest = fixture.resolve("native").resolve("expected-sources.json");
        String frozen = Files.readString(manifest);
        String renamed = frozen.replace("\"artifact\": \"stream.jsonl\",\n   \"profile\": \"claude_stream\"",
                "\"artifact\": \"renamed.jsonl\",\n   \"profile\": \"claude_stream\"");
        assertThat(renamed).as("the mutation applied").isNotEqualTo(frozen);
        Files.writeString(manifest, renamed);
        Map<String, OracleReport> reports = Map.of(CLAUDE_CHILD, verify(fixture));
        assertThat(reports.get(CLAUDE_CHILD).defects()).as("the oracle itself reports the omission").isNotEmpty();

        Map<String, Judgment> judgments = judgeCriteria(criteria, reports);

        assertThat(judgments.get("UC5-AC4").status()).as(judgments.get("UC5-AC4").reasoning()).isEqualTo(JudgmentStatus.FAIL);
        assertThat(judgments.get("UC1-AC6").status()).isEqualTo(JudgmentStatus.FAIL);
        assertThat(judgments.get("UC5-AC4").checks()).anyMatch(c -> c.judgment().status() == JudgmentStatus.FAIL && c.id().contains("source.expected"));
        assertThat(rollup(judgments.values())).isEqualTo(JudgmentStatus.FAIL);
    }

    /** Degenerate inputs: an empty report set and a property the oracle never emitted are judge errors, not passes. */
    @Test
    void aMappingWithNoMatchingOracleCheckIsAnErrorNotAPass() {
        OracleReport empty = verify(copyFixture(CODEX_STREAM, tempDir()));
        Judgment unknownProperty = judge("UC9-AC9", List.of("property.that.does.not.exist"), Map.of(CODEX_STREAM, empty));
        assertThat(unknownProperty.status()).isEqualTo(JudgmentStatus.ERROR);
        assertThat(unknownProperty.reasoning()).contains("0 check(s)");

        Judgment noFixtures = judge("UC9-AC9", List.of("result.owner"), Map.of());
        assertThat(noFixtures.status()).isEqualTo(JudgmentStatus.ERROR);

        Judgment onlyEmptyPopulation = judge("UC3-AC5", List.of("population.cost", "cost.values"), Map.of(CODEX_STREAM, empty));
        assertThat(onlyEmptyPopulation.status()).as("the Codex stream reports no cost; NOT_APPLICABLE everywhere is no evidence").isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(onlyEmptyPopulation.reasoning()).contains("no occurrence");
    }

    // ---- the judge -------------------------------------------------------------------------------------------------

    static Map<String, Judgment> judgeCriteria(List<EarsRequirement> criteria, Map<String, OracleReport> reports) {
        Map<String, Judgment> out = new LinkedHashMap<>();
        for (EarsRequirement c : criteria) {
            List<String> properties = CRITERION_PROPERTIES.get(c.id());
            out.put(c.id(), properties == null
                    ? Judgment.abstain("not assessed: no oracle property is mapped to " + c.id() + "; see its Tests: and Status: lines in criteria.md")
                    : judge(c.id(), properties, reports));
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

    /**
     * Conjunction over every (fixture, property, owner) check the oracle emitted for the mapped properties:
     * any DEFECTIVE → FAIL; any UNRESOLVED or UNSUPPORTED (and no DEFECTIVE) → ABSTAIN; at least one VERIFIED and nothing
     * worse → PASS; only NOT_APPLICABLE → ABSTAIN (no occurrence on these fixtures); no check at all → ERROR.
     * REPRESENTATION_DIFFERS counts as VERIFIED for value preservation and is named in the check.
     */
    static Judgment judge(String id, List<String> properties, Map<String, OracleReport> reports) {
        List<io.github.markpollack.judge.judgment.Check> checks = new ArrayList<>();
        int verified = 0, defective = 0, unresolved = 0, notApplicable = 0;
        for (Map.Entry<String, OracleReport> e : reports.entrySet()) {
            for (Check c : e.getValue().checks()) {
                if (!properties.contains(c.property())) {
                    continue;
                }
                String name = e.getKey() + " " + c.property() + " [" + c.owner() + "]";
                String detail = c.outcome() + (c.detail() == null || c.detail().isBlank() ? "" : ": " + c.detail());
                switch (c.outcome()) {
                    case VERIFIED, REPRESENTATION_DIFFERS -> {
                        verified++;
                        checks.add(io.github.markpollack.judge.judgment.Check.pass(name, detail));
                    }
                    case DEFECTIVE -> {
                        defective++;
                        checks.add(io.github.markpollack.judge.judgment.Check.fail(name, detail));
                    }
                    case UNSUPPORTED, UNRESOLVED -> {
                        unresolved++;
                        checks.add(new io.github.markpollack.judge.judgment.Check(name, Judgment.abstain(detail)));
                    }
                    case NOT_APPLICABLE -> {
                        notApplicable++;
                        checks.add(new io.github.markpollack.judge.judgment.Check(name, Judgment.abstain(detail)));
                    }
                }
            }
        }
        String denominator = checks.size() + " check(s) over " + reports.size() + " fixture(s) for " + properties + ": "
                + verified + " VERIFIED, " + defective + " DEFECTIVE, " + unresolved + " UNRESOLVED/UNSUPPORTED, " + notApplicable + " NOT_APPLICABLE";
        Judgment.EnrichmentBuilder builder;
        if (checks.isEmpty()) {
            builder = Judgment.builder().error().reasoning(id + ": no oracle check matched; " + denominator);
        }
        else if (defective > 0) {
            builder = Judgment.builder().fail().reasoning(id + ": DEFECTIVE on the fixtures; " + denominator);
        }
        else if (unresolved > 0) {
            builder = Judgment.builder().abstain().reasoning(id + ": not established, an unresolved or unsupported check remains; " + denominator);
        }
        else if (verified > 0) {
            builder = Judgment.builder().pass().reasoning(id + ": VERIFIED on " + reports.size() + " committed fixture(s) by the independent oracle; " + denominator);
        }
        else {
            builder = Judgment.builder().abstain().reasoning(id + ": no occurrence on the committed fixtures; " + denominator);
        }
        return builder.checks(checks).metadata("fixtures", List.copyOf(reports.keySet())).metadata("properties", properties).build();
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

    static Map<String, OracleReport> verifyFixtures(Path work) {
        Map<String, OracleReport> reports = new LinkedHashMap<>();
        for (String name : FIXTURES) {
            reports.put(name, verify(copyFixture(name, work)));
        }
        return reports;
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
        out.put("evidence_class", "committed public fixtures: labelled synthetic Codex exec stream; sanitized real Claude stream with one child core. Not live capture.");
        out.put("rollup", rollup(judgments.values()).name());
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
