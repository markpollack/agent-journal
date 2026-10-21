package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.lines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.writeLines;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 0.2.0 layout's recording states as the reader and oracle judge them, the settings/context and turn-start
 * properties, the open-source inventory and the split-core scope: each mutation is detected on the owning property,
 * each honest state is reported without being folded into a pass.
 */
class OcsfOracleStateAndContextTest {

    static final String PAIR = OracleFullSourceTest.PAIR;
    static final String STREAM = "main/stream.jsonl";
    static final String TRANSCRIPT = "main/transcript.jsonl";
    static final String ROLLOUT = "main/rollout.jsonl";

    @Test
    void turnStartIdentityAndPopulationAreJudged(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        editEvents(pkg(fixture), events -> obj(at(events, "prompt_turn", 3, 2)).members().put("turn_uid", str("turn-wrong")));
        OracleReport report = verify(fixture);
        assertThat(report.check("turn_started.values", ROLLOUT).detail()).contains("record 2 turn_uid turn-wrong != ");
        assertThat(report.outcome("population.turn_started", ROLLOUT)).isEqualTo(Outcome.VERIFIED);
        Path dropped = copy(CODEX, work.resolve("dropped"));
        editEvents(pkg(dropped), events -> events.remove(at(events, "prompt_turn", 3, 2)));
        OracleReport r2 = verify(dropped);
        assertThat(r2.check("population.turn_started", ROLLOUT).detail()).contains("missing [2]");
        assertThat(r2.outcome("whole_record.coverage", ROLLOUT)).isEqualTo(Outcome.DEFECTIVE);
    }

    @Test
    void contextTurnRoleBlocksSettingsAndDataAreEachJudged(@TempDir Path work) {
        Path codex = copy(CODEX, work.resolve("codex"));
        editEvents(pkg(codex), events -> obj(at(events, "session_context", 1, 4)).members().put("turn_uid", str("other-turn")));
        assertThat(verify(codex).check("context.values", ROLLOUT).detail()).contains("4:turn_context turn_uid other-turn != ");

        Path pair = copy(PAIR, work.resolve("pair"));
        editEvents(pkg(pair), events -> {
            Json env = at(events, "transcript.jsonl", "session_context", 2, 4);
            obj(env).members().put("rendered_role", str("user"));
            obj(env).members().remove("content_blocks");
            Json credential = at(events, "transcript.jsonl", "session_context", 2, 6);
            obj(credential).members().put("content_blocks", new Json.JArray(List.of()));
            Json init = at(events, "stream.jsonl", "session_context", 1, 1);
            obj(init).members().remove("settings");
            obj(init).members().remove("data");
        });
        OracleReport report = verify(pair);
        String transcript = report.check("context.values", TRANSCRIPT).detail();
        assertThat(transcript).contains("4:attachment/environment rendered_role user != system")
                .contains("4:attachment/environment content_blocks absent != 1")
                .contains("6:attachment/credential_org content_blocks present but the source renders nothing");
        String stream = report.check("context.values", STREAM).detail();
        assertThat(stream).contains("1:system/init settings absent; the record reports [working_directory, model, permission_mode, version, tool_names]")
                .contains("1:system/init whole record (data) not retained");
    }

    @Test
    void recordingFailedAndInterruptedStatesAreReportedHonestly(@TempDir Path work) {
        Path failed = copy(PAIR, work.resolve("failed"));
        editManifest(pkg(failed), run -> {
            obj(run.member("package")).members().put("state", str("FAILED"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            obj(run.member("recording")).members().put("failure", new Json.JObject(java.util.Map.of("operation", str("append"), "detail", str("disk full"))));
        });
        OcsfPackage p = open(failed);
        assertThat(p.diagnosticsWithCode("PACKAGE_FAILED")).hasSize(1);
        assertThat(p.diagnosticsWithCode("EVENTS_HASH_ABSENT")).isEmpty();
        OracleReport report = verify(failed);
        assertThat(report.outcome("package.state", "main")).isEqualTo(Outcome.UNRESOLVED);
        assertThat(report.check("package.state", "main").detail()).contains("the writer failed").contains("disk full");
        assertThat(OcsfReaderTool.acceptance(report).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);

        Path interrupted = copy(PAIR, work.resolve("interrupted"));
        editManifest(pkg(interrupted), run -> obj(run.member("package")).members().put("state", str("INTERRUPTED")));
        OcsfPackage i = open(interrupted);
        assertThat(i.diagnosticsWithCode("PACKAGE_INTERRUPTED")).hasSize(1);
        OracleReport ir = verify(interrupted);
        assertThat(ir.outcome("package.state", "main")).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(ir.check("package.state", "main").detail()).contains("INTERRUPTED: the recording did not complete");
        assertThat(OcsfReaderTool.acceptance(ir).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);

        Path recording = copy(PAIR, work.resolve("recording"));
        editManifest(pkg(recording), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
        });
        OcsfPackage r = open(recording);
        assertThat(r.diagnosticsWithCode("PACKAGE_OPEN")).hasSize(1);
        assertThat(r.diagnosticsWithCode("EVENTS_HASH_ABSENT")).isEmpty();
        assertThat(verify(recording).check("package.state", "main").detail()).contains("open package");

        Path noHash = copy(PAIR, work.resolve("nohash"));
        editManifest(pkg(noHash), run -> obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE));
        assertThat(open(noHash).diagnosticsWithCode("EVENTS_HASH_ABSENT")).hasSize(1);
    }

    @Test
    void declaredEventCountsAreCheckedAgainstTheFileInEveryState(@TempDir Path work) {
        Path complete = copy(PAIR, work.resolve("complete"));
        editManifest(pkg(complete), run -> obj(run.member("package")).members().put("events_count", num("3")));
        assertThat(open(complete).diagnosticsWithCode("EVENTS_COUNT_MISMATCH").get(0).detail()).contains("declares 3 event(s) but events.jsonl holds 44");

        Path recording = copy(PAIR, work.resolve("recording"));
        editManifest(pkg(recording), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            obj(run.member("package")).members().put("events_count", num("50"));
        });
        assertThat(open(recording).diagnosticsWithCode("EVENTS_COUNT_MISMATCH").get(0).detail()).contains("declares 50 persisted event(s) but events.jsonl holds only 44");

        Path recordingFewer = copy(PAIR, work.resolve("recording-fewer"));
        editManifest(pkg(recordingFewer), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            obj(run.member("package")).members().put("events_count", num("40"));
        });
        assertThat(open(recordingFewer).diagnosticsWithCode("EVENTS_COUNT_MISMATCH")).as("a flush after the manifest may add lines; fewer declared than present is not a mismatch while recording").isEmpty();
    }

    @Test
    void openSourceInventoryIsJudgedWithoutAHashAndFinalInventoryWithOne(@TempDir Path work) throws Exception {
        Path open = copy(PAIR, work.resolve("open"));
        editManifest(pkg(open), run -> {
            Json.JObject source = obj(run.member("sources").elements().get(1));
            source.members().put("final", new Json.JBool(false));
            source.members().put("state", str("RECORDING"));
        });
        OracleReport report = verify(open);
        assertThat(report.check("source.inventory", TRANSCRIPT).detail()).contains("an open source must not carry a sha256");
        editManifest(pkg(open), run -> obj(run.member("sources").elements().get(1)).members().remove("sha256"));
        OracleReport again = verify(open);
        assertThat(again.outcome("source.inventory", TRANSCRIPT)).isEqualTo(Outcome.VERIFIED);
        assertThat(again.check("source.inventory", TRANSCRIPT).detail()).contains("open source (state RECORDING)");

        Path framing = copy(PAIR, work.resolve("framing"));
        editManifest(pkg(framing), run -> {
            Json.JObject source = obj(run.member("sources").elements().get(0));
            source.members().put("trailing_newline", new Json.JBool(false));
            source.members().put("bytes", num("1"));
        });
        String detail = verify(framing).check("source.inventory", STREAM).detail();
        assertThat(detail).contains("trailing_newline false, artifact: true").contains("bytes 1, artifact has");
    }

    @Test
    void aChildCoreCarryingAnotherArtifactsEventIsOutsideItsScope(@TempDir Path work) throws Exception {
        Path fixture = copy(PAIR, work);
        Path child = pkg(fixture).resolve("children").resolve(OcsfFixtures.SPAWN);
        List<Json> parentEvents = lines(pkg(fixture).resolve("events.jsonl"));
        Json stray = OcsfFixtures.copy(at(parentEvents, "transcript.jsonl", "source_record", 1, 11));
        obj(stray.member("journal")).members().put("run_uid", str(open(fixture).children.get(0).manifest.runUid()));
        obj(stray.member("journal")).members().put("sequence", num("99"));
        List<Json> childEvents = new ArrayList<>(lines(child.resolve("events.jsonl")));
        childEvents.add(stray);
        writeLines(child.resolve("events.jsonl"), childEvents);
        OcsfFixtures.repin(child);
        OracleReport report = verify(fixture);
        String owner = "child:" + OcsfFixtures.SPAWN + "/transcript.jsonl";
        assertThat(report.outcome("source.scope", owner)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("source.scope", owner).detail()).contains("outside its owner scope (stream.jsonl)");
        Path control = copy(PAIR, work.resolve("control"));
        assertThat(verify(control).outcome("source.scope", owner)).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(Files.exists(child.resolve("run.json"))).isTrue();
    }
}
