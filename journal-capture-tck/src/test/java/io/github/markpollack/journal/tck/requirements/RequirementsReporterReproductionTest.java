package io.github.markpollack.journal.tck.requirements;

import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import io.github.markpollack.journal.tck.ocsf.ExpectedSources;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.CLAUDE_CHILD;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.CODEX_STREAM;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.CRITERION_PROPERTIES;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.judge;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.verifyFixtures;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces, against the unchanged requirements reporter of REQ-1 ({@code a6dce21}), the review findings on how it
 * reduces oracle checks to a criterion status. Each test pins what the reporter says today; the repair flips these
 * assertions to the corrected outcomes and keeps the scenarios as regressions.
 *
 * <ul>
 * <li>RR-01: UC3-AC2 covers response and prompt content but maps only the response properties, so prompt evidence
 * that is absent, or even DEFECTIVE, leaves the criterion PASS.</li>
 * <li>RR-02: the reducer passes on the subset of mapped properties that happened to appear; removing every check of
 * one required property (UC3-AC1's {@code result.owner}) or of one required owner (the child core) leaves PASS.</li>
 * <li>RR-03: UC4-AC5 (acquisition completeness kept separate from storage finalization when a source was
 * interrupted or cut) is PASS although every committed fixture is a complete run and no interrupted scenario was
 * exercised.</li>
 * <li>RR-04: UC5-AC3 (validation against the pinned compiled OCSF schema) is PASS on {@code package.readable}, a
 * property the Java reader computes; the report carries no evidence that the pinned upstream validator ran.</li>
 * </ul>
 */
class RequirementsReporterReproductionTest {

    private static final String CHILD_OWNER = "child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ/stream.jsonl";

    @Test
    void rr01PromptEvidenceAbsentOrDefectiveLeavesTheContentCriterionPass(@TempDir Path work) {
        Map<String, OracleReport> reports = verifyFixtures(work);
        assertThat(CRITERION_PROPERTIES.get("UC3-AC2")).as("the mapping names response properties only")
                .containsExactly("population.responses", "response.values");
        for (OracleReport r : reports.values()) {
            assertThat(r.checks()).as("the oracle does report prompt properties the mapping ignores")
                    .anyMatch(c -> c.property().equals("prompt.values"));
        }

        Map<String, OracleReport> noPrompts = map(reports, r -> r.filtered(c -> !c.property().startsWith("prompt.") && !c.property().equals("population.prompts")));
        Judgment absent = judge("UC3-AC2", CRITERION_PROPERTIES.get("UC3-AC2"), noPrompts);
        assertThat(absent.status()).as("REPRODUCED: no prompt evidence at all, criterion still PASS").isEqualTo(JudgmentStatus.PASS);

        Map<String, OracleReport> badPrompts = map(reports, r -> r.replaced(c -> c.property().equals("prompt.values"),
                c -> new Check(c.category(), c.property(), c.owner(), Check.Outcome.DEFECTIVE, "synthetic: prompt text altered")));
        Judgment defective = judge("UC3-AC2", CRITERION_PROPERTIES.get("UC3-AC2"), badPrompts);
        assertThat(defective.status()).as("REPRODUCED: prompt evidence DEFECTIVE, criterion still PASS").isEqualTo(JudgmentStatus.PASS);
    }

    @Test
    void rr02RemovingOneRequiredPropertyOrOneRequiredOwnerLeavesTheCriterionPass(@TempDir Path work) {
        Map<String, OracleReport> reports = verifyFixtures(work);
        List<String> properties = CRITERION_PROPERTIES.get("UC3-AC1");
        assertThat(properties).contains("result.owner");
        Judgment control = judge("UC3-AC1", properties, reports);
        assertThat(control.status()).isEqualTo(JudgmentStatus.PASS);

        Map<String, OracleReport> noOwnerProperty = map(reports, r -> r.filtered(c -> !c.property().equals("result.owner")));
        Judgment missingProperty = judge("UC3-AC1", properties, noOwnerProperty);
        assertThat(missingProperty.status()).as("REPRODUCED: result.owner never appears, criterion still PASS").isEqualTo(JudgmentStatus.PASS);
        assertThat(missingProperty.checks()).noneMatch(c -> c.id().contains("result.owner"));

        assertThat(reports.get(CLAUDE_CHILD).checks()).as("the child core is a judged owner").anyMatch(c -> c.owner().equals(CHILD_OWNER));
        Map<String, OracleReport> noChildOwner = map(reports, r -> r.filtered(c -> !c.owner().equals(CHILD_OWNER)));
        Judgment missingOwner = judge("UC3-AC1", properties, noChildOwner);
        assertThat(missingOwner.status()).as("REPRODUCED: the child core has no check at all, criterion still PASS").isEqualTo(JudgmentStatus.PASS);
        assertThat(missingOwner.checks()).noneMatch(c -> c.id().contains(CHILD_OWNER));
    }

    @Test
    void rr03InterruptionCriterionPassesOnCompleteRunsWithoutAnInterruptedScenario(@TempDir Path work) {
        Map<String, OracleReport> reports = verifyFixtures(work);
        for (String fixture : List.of(CODEX_STREAM, CLAUDE_CHILD)) {
            ExpectedSources frozen = ExpectedSources.beside(RequirementsEvidenceTest.copyFixture(fixture, work.resolve("frozen")).resolve("native"));
            assertThat(frozen.complete()).as(fixture + " is frozen as a complete run; nothing was interrupted or cut").isTrue();
        }
        for (OracleReport r : reports.values()) {
            assertThat(r.checks()).noneMatch(c -> c.detail() != null && c.detail().contains("interrupted"));
        }
        Judgment j = judge("UC4-AC5", CRITERION_PROPERTIES.get("UC4-AC5"), reports);
        assertThat(j.status()).as("REPRODUCED: baseline state checks on completed runs pass an interruption criterion").isEqualTo(JudgmentStatus.PASS);
    }

    @Test
    void rr04SchemaValidationCriterionPassesOnTheJavaReaderCheckAlone(@TempDir Path work) {
        Map<String, OracleReport> reports = verifyFixtures(work);
        assertThat(CRITERION_PROPERTIES.get("UC5-AC3")).containsExactly("package.readable");
        for (OracleReport r : reports.values()) {
            assertThat(r.checks()).as("no check names the upstream OCSF validator").noneMatch(c -> c.property().startsWith("ocsf."));
        }
        Judgment j = judge("UC5-AC3", CRITERION_PROPERTIES.get("UC5-AC3"), reports);
        assertThat(j.status()).as("REPRODUCED: PASS without any upstream-validator evidence").isEqualTo(JudgmentStatus.PASS);
        assertThat(j.reasoning()).doesNotContain("validator");
    }

    static Map<String, OracleReport> map(Map<String, OracleReport> reports, java.util.function.UnaryOperator<OracleReport> f) {
        Map<String, OracleReport> out = new LinkedHashMap<>();
        reports.forEach((k, v) -> out.put(k, f.apply(v)));
        return out;
    }
}
