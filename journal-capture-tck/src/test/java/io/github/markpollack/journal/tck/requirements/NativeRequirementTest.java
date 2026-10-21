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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Judge's native requirement types constructed from the documents: identity, revision and source provenance
 * are what a retained result would carry, so they are checked here against the document bytes rather than assumed.
 */
class NativeRequirementTest {

    @Test
    void rfcNativeRequirementsCarryTheRosterRevisionAndDocumentDigest(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<Rfc2119Requirement> requirements = Rfc2119Requirement.from(rules.file(), rules.revision());

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
        List<EarsRequirement> requirements = EarsRequirement.from(criteria.file(), criteria.revision());
        List<EarsCriterion> parsed = EarsCriterion.from(criteria.file());

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
        Rfc2119Requirement first = Rfc2119Requirement.from(rules.file(), rules.revision()).get(0);
        Rfc2119Requirement again = Rfc2119Requirement.from(rules.file(), rules.revision()).get(0);
        assertThat(Requirement.equivalent(first, again)).isTrue();

        Document control = RequirementDocuments.load(RequirementDocuments.NEGATIVE_RULES, work.resolve("other"));
        Rfc2119Requirement other = Rfc2119Requirement.from(control.file(), control.revision()).get(0);
        assertThat(other.id()).isEqualTo(first.id());
        assertThat(Requirement.equivalent(first, other)).as("same identifier, different document bytes and revision").isFalse();
    }

    /**
     * Observed reader behavior, recorded rather than worked around: {@code Rfc2119Requirement.from} places the
     * clause-shortened {@link Rfc2119Constraint#title()} in {@code specification().requirement()} while the full
     * sentence survives in {@code text()}. A judge that renders the native specification would therefore see a
     * truncated rule. This test names the rules affected so the gap is visible; the fix belongs to Agent Judge.
     */
    @Test
    void nativeRfcSpecificationCarriesTheShortTitleWhileTextCarriesTheFullSentence(@TempDir Path work) {
        Document rules = RequirementDocuments.load(RequirementDocuments.RULES, work);
        List<Rfc2119Constraint> constraints = Rfc2119Constraint.from(rules.file());
        List<Rfc2119Requirement> requirements = Rfc2119Requirement.from(rules.file(), rules.revision());
        List<String> truncated = new ArrayList<>();
        for (int i = 0; i < constraints.size(); i++) {
            Rfc2119Constraint c = constraints.get(i);
            Rfc2119Requirement r = requirements.get(i);
            assertThat(r.text()).as(c.id() + " text is the full prompt rendering").isEqualTo(c.asPrompt()).contains(c.requirement());
            assertThat(r.specification().requirement()).as(c.id() + " native specification holds the title").isEqualTo(c.title());
            if (!c.title().equals(c.requirement())) {
                truncated.add(c.id());
            }
        }
        assertThat(truncated).as("rules whose native specification is shorter than the sentence; reported as an Agent Judge gap, not hidden").isNotEmpty();
    }
}
