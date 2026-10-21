package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction of the three false acceptances of the 2026-10-21 independent FULL-1 review (FR-01, FR-02, FR-03) on
 * the UNCHANGED oracle, beside the unmodified control. Each test asserts what the oracle at {@code ca90278} actually
 * reports for the mutation: no blocking outcome and acceptance exit 0. These assertions document the defect; the
 * repair commit turns each of them into the detection the review requires.
 */
class FullOneReviewReproductionTest {

    static final String PAIR = OracleFullSourceTest.PAIR;
    static final String STREAM = "main/stream.jsonl";
    static final String TRANSCRIPT = "main/transcript.jsonl";

    @Test
    void controlIsAcceptedWithBothSourcesJudged(@TempDir Path work) {
        OracleReport report = verify(copy(PAIR, work));
        assertThat(report.defects()).isEmpty();
        assertThat(report.outcome("whole_record.coverage", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    /** FR-01: the transcript source entry and all its events are removed; the native transcript is still supplied. */
    @Test
    void fr01OmittingAnEntireSourceShrinksTheDenominatorAndIsStillAccepted(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        omitTranscriptSource(fixture);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.withOutcome(Outcome.UNRESOLVED)).isEmpty();
        assertThat(report.check("whole_record.coverage", TRANSCRIPT)).as("no check at all judges the omitted source").isNull();
        assertThat(report.check("source.available", TRANSCRIPT)).isNull();
        assertThat(OcsfReaderTool.acceptance(report).exit()).as("FALSE ACCEPTANCE (FR-01)").isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    /** FR-02: the package says COMPLETED while its stream source is open and acquisition is declared incomplete. */
    @Test
    void fr02CompletedPackageWithOpenSourceAndIncompleteAcquisitionIsStillAccepted(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        openStreamSourceInsideCompletedPackage(fixture);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("package.state", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("source.inventory", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("acquisition.complete", "main")).as("no acquisition property exists").isNull();
        assertThat(OcsfReaderTool.acceptance(report).exit()).as("FALSE ACCEPTANCE (FR-02)").isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    /** FR-03: the normalized rendered text of an injected-context event is replaced; the whole native record stays intact. */
    @Test
    void fr03CorruptNormalizedContextTextIsStillAccepted(@TempDir Path work) {
        Path fixture = copy(PAIR, work);
        corruptContextText(fixture);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("context.values", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.structure_value", TRANSCRIPT)).as("the raw record is untouched").isEqualTo(Outcome.VERIFIED);
        assertThat(OcsfReaderTool.acceptance(report).exit()).as("FALSE ACCEPTANCE (FR-03)").isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    // ------------------------------------------------------------------------------------------ the mutations

    static void omitTranscriptSource(Path fixture) {
        editEvents(pkg(fixture), events -> events.removeIf(e -> "transcript.jsonl".equals(SchemaCatalog.text(e.member("source_ref"), "artifact"))));
        editManifest(pkg(fixture), run -> {
            List<Json> sources = run.member("sources").elements();
            sources.removeIf(s -> "transcript.jsonl".equals(SchemaCatalog.text(s, "artifact")));
            Map<String, Json> contracts = obj(run.path("shared", "contracts")).members();
            contracts.keySet().removeIf(k -> k.contains("session-transcript"));
        });
    }

    static void openStreamSourceInsideCompletedPackage(Path fixture) {
        editManifest(pkg(fixture), run -> {
            Json.JObject stream = obj(run.member("sources").elements().get(0));
            stream.members().put("final", new Json.JBool(false));
            stream.members().put("state", str("RECORDING"));
            stream.members().remove("sha256");
            stream.members().remove("ended");
            Json.JObject acquisition = obj(run.path("recording", "acquisition"));
            acquisition.members().put("status", str("incomplete"));
            acquisition.members().put("detail", new Json.JArray(List.of(str("stream.jsonl: recording"))));
        });
    }

    static void corruptContextText(Path fixture) {
        editEvents(pkg(fixture), events -> {
            Json env = at(events, "transcript.jsonl", "session_context", 2, 4); // attachment/environment, one rendered block
            obj(env.member("content_blocks").elements().get(0)).members().put("text", str("UNRELATED TEXT THAT THE SOURCE NEVER RENDERED"));
        });
    }
}
