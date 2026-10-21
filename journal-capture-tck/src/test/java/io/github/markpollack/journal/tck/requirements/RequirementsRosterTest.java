package io.github.markpollack.journal.tck.requirements;

import io.github.markpollack.judge.ai.requirements.EarsCriterion;
import io.github.markpollack.judge.ai.requirements.RequirementDocumentException;
import io.github.markpollack.judge.ai.requirements.RequirementDocumentProblem;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The requirement documents load through Agent Judge's actual readers, strictly, with their complete, unique roster.
 *
 * <p>These tests establish that the documents are well-formed for the readers and that nothing was silently skipped:
 * the strict readers ({@code fromStrict}, Agent Judge 0.19.0) refuse a document with a malformed entry, and the
 * independent heading roster is still compared against what the reader returned, so neither check stands alone.
 * They establish nothing about whether the implementation meets any rule or criterion; see
 * {@link RequirementsEvidenceTest} for what is and is not assessed.
 */
class RequirementsRosterTest {

    /** The intended rule roster, pinned so that removing or renumbering a rule is a deliberate edit here too. */
    static final List<String> RULE_IDS = IntStream.rangeClosed(1, 37).mapToObj(i -> "RULE-" + i).toList();

    /** Criteria per use case, pinned for the same reason: UC1 recording/source selection, UC2 version, UC3 mapping/preservation, UC4 lifecycle/children, UC5 portable output/testing. */
    static final Map<String, Integer> CRITERIA_PER_USE_CASE = Map.of("UC1", 7, "UC2", 6, "UC3", 15, "UC4", 8, "UC5", 13);

    static final Set<String> KEYWORDS = Set.of("MUST", "MUST NOT", "SHOULD", "SHOULD NOT", "MAY");

    private static final Pattern EARS_OPENING = Pattern.compile("^(When|While|If|Where)\\b.*");

    @Test
    void everyRuleHeadingIsParsedExactlyOnceStrictlyAndMatchesThePinnedRoster(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        assertThat(Rfc2119Constraint.problems(rules.file())).as("the strict reader finds no malformed rule").isEmpty();
        List<Rfc2119Constraint> parsed = Rfc2119Constraint.fromStrict(rules.file());
        List<String> ids = parsed.stream().map(Rfc2119Constraint::id).toList();

        assertThat(RequirementDocuments.rosterProblems(rules.intendedIds(), ids)).isEmpty();
        assertThat(ids).as("pinned roster; edit RULE_IDS deliberately when a rule is added or retired").isEqualTo(RULE_IDS);
        assertThat(parsed).hasSize(RULE_IDS.size());
        assertThat(Rfc2119Constraint.from(rules.file())).as("permissive and strict agree on a well-formed document").isEqualTo(parsed);
    }

    @Test
    void everyRuleStatesOneKeywordARequirementAndAReasonAndNoneDeclaresAnApplicabilityClause(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        for (Rfc2119Constraint rule : Rfc2119Constraint.fromStrict(rules.file())) {
            assertThat(KEYWORDS).as(rule.id() + " keyword").contains(rule.keyword());
            assertThat(rule.requirement()).as(rule.id() + " requirement").isNotBlank().doesNotStartWith("*");
            assertThat(rule.reason()).as(rule.id() + " reason").isNotBlank();
            assertThat(rule.conditional()).as(rule.id() + " is unconditional: the document declares no Applies when: entry, so NOT_APPLICABLE is not an available answer").isFalse();
            assertThat(rule.applicability()).isNull();
        }
    }

    @Test
    void everyCriterionHeadingIsParsedExactlyOnceStrictlyAndMatchesThePinnedCounts(@TempDir Path work) {
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        assertThat(EarsCriterion.problems(criteria.file())).as("the strict reader finds no malformed criterion").isEmpty();
        List<EarsCriterion> parsed = EarsCriterion.fromStrict(criteria.file());
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
        assertThat(EarsCriterion.from(criteria.file())).as("permissive and strict agree on a well-formed document").isEqualTo(parsed);
    }

    @Test
    void everyCriterionIsOneEarsSentenceTakenFromTheLineAfterItsHeadingAndNoneDeclaresAnApplicabilityClause(@TempDir Path work) {
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        for (EarsCriterion c : EarsCriterion.fromStrict(criteria.file())) {
            assertThat(c.title()).as(c.id() + " title").isNotBlank();
            assertThat(c.requirement()).as(c.id() + " is the requirement sentence, not a link line").doesNotStartWith("Rule:").doesNotStartWith("Tests:").doesNotStartWith("Status:");
            assertThat(EARS_OPENING.matcher(c.requirement()).matches()).as(c.id() + " opens with an EARS trigger: " + c.requirement()).isTrue();
            assertThat(c.requirement()).as(c.id() + " uses shall").contains(" shall ");
            assertThat(c.requirement().strip()).as(c.id() + " is one sentence").endsWith(".").doesNotContain(". ");
            assertThat(c.conditional()).as(c.id() + " is unconditional: the document declares no Applies when: entry").isFalse();
            assertThat(c.applicability()).isNull();
        }
    }

    @Test
    void everyRuleLinkInTheCriteriaNamesARuleThatParses(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        Set<String> ruleIds = Set.copyOf(Rfc2119Constraint.fromStrict(rules.file()).stream().map(Rfc2119Constraint::id).toList());
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

    /**
     * Malformed-entry control, rules: the permissive reader still shrinks the roster silently (the independent heading
     * comparison names each skipped rule, as before); the strict reader refuses the document and names the same
     * entries with their lines, so a document nobody wrote cannot be evaluated by accident.
     */
    @Test
    void negativeControlRulesDocumentIsRefusedByTheStrictReaderAndShrunkByThePermissiveOne(@TempDir Path work) {
        Document control = RequirementDocuments.load(RequirementDocuments.NEGATIVE_RULES, work);
        List<String> permissive = Rfc2119Constraint.from(control.file()).stream().map(Rfc2119Constraint::id).toList();

        assertThat(control.intendedIds()).containsExactly("RULE-1", "RULE-2", "RULE-3");
        assertThat(permissive).as("the permissive reader skips a rule without a reason and a rule without a bold keyword").containsExactly("RULE-1");
        assertThat(RequirementDocuments.rosterProblems(control.intendedIds(), permissive)).containsExactly(
                "heading RULE-2 was not returned by the reader (malformed or skipped)",
                "heading RULE-3 was not returned by the reader (malformed or skipped)");

        List<RequirementDocumentProblem> problems = Rfc2119Constraint.problems(control.file());
        assertThat(problems.stream().map(RequirementDocumentProblem::id).toList()).as("strict diagnostics name the malformed rules").containsExactly("RULE-2", "RULE-3");
        assertThat(problems).allSatisfy(p -> {
            assertThat(p.line()).isPositive();
            assertThat(p.reason()).isNotBlank();
        });
        assertThatThrownBy(() -> Rfc2119Constraint.fromStrict(control.file()))
                .isInstanceOf(RequirementDocumentException.class)
                .satisfies(e -> assertThat(((RequirementDocumentException) e).problems()).isEqualTo(problems))
                .hasMessageContaining("RULE-2").hasMessageContaining("RULE-3");
    }

    /** Malformed-entry control, criteria: the same pair of behaviors for the EARS reader. */
    @Test
    void negativeControlCriteriaDocumentIsRefusedByTheStrictReaderAndShrunkByThePermissiveOne(@TempDir Path work) {
        Document control = RequirementDocuments.load(RequirementDocuments.NEGATIVE_CRITERIA, work);
        List<String> permissive = EarsCriterion.from(control.file()).stream().map(EarsCriterion::id).toList();

        assertThat(control.intendedIds()).containsExactly("UC1-AC1", "UC1-AC2", "UC1-AC3");
        assertThat(permissive).as("the permissive reader skips a heading without a colon and a heading without a sentence").containsExactly("UC1-AC1");
        assertThat(RequirementDocuments.rosterProblems(control.intendedIds(), permissive)).containsExactly(
                "heading UC1-AC2 was not returned by the reader (malformed or skipped)",
                "heading UC1-AC3 was not returned by the reader (malformed or skipped)");

        List<RequirementDocumentProblem> problems = EarsCriterion.problems(control.file());
        assertThat(problems).as("strict diagnostics for the malformed criteria").isNotEmpty();
        assertThat(problems).allSatisfy(p -> {
            assertThat(p.line()).isPositive();
            assertThat(p.reason()).isNotBlank();
        });
        assertThatThrownBy(() -> EarsCriterion.fromStrict(control.file()))
                .isInstanceOf(RequirementDocumentException.class)
                .satisfies(e -> assertThat(((RequirementDocumentException) e).problems()).isEqualTo(problems));
        // the independent roster comparison catches whatever the strict diagnostics do not name explicitly
        Set<String> named = Set.copyOf(problems.stream().map(RequirementDocumentProblem::id).filter(java.util.Objects::nonNull).toList());
        assertThat(named).isSubsetOf("UC1-AC1", "UC1-AC2", "UC1-AC3");
        assertThat(named).doesNotContain("UC1-AC1");
    }

    /**
     * Applicability control: an explicit {@code Applies when:} entry is loaded as the requirement's applicability
     * (what makes NOT_APPLICABLE an available answer); a requirement without one is unconditional. The agent-journal
     * documents declare none, as the tests above pin.
     */
    @Test
    void anExplicitAppliesWhenEntryIsLoadedAsApplicabilityAndItsAbsenceMeansUnconditional(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.APPLICABILITY_RULES, work);
        List<Rfc2119Constraint> parsedRules = Rfc2119Constraint.fromStrict(rules.file());
        assertThat(parsedRules.stream().map(Rfc2119Constraint::id).toList()).isEqualTo(rules.intendedIds());
        assertThat(parsedRules.get(0).conditional()).isTrue();
        assertThat(parsedRules.get(0).applicability()).isEqualTo("the CLI offers a native session file and no stream.");
        assertThat(parsedRules.get(0).requirement()).as("the applicability entry is not folded into the statement").doesNotContain("Applies when");
        assertThat(parsedRules.get(1).conditional()).isFalse();
        assertThat(parsedRules.get(1).applicability()).isNull();

        Document criteria = RequirementDocuments.load(RequirementDocuments.APPLICABILITY_CRITERIA, work);
        List<EarsCriterion> parsedCriteria = EarsCriterion.fromStrict(criteria.file());
        assertThat(parsedCriteria.stream().map(EarsCriterion::id).toList()).isEqualTo(criteria.intendedIds());
        assertThat(parsedCriteria.get(0).conditional()).isTrue();
        assertThat(parsedCriteria.get(0).applicability()).isEqualTo("the CLI offers no stream output.");
        assertThat(parsedCriteria.get(0).requirement()).startsWith("When the CLI writes a native session file");
        assertThat(parsedCriteria.get(1).conditional()).isFalse();
    }
}
