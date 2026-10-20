package io.github.markpollack.journal.tck.verify;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.verify.VerifyFixtures.copy;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.editEvents;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.rootRun;
import static io.github.markpollack.journal.tck.verify.VerifyFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JV-1 / JV-3 offline gate on the committed fixtures: the verifier loads the native stream and the persisted package
 * independently, judges every property per owner, and the only outcomes other than VERIFIED and NOT_APPLICABLE
 * are the known, named defects and the declared gap. Those expectations pin the defects; passing this test does not
 * turn any of them into a conformance pass (see {@code README.md} beside the fixtures).
 */
class ClaudeStreamVerifierFixtureTest {

    private static List<String> notVerified(VerificationReport report) {
        return report.checks().stream()
                .filter(c -> c.outcome() != Outcome.VERIFIED && c.outcome() != Outcome.NOT_APPLICABLE)
                .map(c -> c.property() + " " + c.owner() + " " + c.outcome())
                .toList();
    }

    @Test
    void singleResultParallelCallsHasNoDefectAndOneDeclaredGap(@TempDir Path work) {
        VerificationReport report = verify(copy("single-result-parallel-calls", work));
        assertThat(notVerified(report)).containsExactly("dropped_lines.rate_limit_event main UNSUPPORTED");
        assertThat(report.summary().get("PRESERVATION").get("noDefects")).isEqualTo(true);
        assertThat(report.summary().get("NORMALIZATION").get("noDefects")).isEqualTo(true);
        // the real content was judged, not an empty set
        assertThat(report.outcome("tool_calls.multiplicity", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("tool_calls.multiplicity", "main").detail()).startsWith("3 tool call(s)");
        assertThat(report.outcome("tool_calls.result_text", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source_records.payload_text", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("vendor_events.payload_text", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("tool_timing.declared", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("capture.invocations", "main")).isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void foregroundSubagentReportsExactlyTheKnownDefects(@TempDir Path work) {
        VerificationReport report = verify(copy("foreground-subagent-one-result", work));
        String child = "child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ";
        assertThat(notVerified(report)).containsExactlyInAnyOrder(
                "dropped_lines.rate_limit_event main UNSUPPORTED",
                "tool_calls.result_structured main DEFECTIVE", // R13-04
                "tool_calls.input_representation " + child + " REPRESENTATION_DIFFERS", // R13-09
                "source_records.invocation_reference " + child + " DEFECTIVE", // R13-10
                "tool_timing.declared " + child + " DEFECTIVE"); // R13-11
        assertThat(report.check("tool_calls.result_structured", "main").detail()).contains("R13-04").contains("array content stored as a string");
        assertThat(report.check("tool_calls.input_representation", child).detail()).contains("$.limit").contains("expected spelling 1, actual 1.0");
        // the value behind the spelling difference is equal, and the whole record keeps the original text
        assertThat(report.outcome("tool_calls.input_value", child)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source_records.payload_text", child)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source_records.payload_value", child)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source_records.multiplicity", child)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("tool_calls.duration_value", child)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("children.linked", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("children.listed_on_parent", "main")).isEqualTo(Outcome.VERIFIED);
        // a defect is never folded into the category aggregate
        assertThat(report.summary().get("NORMALIZATION").get("noDefects")).isEqualTo(false);
        assertThat(report.summary().get("PRESERVATION").get("noDefects")).isEqualTo(false);
        assertThat(report.summary().get("EXPLANATION").get("noDefects")).isEqualTo(false);
        assertThat(report.summary().get("COMPLETENESS").get("noDefects")).isEqualTo(true);
        assertThat(report.toMap().get("notVerified")).asList().hasSize(4);
    }

    @Test
    void backgroundSubagentWithTwoResultLinesIsOneInvocation(@TempDir Path work) {
        VerificationReport report = verify(copy("background-subagent-two-results-a", work));
        String child = "child:toolu_01SDdexyBphVNPU4MuZVnQyF";
        assertThat(notVerified(report)).containsExactlyInAnyOrder(
                "dropped_lines.rate_limit_event main UNSUPPORTED",
                "tool_calls.result_structured main DEFECTIVE",
                "tool_calls.input_representation " + child + " REPRESENTATION_DIFFERS",
                "source_records.invocation_reference " + child + " DEFECTIVE",
                "tool_timing.declared " + child + " DEFECTIVE");
        assertThat(report.check("capture.invocations", "main").detail()).startsWith("1 invocation(s)");
        assertThat(report.check("vendor_events.multiplicity", "main").outcome()).isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void coreOnlyPackageIsJudgedIdentically(@TempDir Path work) throws Exception {
        Path fixture = copy("foreground-subagent-one-result", work);
        VerificationReport full = verify(fixture);
        Path core = ClaudePairsVerificationDriverTest.copyCore(fixture.resolve("journal"), work.resolve("core"));
        VerificationReport coreReport = ClaudeStreamVerifier.verify(ClaudeStreamOracle.read(fixture.resolve("stream.jsonl")),
                CandidatePackage.read(CandidatePackage.findRootRun(core)));
        assertThat(coreReport.checks().stream().map(c -> c.property() + c.owner() + c.outcome()).toList())
                .containsExactlyElementsOf(full.checks().stream().map(c -> c.property() + c.owner() + c.outcome()).toList());
        // and the candidate reader opened nothing but run.json and events.jsonl
        CandidatePackage candidate = CandidatePackage.read(rootRun(fixture));
        assertThat(candidate.filesRead()).allMatch(f -> f.endsWith("run.json") || f.endsWith("events.jsonl"));
        assertThat(candidate.filesRead()).hasSize(4);
        assertThat(Files.exists(rootRun(fixture).resolve("analysis.jsonl"))).as("the optional file exists but was not read").isTrue();
    }

    @Test
    void reportNamesTheExactOracleAndCandidateAndItsCoverage(@TempDir Path work) {
        Path fixture = copy("foreground-subagent-one-result", work);
        VerificationReport report = verify(fixture);
        java.util.Map<String, Object> map = report.toMap();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> oracle = (java.util.Map<String, Object>) map.get("oracle");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> stream = ((List<java.util.Map<String, Object>>) oracle.get("streams")).get(0);
        assertThat(stream.get("sha256")).isEqualTo("bf6177b0d1369d16ad4ac2e770bfe6a2920134ef09875dd355bc06ad6c9748fe");
        assertThat(stream.get("owners")).asList().containsExactly("main", "child:toolu_01BnEuCUmZ2Vu1LpADzksmrJ");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> candidate = (java.util.Map<String, Object>) map.get("candidate");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> declared = (java.util.Map<String, Object>) candidate.get("declared");
        assertThat(declared.get("cliVersion")).isEqualTo("2.1.299");
        assertThat(declared.get("preservation")).isEqualTo("partial");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> coverage = (java.util.Map<String, Object>) map.get("coverage");
        assertThat(coverage.get("unsupported")).asList().isNotEmpty();
        assertThat(map.get("denominator")).isNotNull();
    }

    @Test
    void refusesAnEventsSchemaVersionItDoesNotImplement(@TempDir Path work) {
        Path fixture = copy("single-result-parallel-calls", work);
        editEvents(rootRun(fixture), events -> VerifyFixtures.obj(events.get(0)).members().put("schemaVersion", VerifyFixtures.num("2")));
        assertThatThrownBy(() -> verify(fixture)).isInstanceOf(IllegalStateException.class).hasMessageContaining("schema version");
    }
}
