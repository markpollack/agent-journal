package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.PrintStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.FIXTURES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Replays an existing sanitized live pair; altered metadata is labelled rejection control, never fresh runtime evidence. */
class AcquiredExecutionTest {
    static final String FIXTURE = "claude-live-2.1.304-mcp-pair";

    static Jv.Obj acquisition(Path work) throws IOException {
        Path fixture = FIXTURES.resolve(FIXTURE);
        Jv spec = Jv.parse(Files.readString(fixture.resolve("spec.json")));
        Jv expected = Jv.parse(Files.readString(fixture.resolve("native/expected-sources.json")));
        Jv.Arr sources = new Jv.Arr();
        int index = 0;
        for (Jv input : spec.path("assembly", "inputs").elements()) {
            String name = input.member("artifact").text();
            Files.copy(fixture.resolve("native").resolve(name), work.resolve(name));
            Jv.Obj source = new Jv.Obj(((Jv.Obj) expected.member("sources").elements().get(index)).members());
            source.members().putAll(((Jv.Obj) input).members());
            source.put("path", name).put("route", index++ == 0 ? "stdout" : "native_session_file")
                    .put("framing", "lines").put("state", "acquired").put("end", "EOF");
            sources.add(source);
        }
        Files.copy(fixture.resolve("native/assembly-expectation.json"), work.resolve("assembly-expectation.json"));
        return new Jv.Obj().put("kind", "agent-journal/acquired-execution").put("version", "0.1")
                .put("profile", FinalAssemblyClaudeTest.PROFILE).put("profile_id", "claude-code/2.1.304/stdout+session-file")
                .put("acquisition_uid", "replay of sanitized live acquisition").put("complete", true).put("run", spec)
                .put("process", new Jv.Obj().put("status", "exited").put("exit_code", 7)).put("sources", sources);
    }

    static Path save(Path work, Jv manifest) throws IOException {
        Path file = work.resolve("acquisition.json");
        Files.writeString(file, Jv.write(manifest));
        return file;
    }

    @Test
    void frozenPairIsAssembledAndIndependentlyVerifiedWithProcessFailureSeparate(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        Path file = save(work, manifest);
        Path out = work.resolve("output");
        assertThat(FinalAssembly.assemble(file, out)).isEqualTo(OcsfRunWriter.State.COMPLETED);
        var pkg = PackageReader.open(out.resolve("events.jsonl"));
        assertThat(pkg.refused()).isFalse();
        assertThat(Files.readString(out.resolve("events.jsonl"))).contains("\"exit_code\":7");
        ByteArrayOutputStream report = new ByteArrayOutputStream();
        try (PrintStream console = new PrintStream(report)) {
            int result = OcsfReaderTool.run(new String[] {"verify", out.toString(), work.toString(), "--acquisition", file.toString(),
                    "--assembly", work.resolve("assembly-expectation.json").toString()}, console, console);
            assertThat(result).as(report.toString()).isZero();
        }
        for (Jv source : manifest.member("sources").elements()) {
            assertThat(SchemaPin.sha256(Files.readAllBytes(work.resolve(source.member("path").text())))).isEqualTo(source.member("sha256").text());
        }
    }

    @Test
    void corruptHashAndRouteAreRejectedBeforeAnyOutput(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        Jv.Obj source = (Jv.Obj) manifest.member("sources").elements().get(0);
        String hash = source.member("sha256").text();
        source.put("sha256", "wrong");
        Path file = save(work, manifest);
        assertThatThrownBy(() -> FinalAssembly.assemble(file, work.resolve("output"))).hasMessageContaining("hash mismatch");
        assertThat(work.resolve("output")).doesNotExist();
        source.put("sha256", hash).put("route", "sdk_stdio");
        save(work, manifest);
        assertThatThrownBy(() -> FinalAssembly.assemble(file, work.resolve("output"))).hasMessageContaining("binding differs");
        assertThat(work.resolve("output")).doesNotExist();
    }

    @Test
    void interruptedAcquisitionCannotBecomeCompleteAtFileEof(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        ((Jv.Obj) manifest.member("sources").elements().get(0)).put("end", "INTERRUPTED");
        Path file = save(work, manifest);
        Path out = work.resolve("output");
        assertThat(FinalAssembly.assemble(file, out)).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(Files.readString(out.resolve("events.jsonl"))).contains("\"status\":\"incomplete\"");
        try (PrintStream console = new PrintStream(new ByteArrayOutputStream())) {
            assertThat(OcsfReaderTool.run(new String[] {"verify", out.toString(), work.toString(), "--acquisition", file.toString(),
                    "--assembly", work.resolve("assembly-expectation.json").toString()}, console, console)).isNotZero();
        }
    }

    @Test
    void archiveBytesAndMissingRequiredInputAreNeverTreatedAsComplete(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        byte[] raw = {(byte) 0xff, 13, 10};
        Files.write(work.resolve("stderr.bin"), raw);
        ((Jv.Arr) manifest.member("sources")).add(new Jv.Obj().put("artifact", "stderr.bin").put("path", "stderr.bin")
                .put("role", "stderr").put("state", "acquired").put("archive_only", true).put("required", true)
                .put("origin", "recorded stderr").put("acquisition", "replay").put("end", "EOF")
                .put("bytes", raw.length).put("sha256", SchemaPin.sha256(raw)));
        ((Jv.Arr) manifest.member("sources")).add(new Jv.Obj().put("artifact", "stdin.bin").put("state", "missing")
                .put("required", true).put("reason", "synthetic missing-input rejection control"));
        assertThat(FinalAssembly.assemble(save(work, manifest), work.resolve("output"))).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
        assertThat(Files.readAllBytes(work.resolve("stderr.bin"))).isEqualTo(raw);
    }
    @Test
    void changedCallerReceiptCannotPassIndependentVerification(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        Jv.Obj source = (Jv.Obj) manifest.member("sources").elements().get(0);
        Files.move(work.resolve(source.member("path").text()), work.resolve("stdout.bin"));
        source.put("path", "stdout.bin");
        Path file = save(work, manifest);
        Path out = work.resolve("output");
        try (PrintStream console = new PrintStream(new ByteArrayOutputStream())) {
            assertThat(OcsfWriterTool.assemble(new String[] {"assemble", file.toString(), out.toString()}, console, console)).isZero();
            assertThat(verify(work, out, file, console)).isZero();
            ((Jv.Obj) manifest.member("process")).put("exit_code", 0);
            save(work, manifest);
            assertThat(verify(work, out, file, console)).isEqualTo(OcsfReaderTool.EXIT_DEFECTS);
            source.put("sha256", "wrong");
            save(work, manifest);
            assertThat(verify(work, out, file, console)).isEqualTo(OcsfReaderTool.EXIT_REFUSED);
            assertThat(OcsfWriterTool.assemble(new String[] {"assemble", file.toString(), out.toString()}, console, console)).isEqualTo(2);
        }
    }

    @Test
    void unavailableRequiredSourceAndUninterpretedArchiveRemainVisibleToChecker(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        byte[] raw = {(byte) 0xff};
        Files.write(work.resolve("stderr.bin"), raw);
        ((Jv.Arr) manifest.member("sources")).add(new Jv.Obj().put("artifact", "stderr.bin").put("path", "stderr.bin")
                .put("state", "acquired").put("archive_only", true).put("required", false)
                .put("origin", "replay stderr").put("acquisition", "replay").put("end", "EOF")
                .put("bytes", raw.length).put("sha256", SchemaPin.sha256(raw)));
        Path file = save(work, manifest);
        Path out = work.resolve("output");
        assertThat(FinalAssembly.assemble(file, out)).isEqualTo(OcsfRunWriter.State.COMPLETED);
        try (PrintStream console = new PrintStream(new ByteArrayOutputStream())) {
            assertThat(verify(work, out, file, console)).isZero();
            manifest.put("complete", false);
            save(work, manifest);
            Path incomplete = work.resolve("incomplete");
            assertThat(FinalAssembly.assemble(file, incomplete)).isEqualTo(OcsfRunWriter.State.INTERRUPTED);
            assertThat(verify(work, incomplete, file, console)).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        }
    }

    @Test
    void unpinnedMeaningCannotBeAccepted(@TempDir Path work) throws IOException {
        Jv.Obj manifest = acquisition(work);
        ((Jv.Obj) manifest.member("sources").elements().get(0)).members().remove("binding");
        Path file = save(work, manifest);
        Path out = work.resolve("output");
        assertThat(FinalAssembly.assemble(file, out)).isEqualTo(OcsfRunWriter.State.COMPLETED);
        try (PrintStream console = new PrintStream(new ByteArrayOutputStream())) {
            assertThat(verify(work, out, file, console)).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        }
    }

    private static int verify(Path work, Path out, Path file, PrintStream console) throws IOException {
        return OcsfReaderTool.run(new String[] {"verify", out.toString(), work.toString(), "--acquisition", file.toString(),
                "--assembly", work.resolve("assembly-expectation.json").toString()}, console, console);
    }

}
