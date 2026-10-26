package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.natives;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.tamperEvents;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI's exit status is the acceptance specification, reproduced from the review findings: a detached events
 * file is refused (exit 2) by every command instead of crashing; verify exits 3 on a defect, 4 when a mandatory
 * property is unresolved/unsupported or the package is not finalized, and 0 only when accepted.
 */
class OcsfReaderToolTest {

    @Test
    void everyCommandReadsAnEventsFileAloneAndOnlyTheOptionalViewsNeedTheAncillaryManifest(@TempDir Path work) throws Exception {
        Path fixture = copy(OcsfFixtures.LIVE_CODEX, work);
        Path alone = work.resolve("alone");
        Files.createDirectories(alone);
        Files.copy(pkg(fixture).resolve("events.jsonl"), alone.resolve("events.jsonl"));
        String file = alone.resolve("events.jsonl").toString();
        Result inspect = run("inspect", file);
        assertThat(inspect.exit()).as(inspect.out()).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(inspect.out()).contains("ANCILLARY_ABSENT").contains("class agentic_cli/tool_call");
        assertThat(run("measure", file).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(run("self-contained", file, work.resolve("sc.jsonl").toString()).exit()).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(Files.exists(work.resolve("sc.jsonl"))).isTrue();
        Result views = run("views", file);
        assertThat(views.exit()).isEqualTo(OcsfReaderTool.EXIT_UNAVAILABLE);
        assertThat(views.err()).contains("no semantic contract");
        Result verify = run("verify", file, natives(fixture).toString());
        assertThat(verify.exit()).as(verify.out()).isEqualTo(OcsfReaderTool.EXIT_OK);
    }

    @Test
    void everyCommandRefusesADetachedHistoricalEventsFileWithExitTwo(@TempDir Path work) throws Exception {
        Path fixture = OcsfFixtures.copyHistorical(OcsfFixtures.HISTORICAL_0_2_0, CODEX, work);
        Path detached = work.resolve("detached");
        Files.createDirectories(detached);
        Files.copy(pkg(fixture).resolve("events.jsonl"), detached.resolve("events.jsonl"));
        String file = detached.resolve("events.jsonl").toString();
        assertThat(run("inspect", file).exit()).isEqualTo(OcsfReaderTool.EXIT_REFUSED);
        assertThat(run("views", file).exit()).isEqualTo(OcsfReaderTool.EXIT_REFUSED);
        assertThat(run("measure", file).exit()).isEqualTo(OcsfReaderTool.EXIT_REFUSED);
        assertThat(run("self-contained", file, work.resolve("sc.jsonl").toString()).exit()).isEqualTo(OcsfReaderTool.EXIT_REFUSED);
        assertThat(Files.exists(work.resolve("sc.jsonl"))).as("nothing is exported from a refused package").isFalse();
        Result verify = run("verify", file, natives(fixture).toString());
        assertThat(verify.exit()).isEqualTo(OcsfReaderTool.EXIT_REFUSED);
        assertThat(verify.err()).contains("NO_MANIFEST");
    }

    @Test
    void verifyExitsZeroOnlyWhenAccepted(@TempDir Path work) throws Exception {
        Path fixture = copy(OcsfFixtures.LIVE_CODEX, work);
        Result ok = run("verify", pkg(fixture).toString(), natives(fixture).toString(), work.resolve("report.json").toString());
        assertThat(ok.exit()).as(ok.out()).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(ok.out()).contains("acceptance: ACCEPTED");
        assertThat(Files.exists(work.resolve("report.json"))).isTrue();

        tamperEvents(pkg(fixture), events -> obj(events.get(0)).members().put("severity_id", num("1")));
        Result tampered = run("verify", pkg(fixture).toString(), natives(fixture).toString());
        assertThat(tampered.exit()).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
        assertThat(tampered.out()).contains("acceptance: DEFECTIVE");
    }

    @Test
    void verifyExitsFourWhenAMandatoryPropertyIsUnresolvedEvenWithoutDefects(@TempDir Path work) throws Exception {
        Path fixture = copy(CODEX, work);
        Files.delete(natives(fixture).resolve("rollout.jsonl"));
        Result missing = run("verify", pkg(fixture).toString(), natives(fixture).toString());
        assertThat(missing.exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(missing.out()).contains("acceptance: INCOMPLETE").contains("source.available");

        Path open = copy(CODEX, work.resolve("open"));
        editManifest(pkg(open), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
        });
        Result recording = run("verify", pkg(open).toString(), natives(open).toString());
        assertThat(recording.exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(recording.out()).contains("package.state");
    }

    @Test
    void badArgumentsExitSixtyFour() throws Exception {
        assertThat(run("inspect").exit()).isEqualTo(OcsfReaderTool.EXIT_USAGE);
        assertThat(run("bogus", ".").exit()).isEqualTo(OcsfReaderTool.EXIT_USAGE);
    }

    record Result(int exit, String out, String err) {
    }

    static Result run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = OcsfReaderTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
