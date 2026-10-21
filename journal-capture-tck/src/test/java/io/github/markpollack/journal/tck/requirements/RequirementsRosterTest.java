package io.github.markpollack.journal.tck.requirements;

import io.github.markpollack.judge.ai.requirements.EarsCriterion;
import io.github.markpollack.judge.ai.requirements.Rfc2119Constraint;
import io.github.markpollack.journal.tck.requirements.RequirementDocuments.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The requirement documents load through Agent Judge's actual readers with their complete, unique roster.
 *
 * <p>These tests establish that the documents are well-formed for the readers and that nothing was silently skipped.
 * They establish nothing about whether the implementation meets any rule or criterion; see
 * {@link RequirementsEvidenceTest} for what is and is not assessed.
 */
class RequirementsRosterTest {

    /** The intended rule roster, pinned so that removing or renumbering a rule is a deliberate edit here too. */
    static final List<String> RULE_IDS = IntStream.rangeClosed(1, 32).mapToObj(i -> "RULE-" + i).toList();

    /** Criteria per use case, pinned for the same reason: UC1 recording/source selection, UC2 version, UC3 mapping/preservation, UC4 lifecycle/children, UC5 portable output/testing. */
    static final Map<String, Integer> CRITERIA_PER_USE_CASE = Map.of("UC1", 7, "UC2", 5, "UC3", 12, "UC4", 8, "UC5", 10);

    static final Set<String> KEYWORDS = Set.of("MUST", "MUST NOT", "SHOULD", "SHOULD NOT", "MAY");

    private static final Pattern EARS_OPENING = Pattern.compile("^(When|While|If|Where)\\b.*");

    @Test
    void everyRuleHeadingIsParsedExactlyOnceAndMatchesThePinnedRoster(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<Rfc2119Constraint> parsed = Rfc2119Constraint.from(rules.file());
        List<String> ids = parsed.stream().map(Rfc2119Constraint::id).toList();

        assertThat(RequirementDocuments.rosterProblems(rules.intendedIds(), ids)).isEmpty();
        assertThat(ids).as("pinned roster; edit RULE_IDS deliberately when a rule is added or retired").isEqualTo(RULE_IDS);
        assertThat(parsed).hasSize(RULE_IDS.size());
    }

    @Test
    void everyRuleStatesOneKeywordARequirementAndAReason(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        for (Rfc2119Constraint rule : Rfc2119Constraint.from(rules.file())) {
            assertThat(KEYWORDS).as(rule.id() + " keyword").contains(rule.keyword());
            assertThat(rule.requirement()).as(rule.id() + " requirement").isNotBlank().doesNotStartWith("*");
            assertThat(rule.reason()).as(rule.id() + " reason").isNotBlank();
            assertThat(rule.conditional()).as(rule.id() + " has no applicability clause; the reader never sets one").isFalse();
        }
    }

    @Test
    void everyCriterionHeadingIsParsedExactlyOnceAndMatchesThePinnedCounts(@TempDir Path work) {
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        List<EarsCriterion> parsed = EarsCriterion.from(criteria.file());
        List<String> ids = parsed.stream().map(EarsCriterion::id).toList();

        assertThat(RequirementDocuments.rosterProblems(criteria.intendedIds(), ids)).isEmpty();

        Map<String, Integer> perUseCase = new TreeMap<>();
        for (String id : ids) {
            perUseCase.merge(id.substring(0, id.indexOf('-')), 1, Integer::sum);
        }
        assertThat(perUseCase).as("pinned per-use-case counts; edit CRITERIA_PER_USE_CASE deliberately").isEqualTo(new TreeMap<>(CRITERIA_PER_USE_CASE));
        for (String uc : CRITERIA_PER_USE_CASE.keySet()) {
            List<String> expected = IntStream.rangeClosed(1, CRITERIA_PER_USE_CASE.get(uc)).mapToObj(i -> uc + "-AC" + i).toList();
            assertThat(ids.stream().filter(id -> id.startsWith(uc + "-")).toList()).as(uc + " is numbered contiguously").isEqualTo(expected);
        }
    }

    @Test
    void everyCriterionIsOneEarsSentenceTakenFromTheLineAfterItsHeading(@TempDir Path work) {
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        for (EarsCriterion c : EarsCriterion.from(criteria.file())) {
            assertThat(c.title()).as(c.id() + " title").isNotBlank();
            assertThat(c.requirement()).as(c.id() + " is the requirement sentence, not a link line").doesNotStartWith("Rule:").doesNotStartWith("Tests:").doesNotStartWith("Status:");
            assertThat(EARS_OPENING.matcher(c.requirement()).matches()).as(c.id() + " opens with an EARS trigger: " + c.requirement()).isTrue();
            assertThat(c.requirement()).as(c.id() + " uses shall").contains(" shall ");
            assertThat(c.requirement().strip()).as(c.id() + " is one sentence").endsWith(".").doesNotContain(". ");
            assertThat(c.conditional()).as(c.id() + " has no applicability clause; the reader never sets one").isFalse();
        }
    }

    @Test
    void everyRuleLinkInTheCriteriaNamesARuleThatParses(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        Set<String> ruleIds = Set.copyOf(Rfc2119Constraint.from(rules.file()).stream().map(Rfc2119Constraint::id).toList());
        Matcher m = Pattern.compile("Rule: ([^.\\n]+)\\.").matcher(criteria.text());
        int links = 0;
        while (m.find()) {
            for (String ref : m.group(1).split(",\\s*")) {
                links++;
                assertThat(ruleIds).as("criteria link " + ref).contains(ref.strip());
            }
        }
        assertThat(links).as("the criteria link to rules").isGreaterThanOrEqualTo(CRITERIA_PER_USE_CASE.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void negativeControlRulesDocumentIsSilentlyShrunkByTheReaderAndTheRosterCheckNamesEachSkippedRule(@TempDir Path work) {
        Document control = RequirementDocuments.load(RequirementDocuments.NEGATIVE_RULES, work);
        List<String> parsed = Rfc2119Constraint.from(control.file()).stream().map(Rfc2119Constraint::id).toList();

        assertThat(control.intendedIds()).containsExactly("RULE-1", "RULE-2", "RULE-3");
        assertThat(parsed).as("the reader skips a rule without a reason and a rule without a bold keyword, and reports nothing").containsExactly("RULE-1");
        assertThat(RequirementDocuments.rosterProblems(control.intendedIds(), parsed)).containsExactly(
                "heading RULE-2 was not returned by the reader (malformed or skipped)",
                "heading RULE-3 was not returned by the reader (malformed or skipped)");
    }

    @Test
    void negativeControlCriteriaDocumentIsSilentlyShrunkByTheReaderAndTheRosterCheckNamesEachSkippedCriterion(@TempDir Path work) {
        Document control = RequirementDocuments.load(RequirementDocuments.NEGATIVE_CRITERIA, work);
        List<String> parsed = EarsCriterion.from(control.file()).stream().map(EarsCriterion::id).toList();

        assertThat(control.intendedIds()).containsExactly("UC1-AC1", "UC1-AC2", "UC1-AC3");
        assertThat(parsed).as("a heading without a colon and a heading without a sentence are skipped without complaint").containsExactly("UC1-AC1");
        assertThat(RequirementDocuments.rosterProblems(control.intendedIds(), parsed)).containsExactly(
                "heading UC1-AC2 was not returned by the reader (malformed or skipped)",
                "heading UC1-AC3 was not returned by the reader (malformed or skipped)");
    }
}
