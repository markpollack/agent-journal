package io.github.markpollack.journal.tck.requirements;

import io.github.markpollack.judge.judgment.Judgment;
import io.github.markpollack.judge.judgment.JudgmentStatus;
import io.github.markpollack.journal.tck.ocsf.ExpectedSources;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.Corpus;
import io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.Evidence;
import io.github.markpollack.journal.tck.verify.Check;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.CLAUDE_CHILD;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.CODEX_STREAM;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.CRITERION_EVIDENCE;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.judge;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.map;
import static io.github.markpollack.journal.tck.requirements.RequirementsEvidenceTest.verifyFixtures;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The review findings on the REQ-1 requirements reporter ({@code a6dce21}), first reproduced against the unchanged
 * reporter (commit {@code fe2279d}, where every assertion below read PASS) and now pinned at their corrected
 * outcomes as regressions of the repaired reporter.
 *
 * <ul>
 * <li>RR-01: the content criterion covered responses and prompts but mapped only response properties. It is now
 * split: UC3-AC2 (responses) and UC3-AC13 (prompts). Prompt evidence absent is ERROR on UC3-AC13 and DEFECTIVE
 * prompt evidence is FAIL, while UC3-AC2 stays PASS on its own obligations.</li>
 * <li>RR-02: removing every check of one required property or of one required owner is an incomplete instrument:
 * ERROR, never PASS.</li>
 * <li>RR-03: the interruption criterion is not assessed on complete runs; it needs a fixture whose frozen expected
 * sources declare an incomplete run.</li>
 * <li>RR-04: UC5-AC3 is narrowed to the Java catalog check it actually maps; the upstream validator is UC5-AC11 and
 * is reported not assessed because no Maven check runs it.</li>
 * </ul>
 */
class RequirementsReporterReproductionTest {

    private static final String CHILD_OWNER = "child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ/stream.jsonl";

    @Test
    void rr01PromptEvidenceAbsentIsAnErrorAndDefectiveIsAFailOnThePromptCriterionWhileResponsesPass(@TempDir Path work) {
        Corpus corpus = verifyFixtures(work);
        assertThat(CRITERION_EVIDENCE.get("UC3-AC2").properties()).as("responses only, by design since the split").containsExactly("population.responses", "response.values");
        assertThat(CRITERION_EVIDENCE.get("UC3-AC13").properties()).as("prompts have their own criterion").containsExactly("population.prompts", "prompt.values");
        for (OracleReport r : corpus.reports().values()) {
            assertThat(r.checks()).anyMatch(c -> c.property().equals("prompt.values"));
        }

        Corpus noPrompts = corpus.with(map(corpus.reports(), r -> r.filtered(c -> !c.property().startsWith("prompt.") && !c.property().equals("population.prompts"))));
        assertThat(judge("UC3-AC2", CRITERION_EVIDENCE.get("UC3-AC2"), noPrompts).status()).as("responses are established on their own obligations").isEqualTo(JudgmentStatus.PASS);
        Judgment absent = judge("UC3-AC13", CRITERION_EVIDENCE.get("UC3-AC13"), noPrompts);
        assertThat(absent.status()).as("REPAIRED: no prompt evidence is an incomplete instrument").isEqualTo(JudgmentStatus.ERROR);
        assertThat(absent.reasoning()).contains("population.prompts [main/stream.jsonl]").contains("prompt.values [" + CHILD_OWNER + "]");

        Corpus badPrompts = corpus.with(map(corpus.reports(), r -> r.replaced(c -> c.property().equals("prompt.values"),
                c -> new Check(c.category(), c.property(), c.owner(), Check.Outcome.DEFECTIVE, "synthetic: prompt text altered"))));
        Judgment defective = judge("UC3-AC13", CRITERION_EVIDENCE.get("UC3-AC13"), badPrompts);
        assertThat(defective.status()).as("REPAIRED: DEFECTIVE prompt evidence fails the prompt criterion").isEqualTo(JudgmentStatus.FAIL);
        assertThat(judge("UC3-AC2", CRITERION_EVIDENCE.get("UC3-AC2"), badPrompts).status()).as("and leaves the response criterion's own evidence intact").isEqualTo(JudgmentStatus.PASS);
    }

    @Test
    void rr02RemovingOneRequiredPropertyOrOneRequiredOwnerIsAnErrorNotAPass(@TempDir Path work) {
        Corpus corpus = verifyFixtures(work);
        Evidence evidence = CRITERION_EVIDENCE.get("UC3-AC1");
        assertThat(evidence.properties()).contains("result.owner");
        Judgment control = judge("UC3-AC1", evidence, corpus);
        assertThat(control.status()).as(control.reasoning()).isEqualTo(JudgmentStatus.PASS);
        assertThat(control.checks()).as("every property on every owner of the matched fixtures: 5 properties x 16 owners (codex synthetic 1, claude child 2, interrupted 1, live features 2, live denial 1, live cancelled 1, live MCP 1, live codex 1, live codex cancelled 1, antigravity features, denial, interrupt-open, subagent and MCP-denied 1 each); the untested-version fixture is outside the scenario").hasSize(80);

        Judgment missingProperty = judge("UC3-AC1", evidence, corpus.with(map(corpus.reports(), r -> r.filtered(c -> !c.property().equals("result.owner")))));
        assertThat(missingProperty.status()).as("REPAIRED: result.owner never appears").isEqualTo(JudgmentStatus.ERROR);
        assertThat(missingProperty.reasoning()).contains("16 required check(s) missing").contains("result.owner [main/stream.jsonl]");

        assertThat(corpus.reports().get(CLAUDE_CHILD).checks()).anyMatch(c -> c.owner().equals(CHILD_OWNER));
        Judgment missingOwner = judge("UC3-AC1", evidence, corpus.with(map(corpus.reports(), r -> r.filtered(c -> !c.owner().equals(CHILD_OWNER)))));
        assertThat(missingOwner.status()).as("REPAIRED: the child core, an owner the frozen manifest implies, has no check").isEqualTo(JudgmentStatus.ERROR);
        assertThat(missingOwner.reasoning()).contains("5 required check(s) missing").contains(CHILD_OWNER);
    }

    @Test
    void rr03InterruptionCriterionIsNotAssessedOnCompleteRunsAndScopedToTheInterruptedFixtureOtherwise(@TempDir Path work) {
        Corpus all = verifyFixtures(work);
        Corpus complete = RequirementsEvidenceTest.completeRunsOnly(all);
        for (String fixture : List.of(CODEX_STREAM, CLAUDE_CHILD)) {
            assertThat(complete.frozen().get(fixture).complete()).as(fixture + " is frozen as a complete run; nothing was interrupted or cut").isTrue();
        }
        Judgment j = judge("UC4-AC5", CRITERION_EVIDENCE.get("UC4-AC5"), complete);
        assertThat(j.status()).as("REPAIRED: baseline state checks on completed runs do not establish an interruption criterion").isEqualTo(JudgmentStatus.ABSTAIN);
        assertThat(j.reasoning()).contains("not assessed").contains("no committed fixture exercises").contains("incomplete run");
        assertThat(j.checks()).as("no obligation was evaluated on a fixture that does not exercise the scenario").isEmpty();
        assertThat(judge("UC4-AC3", CRITERION_EVIDENCE.get("UC4-AC3"), complete).status()).isEqualTo(JudgmentStatus.ABSTAIN);

        assertThat(all.frozen().get(RequirementsEvidenceTest.CLAUDE_INTERRUPTED).complete()).as("the replay-interrupted fixture is frozen as incomplete").isFalse();
        Judgment scoped = judge("UC4-AC5", CRITERION_EVIDENCE.get("UC4-AC5"), all);
        assertThat(scoped.status()).withFailMessage(scoped.reasoning()).isEqualTo(JudgmentStatus.PASS);
        List<String> completeFixtures = all.frozen().entrySet().stream().filter(e -> e.getValue().complete()).map(java.util.Map.Entry::getKey).toList();
        List<String> interruptedFixtures = all.frozen().entrySet().stream().filter(e -> !e.getValue().complete()).map(java.util.Map.Entry::getKey).toList();
        assertThat(interruptedFixtures).contains(RequirementsEvidenceTest.CLAUDE_INTERRUPTED, RequirementsEvidenceTest.LIVE_CANCELLED, RequirementsEvidenceTest.LIVE_CODEX_CANCELLED);
        assertThat(scoped.reasoning()).contains("scoped to").contains(RequirementsEvidenceTest.CLAUDE_INTERRUPTED)
                .contains("fixtures not exercising the scenario: [" + String.join(", ", completeFixtures) + "]");
        assertThat(scoped.checks()).as("only the interrupted or cancelled fixtures' obligations were evaluated").allMatch(c -> interruptedFixtures.stream().anyMatch(f -> c.id().startsWith(f)));
    }

    @Test
    void rr04JavaCatalogCheckAndUpstreamValidatorAreSeparateCriteria(@TempDir Path work) {
        Corpus corpus = verifyFixtures(work);
        assertThat(CRITERION_EVIDENCE.get("UC5-AC3").properties()).containsExactly("package.readable");
        assertThat(CRITERION_EVIDENCE).as("REPAIRED: no oracle property claims the upstream validator ran").doesNotContainKey("UC5-AC11");
        for (OracleReport r : corpus.reports().values()) {
            assertThat(r.checks()).noneMatch(c -> c.property().startsWith("ocsf."));
        }
        Judgment j = judge("UC5-AC3", CRITERION_EVIDENCE.get("UC5-AC3"), corpus);
        assertThat(j.status()).isEqualTo(JudgmentStatus.PASS);
        assertThat(j.reasoning()).as("the pass is scoped to the Java reader's check").contains("package.readable [main]").contains("scoped to");
    }
}
