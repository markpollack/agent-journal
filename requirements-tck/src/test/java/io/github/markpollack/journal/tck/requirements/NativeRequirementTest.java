package io.github.markpollack.journal.tck.requirements;

import io.github.markpollack.judge.ai.requirements.EarsCriterion;
import io.github.markpollack.judge.ai.requirements.EarsRequirement;
import io.github.markpollack.judge.ai.requirements.Rfc2119Constraint;
import io.github.markpollack.judge.ai.requirements.Rfc2119Requirement;
import io.github.markpollack.judge.execution.RequirementRequest;
import io.github.markpollack.judge.requirement.Requirement;
import io.github.markpollack.journal.tck.requirements.RequirementDocuments.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Judge's native requirement types constructed strictly from the documents: identity, revision and source
 * provenance are what a retained result would carry, so they are checked here against the document bytes rather
 * than assumed.
 */
class NativeRequirementTest {

    @Test
    void rfcNativeRequirementsCarryTheRosterRevisionAndDocumentDigest(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<Rfc2119Requirement> requirements = Rfc2119Requirement.fromStrict(rules.file(), rules.revision());

        assertThat(requirements.stream().map(Rfc2119Requirement::id).toList()).isEqualTo(RequirementsRosterTest.RULE_IDS);
        String digest = RequirementDocuments.sha256(rules.bytes());
        for (Rfc2119Requirement r : requirements) {
            Requirement.validate(r);
            assertThat(r.revision()).isEqualTo(rules.revision());
            assertThat(r.source().nativeId()).as(r.id() + " document-local identity").isEqualTo(r.id());
            assertThat(r.source().artifact().sha256()).as(r.id() + " source snapshot is the document's bytes").isEqualTo(digest);
            assertThat(r.source().artifact().id()).isEqualTo(rules.file().toString());
            assertThat(r.text()).startsWith(r.id() + ": " + r.specification().keyword() + " ");
            var request = new RequirementRequest<>(r, "evidence placeholder; no judgment is made here");
            assertThat(request.requirement()).isSameAs(r);
        }
    }

    @Test
    void earsNativeRequirementsCarryTheSentenceTitleRevisionAndDocumentDigest(@TempDir Path work) {
        Document criteria = RequirementDocuments.load(RequirementDocuments.CRITERIA, work);
        List<EarsRequirement> requirements = EarsRequirement.fromStrict(criteria.file(), criteria.revision());
        List<EarsCriterion> parsed = EarsCriterion.fromStrict(criteria.file());

        assertThat(requirements).hasSameSizeAs(parsed);
        String digest = RequirementDocuments.sha256(criteria.bytes());
        for (int i = 0; i < parsed.size(); i++) {
            EarsRequirement r = requirements.get(i);
            EarsCriterion c = parsed.get(i);
            Requirement.validate(r);
            assertThat(r.id()).isEqualTo(c.id());
            assertThat(r.revision()).isEqualTo(criteria.revision());
            assertThat(r.text()).as(r.id() + " text is the verbatim sentence").isEqualTo(c.requirement());
            assertThat(r.specification().title()).isEqualTo(c.title());
            assertThat(r.specification().requirement()).isEqualTo(c.requirement());
            assertThat(r.source().nativeId()).isEqualTo(c.id());
            assertThat(r.source().artifact().sha256()).isEqualTo(digest);
        }
    }

    @Test
    void sameBytesYieldEquivalentRequirementsAndAnEditedDocumentDoesNot(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        Rfc2119Requirement first = Rfc2119Requirement.fromStrict(rules.file(), rules.revision()).get(0);
        Rfc2119Requirement again = Rfc2119Requirement.fromStrict(rules.file(), rules.revision()).get(0);
        assertThat(Requirement.equivalent(first, again)).isTrue();

        Document control = RequirementDocuments.load(RequirementDocuments.APPLICABILITY_RULES, work.resolve("other"));
        Rfc2119Requirement other = Rfc2119Requirement.fromStrict(control.file(), control.revision()).get(0);
        assertThat(other.id()).isEqualTo(first.id());
        assertThat(Requirement.equivalent(first, other)).as("same identifier, different document bytes and revision").isFalse();
    }

    /**
     * Inverted on 2026-10-21 with Agent Judge 0.19.0 (AJR-1): the native specification now carries the complete
     * statement of every rule, and the prompt rendering contains it. The previous version of this test recorded the
     * reader placing the clause-shortened title in the specification and listed the rules it truncated; none remain.
     */
    @Test
    void everyNativeRfcSpecificationCarriesTheCompleteStatementAndThePromptRendersIt(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<Rfc2119Constraint> constraints = Rfc2119Constraint.fromStrict(rules.file());
        List<Rfc2119Requirement> requirements = Rfc2119Requirement.fromStrict(rules.file(), rules.revision());
        assertThat(requirements).hasSameSizeAs(constraints);
        int shorterThanSentence = 0;
        for (int i = 0; i < constraints.size(); i++) {
            Rfc2119Constraint c = constraints.get(i);
            Rfc2119Requirement r = requirements.get(i);
            assertThat(r.id()).isEqualTo(c.id());
            assertThat(r.specification().requirement()).as(c.id() + " native specification is the complete statement, not the title").isEqualTo(c.requirement());
            assertThat(r.specification().keyword()).isEqualTo(c.keyword());
            assertThat(r.specification().reason()).isEqualTo(c.reason());
            assertThat(r.text()).as(c.id() + " text is the full prompt rendering").isEqualTo(c.asPrompt()).contains(c.requirement()).contains(c.reason());
            if (!c.title().equals(c.requirement())) {
                shorterThanSentence++;
                assertThat(r.specification().requirement()).as(c.id() + " is not truncated to its title").isNotEqualTo(c.title());
            }
        }
        assertThat(shorterThanSentence).as("the control is meaningful: titles shorter than their sentences exist in this document").isPositive();
    }
}
