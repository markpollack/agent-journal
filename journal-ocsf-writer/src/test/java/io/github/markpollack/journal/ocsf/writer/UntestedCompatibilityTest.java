package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CHILD;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.UNTESTED;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStreamStdout;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeDir;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.open;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * RULE-33 / UC2-AC5 / UC1-AC5 through the writer: a stream whose reported version matches no compatibility entry is
 * retained whole and flagged untested; nothing after the version report is normalized, the nearest entry's mapper is
 * not consulted, the native session file is not selected, the file declares the selection history, the reader warns,
 * and the independent oracle reports the source's compatibility as UNSUPPORTED (an honest absence of normalization),
 * not as a pass. The labelled synthetic fixture {@code syn-claude-untested-version} is the committed form.
 */
class UntestedCompatibilityTest {

    @Test
    void aVersionNoEntryCoversIsRetainedFlaggedAndNotNormalized(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        RunDescriptor spec = run(UNTESTED);
        assertThat(spec.cli().version()).as("the version is not known at launch; the stream reports it").isNull();
        try (OcsfRunWriter w = OcsfRunWriter.open(core, spec, SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeOf(UNTESTED, "stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).as(p.diagnostics.toString()).isEmpty();
        assertThat(p.manifest.cliVersion()).isEqualTo("2.1.999");

        // the selection history: version_unknown with the source, then no_match once record 1 reported 2.1.999
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).member("status").text()).isEqualTo("version_unknown");
        assertThat(history.get(0).member("records_before_selection").scalarText()).isEqualTo("0");
        Json inForce = history.get(1);
        assertThat(inForce.member("status").text()).isEqualTo("no_match");
        assertThat(inForce.member("entry_id")).as("no entry is named: the nearest one is not selected").isNull();
        assertThat(inForce.member("records_before_selection").scalarText()).isEqualTo("1");
        assertThat(inForce.member("normalizes").text()).isEqualTo("no");
        assertThat(inForce.member("detail").text()).contains("2.1.999").contains("claude-code/stream-json/2.1.304-stdout [2.1.304, 2.1.304]").contains("nearest entry is not selected").contains("RULE-33");
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).isEqualTo("no_match");

        // record 1 (the init record that carried the version) was mapped under the profile's contract before the version was known;
        // every later record is a whole-record source_record under the untested-compatibility contract: no portable field is populated
        int normalizedAfterFirst = 0;
        int retained = 0;
        for (Event e : p.events) {
            if (e.recordIndex() != null && e.recordIndex() > 1) {
                assertThat(e.className).as("record " + e.recordIndex()).isEqualTo("source_record");
                assertThat(e.contractUid()).isEqualTo("agent-journal/untested-compatibility/0.1");
                assertThat(e.text("journal", "rule_uid")).isEqualTo("compat.no_match->source_record");
                assertThat(e.text("basis")).contains("retained whole");
                assertThat(e.get("data")).as("the whole record is kept").isNotNull();
                retained++;
            }
            else if (!"source_record".equals(e.className)) {
                normalizedAfterFirst++;
            }
        }
        assertThat(retained).as("every record after the init record: the stream has 27").isEqualTo(26);
        assertThat(normalizedAfterFirst).as("only the init record's session_context was normalized").isEqualTo(1);
        assertThat(p.children).as("no child core: owner routing is normalization, and nothing after the selection was normalized").isEmpty();
        assertThat(p.manifest.sources()).as("the stream remains the only declared source; no native session file was selected").hasSize(1);
        assertThat(p.manifest.source("stream.jsonl").member("profile").text()).isEqualTo("claude_stream");

        // the reader warns; it does not claim support, and it does not raise the single-version contract warning on top
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).hasSize(1);
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH").get(0).detail()).contains("2.1.999").contains("retained");

        // the family inventory states the disposition of every retained family
        Json families = p.manifest.source("stream.jsonl").member("family_inventory");
        for (var f : ((Json.JObject) families).members().entrySet()) {
            String disposition = f.getValue().member("disposition").text();
            if (!f.getKey().equals("system/init")) {
                assertThat(disposition).as(f.getKey()).contains("untested compatibility").contains("not normalized");
            }
        }

        // the independent oracle against the frozen bytes: compatibility UNSUPPORTED (honest), retention VERIFIED, no defect; the
        // normalized populations are empty or absent, never claimed
        OracleReport report = verify(core, nativeDir(work, UNTESTED, "stream.jsonl", -1));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.check("source.compatibility", "main/stream.jsonl").detail()).contains("retained and flagged untested");
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    /** Negative control: the same stream normalized under the nearest entry's mapper is a defect the oracle sees, not a pass. */
    @Test
    void normalizingAfterANoMatchSelectionIsADefect(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(UNTESTED), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeOf(UNTESTED, "stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        // forge: rewrite the in-force compatibility declaration of a package whose records WERE normalized (the tested 2.1.299 fixture)
        // to claim no_match; the oracle must notice that normalized events follow the selection
        Path forged = work.resolve("forged");
        try (OcsfRunWriter w = OcsfRunWriter.open(forged, run(CHILD), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(WriterTestSupport.claudeStream()), nativeOf(CHILD, "stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        Path events = forged.resolve("events.jsonl");
        String text = Files.readString(events, StandardCharsets.UTF_8);
        String edited = text.replace("\"status\":\"tested\"", "\"status\":\"no_match\"");
        assertThat(edited).isNotEqualTo(text);
        Files.writeString(events, edited, StandardCharsets.UTF_8);
        Files.deleteIfExists(forged.resolve("run.json")); // the file alone; no manifest to disagree with
        Files.deleteIfExists(forged.resolve("children").resolve(WriterTestSupport.SPAWN).resolve("run.json"));
        OracleReport report = verify(forged, nativeDir(work.resolve("n2"), CHILD, "stream.jsonl", -1));
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(report.check("source.compatibility", "main/stream.jsonl").detail()).contains("were normalized");
    }

    /** A version known at launch and tested on its route: tested, the entry named, the reader silent, the oracle VERIFIED. */
    @Test
    void aTestedVersionOnItsRouteIsSelectedAndVerified(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        RunDescriptor spec = run(CHILD);
        assertThat(spec.cli().version()).isEqualTo("2.1.299");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, spec, SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(WriterTestSupport.claudeStream()), nativeOf(CHILD, "stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).member("status").text()).isEqualTo("tested");
        assertThat(history.get(0).member("entry_id").text()).isEqualTo("claude-code/stream-json/2.1.299-sdk");
        assertThat(history.get(0).member("route").text()).isEqualTo("sdk");
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).isEmpty();
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_IN_RANGE")).isEmpty();
        assertThat(p.diagnosticsWithCode("CONTRACT_APPLICABILITY")).isEmpty();
        OracleReport report = verify(core, nativeDir(work, CHILD, "stream.jsonl", -1));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("source.compatibility", "child:" + WriterTestSupport.SPAWN + "/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    /** A declared version at launch with a stated discovery basis (the Codex path): the basis travels into the run and compatibility declarations. */
    @Test
    void aDiscoveredVersionCarriesItsBasis(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        RunDescriptor spec = new RunDescriptor("discovered-basis", 0, null, Instant.parse("2026-10-21T12:00:00Z"),
                new RunDescriptor.Cli("Codex CLI", "0.160.1", "OpenAI", "discovered once per run by `codex --version` (documented adapter path; the exec --json stream reports no version)"),
                null, new Jv.Obj().put("label", "LABELLED SYNTHETIC: version basis test"));
        try (OcsfRunWriter w = OcsfRunWriter.open(core, spec, SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(WriterTestSupport.codexStream()), nativeOf(WriterTestSupport.CODEX_STREAM, "stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        assertThat(p.manifest.text("run", "cli", "version_basis")).contains("codex --version");
        assertThat(p.declarations.compatibility("stream.jsonl").get(0).member("version_basis").text()).contains("codex --version");
        assertThat(p.declarations.compatibility("stream.jsonl").get(0).member("status").text()).isEqualTo("tested");
    }
}
