package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.verify.Check;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Claude Code SDK family on real recordings (Owner, 2026-11-08): the CLI version is recorded, not gated. The retained
 * TX Bench capture of Claude Code 2.1.319 (`txbench-2.1.319`, a scrubbed derivative of the private corpus run) is accepted
 * under the version-neutral terminal profile with its own version in every record; the route, format, identity,
 * completeness and integrity checks still refuse; and the Claude-only checks (sub-agent session, child core, native
 * reading) still run at another version. Edited controls start from the real bytes and restate only what they name.
 */
class ClaudeSdkVersionTest {
    private static final String TXBENCH = "txbench-2.1.319";
    private static final String SPAWN = "toolu_01KQMhaSZMrxp98fqQBkmHCC";
    @TempDir Path temp;

    /** The real run {@code name} copied into {@code into} and edited by {@code edit}; with {@code restate} the manifest's extents are restated. */
    private Path edited(String name, String into, boolean restate, BiConsumer<Path, Jv.Obj> edit) throws IOException {
        return edited(RealRecording.PUBLIC.resolve(name), into, restate, edit);
    }

    /** The real run {@code name} with every statement of CLI version {@code from} in its native files and sealed manifest replaced by {@code to}. */
    private Path relabelled(String name, String from, String to) throws IOException {
        Path run = temp.resolve(name + "-as-" + to);
        RealRecordingTest.copyTree(RealRecording.PUBLIC.resolve(name), run);
        try (Stream<Path> files = Files.walk(RealRecording.nativeDir(run))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.writeString(f, Files.readString(f, StandardCharsets.UTF_8).replace(from, to), StandardCharsets.UTF_8);
            }
        }
        return edited(run, run.getFileName() + "-restated", true, (dir, m) -> { });
    }

    /** The run at {@code source} copied into {@code into}, edited by {@code edit} (its native directory and sealed manifest), and with {@code restate} its extents restated. */
    private Path edited(Path source, String into, boolean restate, BiConsumer<Path, Jv.Obj> edit) throws IOException {
        Path run = temp.resolve(into);
        RealRecordingTest.copyTree(source, run);
        Path dir = RealRecording.nativeDir(run);
        Jv.Obj manifest = (Jv.Obj) Jv.parse(Files.readString(dir.resolve("client-acquisition.json")));
        edit.accept(dir, manifest);
        if (restate) {
            for (Jv src : manifest.member("sources").elements()) {
                Path file = dir.resolve(src.member("path").text());
                if (!"acquired".equals(src.member("state").text()) || !Files.exists(file)) { continue; }
                byte[] bytes = Files.readAllBytes(file);
                ((Jv.Obj) src).put("bytes", bytes.length).put("sha256", SchemaPin.sha256(bytes));
                Jv.Obj end = (Jv.Obj) manifest.member("turns").elements().get(0).member("end_offsets");
                if ("stream".equals(src.member("role").text())) { end.put("STDOUT", bytes.length); }
                if ("stdin".equals(src.member("role").text())) { end.put("STDIN", bytes.length); }
            }
        }
        Files.writeString(dir.resolve("client-acquisition.json"), Jv.write(manifest));
        return run;
    }

    private static List<Jv> rows(Path events) throws IOException {
        return RealRecording.lines(events);
    }

    @Test void theRetained2_1_319CaptureIsAcceptedUnderTheNeutralProfileWithItsOwnVersionRecorded() throws IOException {
        RealRecording.Sealed s = RealRecording.seal(RealRecording.PUBLIC.resolve(TXBENCH), temp.resolve("txbench"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        io.github.markpollack.journal.tck.ocsf.CanonicalExport.write(io.github.markpollack.journal.tck.ocsf.PackageReader.open(s.events()), new java.io.StringWriter());
        Jv qualified = Jv.parse(Files.readString(s.qualified().resolve("acquisition.json")));
        assertThat(qualified.member("profile").text()).isEqualTo("claude-code-2.1.319-sdk-stdio-session-file");
        assertThat(qualified.path("run", "cli", "version").text()).isEqualTo("2.1.319");
        List<Jv> rows = rows(s.events());
        assertThat(Files.readString(s.events())).contains("\\\"profile\\\":\\\"client-terminal-claude-sdk/1\\\"");
        Jv run = rows.stream().filter(r -> r.member("run") != null && r.member("declaration") != null).findFirst().orElseThrow().member("run");
        assertThat(run.path("cli", "version").text()).isEqualTo("2.1.319");
        List<Jv> events = rows.stream().filter(r -> r.member("class_uid") != null).toList();
        assertThat(events).isNotEmpty().allSatisfy(e -> assertThat(e.path("metadata", "product", "version").text()).isEqualTo("2.1.319"));
        // the run's prompt, its one Read and the answer, as the canonical readback shows them
        assertThat(events.stream().map(e -> e.member("class_name").text() + "/" + e.member("activity_name").text()))
                .contains("Prompt Turn/Prompt submitted", "Tool Call/Requested", "Tool Call/Result observed", "Model Response/Completed", "Prompt Turn/Turn completed");
        assertThat(events).filteredOn(e -> "Tool Call".equals(e.member("class_name").text()) && e.member("tool_name") != null)
                .extracting(e -> e.member("tool_name").text()).containsOnly("Read");
        assertThat(s.report().outcome("source.compatibility", "main/stdout.jsonl")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test void anUnlistedVersionIsAcceptedAndRecorded() throws IOException {
        RealRecording.Sealed s = RealRecording.seal(relabelled(TXBENCH, "2.1.319", "2.1.317"), temp.resolve("as-2.1.317"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        assertThat(rows(s.events()).stream().filter(r -> r.member("class_uid") != null))
                .allSatisfy(e -> assertThat(e.path("metadata", "product", "version").text()).isEqualTo("2.1.317"));
        assertThat(s.report().check("source.compatibility", "main/stdout.jsonl").detail()).contains("2.1.317");
    }

    @Test void aTamperedByteIsRefused() throws IOException {
        Path run = edited(TXBENCH, "tampered", false, (dir, m) -> {
            try {
                byte[] bytes = Files.readAllBytes(dir.resolve("stdout.bin"));
                bytes[bytes.length / 2] ^= 1;
                Files.write(dir.resolve("stdout.bin"), bytes);
            }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        assertThatThrownBy(() -> RealRecording.seal(run, temp.resolve("tampered-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("source hash mismatch");
    }

    @Test void aMissingTranscriptIsRefused() throws IOException {
        Path run = edited(TXBENCH, "missing", false, (dir, m) -> {
            try { Files.delete(dir.resolve("native/transcript.jsonl")); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        assertThatThrownBy(() -> RealRecording.seal(run, temp.resolve("missing-sealed"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("declared source absent from the sealed acquisition: native/transcript.jsonl");
    }

    @Test void aWrongRouteIsRefused() throws IOException {
        Path launch = edited(TXBENCH, "route", true, (dir, m) -> ((Jv.Obj) m.path("run", "launch_settings")).put("route", "stdout"));
        assertThatThrownBy(() -> RealRecording.seal(launch, temp.resolve("route-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("unqualified launch route");
        Path stream = edited(TXBENCH, "stream-route", true, (dir, m) -> m.member("sources").elements().stream()
                .filter(src -> "stream".equals(src.member("role").text())).forEach(src -> ((Jv.Obj) src).put("route", "stdout")));
        assertThatThrownBy(() -> RealRecording.seal(stream, temp.resolve("stream-route-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("wrong source route");
    }

    @Test void aWrongIdentityIsRefused() throws IOException {
        Path session = edited(TXBENCH, "identity", true, (dir, m) -> {
            ((Jv.Obj) m.member("run")).put("session_uid", "00000000-0000-0000-0000-000000000000");
            m.member("sources").elements().stream().filter(src -> src.member("owner_session_uid") != null)
                    .forEach(src -> ((Jv.Obj) src).put("owner_session_uid", "00000000-0000-0000-0000-000000000000"));
        });
        assertThatThrownBy(() -> RealRecording.seal(session, temp.resolve("identity-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("native session mismatch");
        Path version = edited(TXBENCH, "version-identity", true, (dir, m) -> ((Jv.Obj) m.path("run", "cli")).put("version", "2.1.317"));
        assertThatThrownBy(() -> RealRecording.seal(version, temp.resolve("version-identity-sealed"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unqualified profile");
    }

    /** A versioned 2.1.316 terminal profile stays readable for 2.1.316, and admits no other version. */
    @Test void theLegacyTerminalProfileStillReadsAt2_1_316Only() throws IOException {
        RealRecording.Sealed legacy = RealRecording.seal(RealRecording.PUBLIC.resolve("heartbeat"), temp.resolve("legacy"), TerminalReceiver.LEGACY_CLAUDE_PROFILE);
        assertThat(legacy.acceptance().verdict()).as(legacy.acceptance().toString()).isEqualTo("ACCEPTED");
        assertThatThrownBy(() -> RealRecording.seal(RealRecording.PUBLIC.resolve(TXBENCH), temp.resolve("legacy-319"), TerminalReceiver.LEGACY_CLAUDE_PROFILE))
                .hasMessageContaining("2.1.319 is not admitted under terminal profile client-terminal-claude-2.1.316/1");
    }

    /**
     * The Claude-only checks key on the family, not on one version: the real sub-agent run relabelled 2.1.319 binds its
     * sub-agent's session file to the child core (acquisition.child_session VERIFIED), and without that file it is INCOMPLETE.
     */
    @Test void theSubAgentChildAndNativeReadingChecksRunAtAnotherVersion() throws IOException {
        Path run = relabelled("sub-agent", "2.1.316", "2.1.319");
        RealRecording.Sealed s = RealRecording.seal(run, temp.resolve("sub-agent-319"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        assertThat(s.report().outcome("acquisition.child_session", "child:" + SPAWN)).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(s.report().outcome("acquisition.child_spawn", "child:" + SPAWN)).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(s.child(SPAWN)).isRegularFile();
        Path without = edited(run, "sub-agent-319-without", true, (dir, m) -> {
            Jv.Arr kept = new Jv.Arr();
            for (Jv src : m.member("sources").elements()) {
                if (src.member("path").text().contains("/subagents/")) {
                    try { Files.delete(dir.resolve(src.member("path").text())); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                }
                else { kept.add(src); }
            }
            m.put("sources", kept);
        });
        RealRecording.Sealed incomplete = RealRecording.seal(without, temp.resolve("sub-agent-319-without-sealed"));
        assertThat(incomplete.acceptance().verdict()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.acceptance().reasons()).anyMatch(r -> r.contains("acquisition.child_session"));
    }
}
