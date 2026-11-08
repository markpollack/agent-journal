package io.github.markpollack.journal.ocsf.writer;

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
 * The Codex exec family on a real recording (Owner, 2026-11-08): the CLI version is recorded, not gated. The real Client
 * Codex 0.166.0 one-read run ({@code codex-one-read}, a scrubbed derivative of the private corpus run recorded by
 * agent-experiment's CodexOneRead on a seat login) is accepted under the version-neutral terminal profile with its own
 * version in every record; the route, format, identity, completeness and integrity checks still refuse. Edited controls
 * start from the real bytes and restate only what they name.
 */
class CodexExecVersionTest {
    private static final String RUN = "codex-one-read";
    @TempDir Path temp;

    /** The run at {@code source} copied into {@code into}, edited by {@code edit}; with {@code restate} the manifest's extents are restated. */
    private Path edited(Path source, String into, boolean restate, BiConsumer<Path, Jv.Obj> edit) throws IOException {
        Path run = temp.resolve(into);
        RealRecordingTest.copyTree(source, run);
        Path dir = RealRecording.nativeDir(run);
        Jv.Obj manifest = (Jv.Obj) Jv.parse(Files.readString(dir.resolve("client-acquisition.json")));
        edit.accept(dir, manifest);
        if (restate) {
            for (Jv src : manifest.member("sources").elements()) {
                Path file = dir.resolve(src.member("path").text());
                if (!Files.exists(file)) { continue; }
                byte[] bytes = Files.readAllBytes(file);
                ((Jv.Obj) src).put("bytes", bytes.length).put("observed", bytes.length).put("sha256", SchemaPin.sha256(bytes));
            }
        }
        Files.writeString(dir.resolve("client-acquisition.json"), Jv.write(manifest));
        return run;
    }

    private Path edited(String into, boolean restate, BiConsumer<Path, Jv.Obj> edit) throws IOException {
        return edited(RealRecording.PUBLIC.resolve(RUN), into, restate, edit);
    }

    /** The real run with every statement of CLI version {@code from} in its native files and sealed manifest replaced by {@code to}. */
    private Path relabelled(String to) throws IOException {
        Path run = temp.resolve("as-" + to);
        RealRecordingTest.copyTree(RealRecording.PUBLIC.resolve(RUN), run);
        try (Stream<Path> files = Files.walk(RealRecording.nativeDir(run))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Files.writeString(f, Files.readString(f, StandardCharsets.UTF_8).replace("0.166.0", to), StandardCharsets.UTF_8);
            }
        }
        return edited(run, "as-" + to + "-restated", true, (dir, m) -> { });
    }

    private static List<Jv> events(Path file) throws IOException {
        return RealRecording.lines(file).stream().filter(r -> r.member("class_uid") != null).toList();
    }

    @Test void theRealCodexRunIsAcceptedUnderTheNeutralProfileWithItsOwnVersionRecorded() throws IOException {
        RealRecording.Sealed s = RealRecording.seal(RealRecording.PUBLIC.resolve(RUN), temp.resolve("sealed"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        io.github.markpollack.journal.tck.ocsf.CanonicalExport.write(io.github.markpollack.journal.tck.ocsf.PackageReader.open(s.events()), new java.io.StringWriter());
        assertThat(Files.readString(s.events())).contains("\\\"profile\\\":\\\"client-terminal-codex-exec/1\\\"");
        Jv qualified = Jv.parse(Files.readString(s.qualified().resolve("acquisition.json")));
        assertThat(qualified.member("profile").text()).isEqualTo("codex-cli-0.166.0-exec-stdio-rollout");
        assertThat(events(s.events())).isNotEmpty().allSatisfy(e -> assertThat(e.path("metadata", "product", "version").text()).isEqualTo("0.166.0"));
        assertThat(events(s.events()).stream().map(e -> e.member("class_name").text() + "/" + e.member("activity_name").text()))
                .contains("Prompt Turn/Prompt submitted", "Tool Call/Requested", "Model Response/Completed", "Prompt Turn/Turn completed");
    }

    @Test void anUnlistedVersionIsAcceptedAndRecorded() throws IOException {
        RealRecording.Sealed s = RealRecording.seal(relabelled("0.170.0"), temp.resolve("as-0.170.0-sealed"));
        assertThat(s.acceptance().verdict()).as(s.acceptance().toString()).isEqualTo("ACCEPTED");
        assertThat(events(s.events())).allSatisfy(e -> assertThat(e.path("metadata", "product", "version").text()).isEqualTo("0.170.0"));
        assertThat(s.report().checks()).filteredOn(c -> "source.compatibility".equals(c.property())).isNotEmpty()
                .allSatisfy(c -> assertThat(c.detail()).contains("0.170.0"));
    }

    @Test void theLegacyTerminalProfileStillReadsAt0_166_0Only() throws IOException {
        RealRecording.Sealed legacy = RealRecording.seal(RealRecording.PUBLIC.resolve(RUN), temp.resolve("legacy"), TerminalReceiver.LEGACY_CODEX_PROFILE);
        assertThat(legacy.acceptance().verdict()).as(legacy.acceptance().toString()).isEqualTo("ACCEPTED");
        Path newer = relabelled("0.170.0");
        assertThatThrownBy(() -> RealRecording.seal(newer, temp.resolve("legacy-0.170.0"), TerminalReceiver.LEGACY_CODEX_PROFILE))
                .hasMessageContaining("0.170.0 is not admitted under terminal profile client-terminal-codex-0.166.0/1");
    }

    @Test void aTamperedByteIsRefused() throws IOException {
        Path run = edited("tampered", false, (dir, m) -> {
            try {
                byte[] bytes = Files.readAllBytes(dir.resolve("stdout.bin"));
                bytes[bytes.length / 2] ^= 1;
                Files.write(dir.resolve("stdout.bin"), bytes);
            }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        assertThatThrownBy(() -> RealRecording.seal(run, temp.resolve("tampered-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("source hash mismatch");
    }

    @Test void aMissingRolloutIsRefused() throws IOException {
        Path run = edited("missing", false, (dir, m) -> {
            try { Files.delete(dir.resolve("native.bin")); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        assertThatThrownBy(() -> RealRecording.seal(run, temp.resolve("missing-sealed"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("declared source absent from the sealed acquisition: native.bin");
    }

    @Test void aWrongRouteIsRefused() throws IOException {
        Path run = edited("route", true, (dir, m) -> ((Jv.Obj) m.path("run", "launch_settings")).put("route", "stdout"));
        assertThatThrownBy(() -> RealRecording.seal(run, temp.resolve("route-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("wrong route");
    }

    @Test void aWrongIdentityIsRefused() throws IOException {
        Path session = edited("identity", true, (dir, m) -> {
            ((Jv.Obj) m.member("run")).put("session_uid", "00000000-0000-0000-0000-000000000000");
            m.member("sources").elements().stream().filter(src -> src.member("owner_session_uid") != null)
                    .forEach(src -> ((Jv.Obj) src).put("owner_session_uid", "00000000-0000-0000-0000-000000000000"));
        });
        assertThatThrownBy(() -> RealRecording.seal(session, temp.resolve("identity-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("wrong thread_id");
        Path version = edited("version-identity", true, (dir, m) -> ((Jv.Obj) m.path("run", "cli")).put("version", "0.170.0"));
        assertThatThrownBy(() -> RealRecording.seal(version, temp.resolve("version-identity-sealed"))).isInstanceOf(IllegalArgumentException.class).hasMessage("wrong profile");
    }
}
