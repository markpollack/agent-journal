package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.AnalysisViews;
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
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.claudeStreamStdout;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.lines;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeDir;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.open;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility selection before normalization through the writer (RULE-9, RULE-10, RULE-33; UC2-AC3, UC2-AC5,
 * UC2-AC7, UC1-AC5). A record is normalized only under a selected entry. The version bootstrap reads the init record's
 * version and its location, the selection is declared, and then that same record is normalized under it; a record
 * that arrives before any selection is retained whole and unnormalized and never credited. A version that matches no
 * entry, or is never learned, leaves every record retained and flagged; the nearest entry's mapper is not consulted,
 * the native session file is not selected, and the reader warns. An identical repeated assertion changes nothing;
 * contradictory assertions (two records, or the launch declaration and a record) keep every assertion with its
 * origin, stop normalization from that record on and keep what was written before. The independent oracle judges each
 * record by the selection declared in force when it arrived. The labelled synthetic fixture
 * {@code syn-claude-untested-version} is the committed no-match form.
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
        assertThat(p.declarations.cliVersion().member("pointer").text()).as("the bootstrap names where it read the version").isEqualTo("/claude_code_version");
        assertThat(p.declarations.cliVersion().member("record_index").scalarText()).isEqualTo("1");

        // the selection history: version_unknown with the source, then no_match selected from record 1 before record 1 was interpreted
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).member("status").text()).isEqualTo("version_unknown");
        assertThat(history.get(0).member("records_before_selection").scalarText()).isEqualTo("0");
        Json inForce = history.get(1);
        assertThat(inForce.member("status").text()).isEqualTo("no_match");
        assertThat(inForce.member("entry_id")).as("no entry is named: the nearest one is not selected").isNull();
        assertThat(inForce.member("records_before_selection").scalarText()).as("the init record that reported the version is governed by the selection").isEqualTo("0");
        assertThat(inForce.member("normalizes").text()).isEqualTo("no");
        assertThat(inForce.member("version_assertions").elements()).extracting(a -> a.member("version").text() + "@" + a.member("record_index").scalarText())
                .containsExactly("2.1.999@1");
        assertThat(inForce.member("detail").text()).contains("2.1.999").contains("claude-code/stream-json/2.1.304-stdout [2.1.304, 2.1.304]").contains("nearest entry is not selected").contains("RULE-33");
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).isEqualTo("no_match");

        // every record, the init record included, is a whole-record source_record under the untested-compatibility contract
        int retained = 0;
        for (Event e : p.events) {
            if (e.recordIndex() != null) {
                assertThat(e.className).as("record " + e.recordIndex()).isEqualTo("source_record");
                assertThat(e.contractUid()).isEqualTo("agent-journal/untested-compatibility/0.2");
                assertThat(e.text("journal", "rule_uid")).isEqualTo("compat.no_match->source_record");
                assertThat(e.text("basis")).contains("retained whole");
                assertThat(e.get("data")).as("the whole record is kept").isNotNull();
                retained++;
            }
        }
        assertThat(retained).as("every record of the stream: it has 27").isEqualTo(27);
        assertThat(p.children).as("no child core: owner routing is normalization, and nothing was normalized").isEmpty();
        assertThat(p.manifest.sources()).as("the stream remains the only declared source; no native session file was selected").hasSize(1);
        assertThat(p.manifest.source("stream.jsonl").member("profile").text()).isEqualTo("claude_stream");

        // the reader warns; it does not claim support, and it does not raise the single-version contract warning on top
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).hasSize(1);
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH").get(0).detail()).contains("2.1.999").contains("retained");

        // the family inventory states the disposition of every retained family, the init record's included
        Json families = p.manifest.source("stream.jsonl").member("family_inventory");
        for (var f : ((Json.JObject) families).members().entrySet()) {
            assertThat(f.getValue().member("disposition").text()).as(f.getKey()).contains("untested compatibility").contains("not normalized");
            assertThat(f.getValue().member("unselected_records").scalarText()).as(f.getKey()).isEqualTo(f.getValue().member("records").scalarText());
        }

        // the independent oracle against the frozen bytes: compatibility UNSUPPORTED (honest), retention VERIFIED, no defect; the
        // normalized populations are not claimed
        OracleReport report = verify(core, nativeDir(work, UNTESTED, "stream.jsonl", -1));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.check("source.compatibility", "main/stream.jsonl").detail()).contains("retained and flagged untested");
        assertThat(report.outcome("context.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Check.Outcome.VERIFIED);
    }

    /** Negative control: the same stream normalized under the nearest entry's mapper is a defect the oracle sees, not a pass. */
    @Test
    void normalizingAfterANoMatchSelectionIsADefect(@TempDir Path work) throws Exception {
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

    /**
     * A version known at launch and tested on its route: tested, the entry named, the reader silent, the oracle VERIFIED.
     * The init record asserts the same version again, which is idempotent: one selection, resting on the launch declaration.
     */
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
        assertThat(history).as("the init record's equal assertion adds no selection").hasSize(1);
        assertThat(history.get(0).member("status").text()).isEqualTo("tested");
        assertThat(history.get(0).member("entry_id").text()).isEqualTo("claude-code/stream-json/2.1.299-sdk");
        assertThat(history.get(0).member("route").text()).isEqualTo("sdk");
        assertThat(history.get(0).member("version_assertions").elements()).extracting(a -> a.member("origin").text()).containsExactly("launch");
        assertThat(p.declarations.cliVersion().member("version").text()).as("the stream's own report is still declared").isEqualTo("2.1.299");
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).isEmpty();
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_IN_RANGE")).isEmpty();
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_CONFLICT")).isEmpty();
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
        assertThat(p.declarations.compatibility("stream.jsonl").get(0).member("version_assertions").elements().get(0).member("basis").text()).contains("codex --version");
    }

    /**
     * Supported discovery in the init record (option B): with no version at launch the selection is declared
     * version_unknown before the first record; the bootstrap reads record 1's version, the tested selection is declared
     * governing record 1 itself, and record 1 is then normalized under it: its settings are kept.
     */
    @Test
    void aSupportedInitRecordSelectsFirstAndIsThenNormalizedUnderTheSelection(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, pending(WriterTestSupport.LIVE_FEATURES), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nativeOf(WriterTestSupport.LIVE_FEATURES, "stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).member("status").text()).isEqualTo("version_unknown");
        assertThat(history.get(0).member("records_before_selection").scalarText()).isEqualTo("0");
        assertThat(history.get(0).member("normalizes").text()).startsWith("no: every record is retained whole and unnormalized").doesNotContain("provisionally");
        assertThat(history.get(1).member("status").text()).isEqualTo("tested");
        assertThat(history.get(1).member("entry_id").text()).isEqualTo("claude-code/stream-json/2.1.304-stdout");
        assertThat(history.get(1).member("records_before_selection").scalarText()).as("no record preceded the init record that reported the version").isEqualTo("0");
        assertThat(history.get(1).member("version_assertions").elements().get(0).member("pointer").text()).isEqualTo("/claude_code_version");
        Event init = p.events.stream().filter(e -> Integer.valueOf(1).equals(e.recordIndex())).findFirst().orElseThrow();
        assertThat(init.className).as("the init record is normalized after the selection, under the selected contract").isEqualTo("session_context");
        assertThat(init.contractUid()).isEqualTo(p.manifest.source("stream.jsonl").member("contract_uid").text());
        assertThat(init.text("settings", "version")).isEqualTo("2.1.304");
        List<String> written = lines(core.resolve("events.jsonl"));
        assertThat(indexOf(written, "\"uid\":\"compatibility:stream.jsonl#2\"")).as("the selection is declared before the event of the record that carried the version")
                .isPositive().isLessThan(indexOf(written, "\"context_kind\":\"system/init\""));
        OracleReport report = verify(core, nativeDir(work, WriterTestSupport.LIVE_FEATURES, "stream.jsonl", -1));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("context.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    /**
     * A record that genuinely precedes the selection (four records moved before the init record: three vendor estimates
     * and an assistant record carrying thinking and usage): retained whole and unnormalized under the pending rule, no
     * fact of theirs written or credited, no backfill; the init record selects and is normalized; every later record is
     * normalized. The oracle owes no fact of the pending records and reports them as not credited.
     */
    @Test
    void aRecordBeforeTheInitRecordStaysRetainedOnlyAndIsNotCredited(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(nativeOf(WriterTestSupport.LIVE_FEATURES, "stream.jsonl"), StandardCharsets.UTF_8);
        List<String> moved = new java.util.ArrayList<>(lines.subList(1, 5));
        moved.add(lines.get(0));
        moved.addAll(lines.subList(5, lines.size()));
        Path nat = write(work, moved);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, pending(WriterTestSupport.LIVE_FEATURES), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nat.resolve("stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history.get(1).member("status").text()).isEqualTo("tested");
        assertThat(history.get(1).member("records_before_selection").scalarText()).isEqualTo("4");
        for (int i = 1; i <= 4; i++) {
            int idx = i;
            Event e = p.events.stream().filter(x -> Integer.valueOf(idx).equals(x.recordIndex())).findFirst().orElseThrow();
            assertThat(e.className).as("record " + i).isEqualTo("source_record");
            assertThat(e.text("journal", "rule_uid")).isEqualTo("compat.version_pending->source_record");
            assertThat(e.contractUid()).isEqualTo("agent-journal/untested-compatibility/0.2");
            assertThat(e.text("basis")).contains("before a compatibility entry was selected").contains("not interpreted provisionally");
        }
        assertThat(p.events).filteredOn(e -> Integer.valueOf(4).equals(e.recordIndex())).as("the pending assistant record has no response or usage event").hasSize(1);
        assertThat(p.events).filteredOn(e -> Integer.valueOf(5).equals(e.recordIndex())).extracting(e -> e.className).containsExactly("session_context");
        Json assistant = p.manifest.source("stream.jsonl").path("family_inventory", "assistant");
        assertThat(assistant.member("unselected_records").scalarText()).isEqualTo("1");
        assertThat(assistant.member("disposition").text()).startsWith("normalized");
        Json view = AnalysisViews.compatibility(p).member("rows").elements().get(0);
        assertThat(view.member("records_retained_unselected").scalarText()).isEqualTo("4");
        assertThat(view.member("normalization_credited")).isEqualTo(new Json.JBool(true));
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.check("source.compatibility", "main/stream.jsonl").detail()).contains("4 record(s) that arrived before the selection retained whole and unnormalized, not credited");
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("response.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);

        // negative controls on the saved file alone: a selection claimed in force before the pending records arrived (so their
        // retention would hide owed facts), and one claimed after the init record that was in fact normalized under it
        for (String[] forge : new String[][] {{"0", "has a normalization rule but is retained only"}, {"5", "arrived before the selection"}}) {
            Path forged = work.resolve("forged-" + forge[0]);
            java.nio.file.Files.createDirectories(forged);
            String text = Files.readString(core.resolve("events.jsonl"), StandardCharsets.UTF_8);
            String edited = text.replaceFirst("(\"uid\":\"compatibility:stream.jsonl#2\".*?\"records_before_selection\":)4", "$1" + forge[0]);
            assertThat(edited).isNotEqualTo(text);
            Files.writeString(forged.resolve("events.jsonl"), edited, StandardCharsets.UTF_8);
            OracleReport f = verify(forged, nat);
            assertThat(f.defects()).as(forge[0]).extracting(Check::detail).anyMatch(d -> d.contains(forge[1]));
        }
    }

    /**
     * A record before the init record, then a version that matches no entry: the pending record and every later one are
     * retained whole, nothing is normalized or credited, the reader warns.
     */
    @Test
    void aRecordBeforeTheVersionIsRetainedUnnormalizedThenNoMatchCreditsNothing(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(nativeOf(UNTESTED, "stream.jsonl"), StandardCharsets.UTF_8);
        String assistant = lines.stream().filter(l -> l.startsWith("{\"type\":\"assistant\"")).findFirst().orElseThrow();
        List<String> reordered = new java.util.ArrayList<>(lines);
        reordered.add(0, assistant);
        Path nat = write(work, reordered);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(UNTESTED), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nat.resolve("stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history.get(0).member("status").text()).isEqualTo("version_unknown");
        assertThat(history.get(1).member("status").text()).isEqualTo("no_match");
        assertThat(history.get(1).member("records_before_selection").scalarText()).as("the early assistant record").isEqualTo("1");
        assertThat(p.events).filteredOn(e -> Integer.valueOf(1).equals(e.recordIndex())).extracting(e -> e.text("journal", "rule_uid"))
                .containsExactly("compat.version_pending->source_record");
        assertThat(p.events).filteredOn(e -> e.recordIndex() != null).allMatch(e -> "source_record".equals(e.className));
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_NO_MATCH")).hasSize(1);
        OracleReport report = verify(core, nat);
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("response.values", "main/stream.jsonl")).as("nothing is credited").isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.checks()).as("no normalization of this source is verified support").noneMatch(c -> "main/stream.jsonl".equals(c.owner()) && c.outcome() == Check.Outcome.VERIFIED
                && (c.property().startsWith("response.") || c.property().startsWith("request.") || c.property().startsWith("usage.") || c.property().startsWith("context.")));
    }

    /**
     * A version that is never learned (a Codex exec replay without the launch probe's version): the selection stays
     * version_unknown to the end, every record is retained whole and unnormalized, the reader warns, and the independent
     * oracle reports the source's compatibility UNRESOLVED and claims no normalization.
     */
    @Test
    void aVersionNeverLearnedRetainsEveryRecordUnnormalizedAndUnresolved(@TempDir Path work) throws Exception {
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, pending(WriterTestSupport.CODEX_STREAM), SchemaPin.bundled(), options())) {
            Replay.feedRecords(w.source(WriterTestSupport.codexStream()), nativeOf(WriterTestSupport.CODEX_STREAM, "stream.jsonl"), options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).member("status").text()).isEqualTo("version_unknown");
        assertThat(p.events).filteredOn(e -> e.recordIndex() != null).isNotEmpty()
                .allMatch(e -> "source_record".equals(e.className) && "compat.version_pending->source_record".equals(e.text("journal", "rule_uid")));
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_VERSION_UNKNOWN")).hasSize(1);
        OracleReport report = verify(core, nativeDir(work, WriterTestSupport.CODEX_STREAM, "stream.jsonl", -1));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(report.outcome("population.requests", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
    }

    /** The same version asserted twice by the stream (a second init record) is idempotent: no third selection, nothing stops. */
    @Test
    void anIdenticalRepeatedAssertionIsIdempotent(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(nativeOf(WriterTestSupport.LIVE_FEATURES, "stream.jsonl"), StandardCharsets.UTF_8);
        List<String> repeated = new java.util.ArrayList<>(lines);
        repeated.add(10, lines.get(0));
        Path nat = write(work, repeated);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, pending(WriterTestSupport.LIVE_FEATURES), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nat.resolve("stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        assertThat(p.declarations.compatibility("stream.jsonl")).extracting(c -> c.member("status").text()).containsExactly("version_unknown", "tested");
        assertThat(p.events).filteredOn(e -> Integer.valueOf(11).equals(e.recordIndex())).extracting(e -> e.className).containsExactly("session_context");
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_CONFLICT")).isEmpty();
    }

    /**
     * Contradictory stream assertions (a second init record inside the child's activity reporting 2.1.999): both are kept
     * with their origins, the selection becomes conflict for every later record, which is retained whole (child records
     * included: routing is normalization) while the events written before stay; the reader warns, and the oracle reports
     * the source ambiguous (UNRESOLVED), credits no normalization and finds no defect.
     */
    @Test
    void contradictoryStreamAssertionsStopNormalizationAndKeepThePrefix(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(nativeOf(WriterTestSupport.LIVE_FEATURES, "stream.jsonl"), StandardCharsets.UTF_8);
        List<String> contradicted = new java.util.ArrayList<>(lines);
        contradicted.add(26, lines.get(0).replace("\"claude_code_version\":\"2.1.304\"", "\"claude_code_version\":\"2.1.999\""));
        assertThat(contradicted.get(26)).contains("2.1.999");
        Path nat = write(work, contradicted);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, pending(WriterTestSupport.LIVE_FEATURES), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(claudeStreamStdout()), nat.resolve("stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).extracting(c -> c.member("status").text()).containsExactly("version_unknown", "tested", "conflict");
        Json conflict = history.get(2);
        assertThat(conflict.member("records_before_selection").scalarText()).isEqualTo("26");
        assertThat(conflict.member("version").isNull()).isTrue();
        assertThat(conflict.member("version_assertions").elements()).extracting(a -> a.member("version").text() + "@" + a.member("record_index").scalarText())
                .containsExactly("2.1.304@1", "2.1.999@27");
        assertThat(p.events).filteredOn(e -> e.recordIndex() != null && e.recordIndex() >= 27)
                .allMatch(e -> "source_record".equals(e.className) && "compat.conflict->source_record".equals(e.text("journal", "rule_uid")));
        assertThat(p.events).as("the prefix stays as written").anyMatch(e -> Integer.valueOf(22).equals(e.recordIndex()) && "tool_call".equals(e.className));
        assertThat(p.children).as("the child core created before the conflict keeps its events").hasSize(1);
        assertThat(p.children.get(0).declarations.compatibility("stream.jsonl")).extracting(c -> c.member("status").text()).endsWith("conflict");
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_CONFLICT")).hasSize(1);
        assertThat(p.manifest.text("run", "cli", "version_basis")).contains("compatibility status conflict");
        assertThat(AnalysisViews.compatibility(p).member("rows").elements().get(0).member("normalization_credited")).isEqualTo(new Json.JBool(false));
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(report.check("source.compatibility", "main/stream.jsonl").detail()).contains("[2.1.304, 2.1.999]").contains("not credited as supported");
        assertThat(report.outcome("response.values", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNSUPPORTED);
        assertThat(report.outcome("whole_record.coverage", "main/stream.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    /** The launch declaration contradicted by the stream's init record: a conflict from record 1, nothing normalized, the first assertion not preferred. */
    @Test
    void aLaunchDeclarationContradictedByTheStreamIsAConflict(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(nativeOf(CHILD, "stream.jsonl"), StandardCharsets.UTF_8);
        List<String> edited = new java.util.ArrayList<>(lines);
        edited.set(0, lines.get(0).replace("\"claude_code_version\":\"2.1.299\"", "\"claude_code_version\":\"2.1.303\""));
        assertThat(edited.get(0)).contains("2.1.303");
        Path nat = write(work, edited);
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CHILD), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(WriterTestSupport.claudeStream()), nat.resolve("stream.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        List<Json> history = p.declarations.compatibility("stream.jsonl");
        assertThat(history).extracting(c -> c.member("status").text()).containsExactly("tested", "conflict");
        assertThat(history.get(1).member("records_before_selection").scalarText()).isEqualTo("0");
        assertThat(history.get(1).member("version_assertions").elements()).extracting(a -> a.member("version").text() + "/" + a.member("origin").text())
                .containsExactly("2.1.299/launch", "2.1.303/source");
        assertThat(p.events).filteredOn(e -> e.recordIndex() != null).allMatch(e -> "source_record".equals(e.className));
        assertThat(p.children).isEmpty();
        OracleReport report = verify(core, nat);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("source.compatibility", "main/stream.jsonl")).isEqualTo(Check.Outcome.UNRESOLVED);
    }

    /** The Codex launch probe's version contradicted by the rollout's session_meta (the one route that reports both): a conflict, nothing normalized. */
    @Test
    void aLaunchProbeContradictedByARolloutRecordIsAConflict(@TempDir Path work) throws Exception {
        List<String> lines = Files.readAllLines(nativeOf(CODEX, "rollout.jsonl"), StandardCharsets.UTF_8);
        List<String> edited = new java.util.ArrayList<>(lines);
        edited.set(0, lines.get(0).replace("\"cli_version\":\"0.160.1\"", "\"cli_version\":\"0.161.0\""));
        assertThat(edited.get(0)).contains("0.161.0");
        Path nat = work.resolve("native");
        Files.createDirectories(nat);
        Files.write(nat.resolve("rollout.jsonl"), (String.join("\n", edited) + "\n").getBytes(StandardCharsets.UTF_8));
        Path core = work.resolve("core");
        try (OcsfRunWriter w = OcsfRunWriter.open(core, run(CODEX), SchemaPin.bundled(), options())) {
            Replay.feedBytes(w.source(WriterTestSupport.codexRollout()), nat.resolve("rollout.jsonl"), 1024, options().clock());
            w.complete();
        }
        OcsfPackage p = open(core);
        assertThat(p.declarations.compatibility("rollout.jsonl")).extracting(c -> c.member("status").text()).containsExactly("tested", "conflict");
        assertThat(p.events).filteredOn(e -> e.recordIndex() != null).allMatch(e -> "source_record".equals(e.className));
        assertThat(p.diagnosticsWithCode("COMPATIBILITY_CONFLICT")).hasSize(1);
    }

    private static RunDescriptor pending(String fixture) {
        RunDescriptor d = run(fixture);
        return new RunDescriptor(d.runUid(), d.invocationIndex(), d.sessionUid(), d.captureStart(),
                new RunDescriptor.Cli(d.cli().name(), null, d.cli().vendorName()), d.launchSettings(), d.synthetic());
    }

    private static Path write(Path work, List<String> lines) throws java.io.IOException {
        Path nat = work.resolve("native");
        Files.createDirectories(nat);
        Files.write(nat.resolve("stream.jsonl"), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        return nat;
    }

    private static int indexOf(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }
}
