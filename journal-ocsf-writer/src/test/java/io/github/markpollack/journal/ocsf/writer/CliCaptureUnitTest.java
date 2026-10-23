package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.CODEX_STREAM;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_FEATURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The launching capture's small deterministic behaviours, pinned one by one: exit-code meaning, the two command
 * lines, the command-line option parsing and its refusals, the frozen manifest's record counting and no-match form,
 * the version probe's parsing and refusal, the archive's reporting when nothing can be located, and the refusal of
 * an existing output directory. TEST-ONLY stand-ins; nothing here is a live CLI.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class CliCaptureUnitTest {

    @Test
    void exitCodesCarryTheirConventionalMeaning() {
        assertThat(CliCapture.exitMeaning(0)).isEqualTo("0: the CLI reported success");
        assertThat(CliCapture.exitMeaning(1)).startsWith("1: the CLI reported failure").contains("judged separately");
        assertThat(CliCapture.exitMeaning(128)).as("128 itself is not a signal under the convention").startsWith("128: the CLI reported failure");
        assertThat(CliCapture.exitMeaning(129)).contains("(128+1)").contains("terminated by signal 1 ");
        assertThat(CliCapture.exitMeaning(143)).contains("(128+15)").contains("terminated by signal 15");
        assertThat(CliCapture.exitMeaning(159)).contains("terminated by signal 31");
        assertThat(CliCapture.exitMeaning(160)).as("above the signal range").startsWith("160: the CLI reported failure");
    }

    @Test
    void theClaudeCommandLineAddsOnlyWhatWasAsked() {
        assertThat(CliCapture.claudeCommand("claude", "hi", null, null, null)).containsExactly("claude", "-p", "hi", "--output-format", "stream-json", "--verbose");
        assertThat(CliCapture.claudeCommand("claude", "hi", null, List.of(), List.of())).as("an empty tool list adds no --allowedTools").containsExactly("claude", "-p", "hi", "--output-format", "stream-json", "--verbose");
        assertThat(CliCapture.claudeCommand("/x/claude", "hi", "haiku", List.of("Read", "Write"), List.of("--permission-mode", "default")))
                .containsExactly("/x/claude", "-p", "hi", "--output-format", "stream-json", "--verbose", "--model", "haiku", "--allowedTools", "Read", "Write", "--permission-mode", "default");
    }

    @Test
    void theCodexCommandLineAddsOnlyWhatWasAskedAndEndsWithThePrompt() {
        assertThat(CliCapture.codexCommand("codex", "hi", null, null, null, null)).containsExactly("codex", "exec", "--json", "--skip-git-repo-check", "hi");
        assertThat(CliCapture.codexCommand("codex", "hi", "gpt-5", "workspace-write", Path.of("/w"), List.of("--extra")))
                .containsExactly("codex", "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write", "-m", "gpt-5", "-C", "/w", "--extra", "hi");
    }

    @Test
    void theCommandLineRefusesMissingUnknownAndWrongOptions(@TempDir Path work) throws IOException {
        assertThat(main(new String[] {"launch"})).isEqualTo(64);
        assertThat(err).contains("usage: launch");
        assertThat(main(new String[] {"launch", work.resolve("o1").toString(), "--prompt", "hi", "--cwd", work.toString()})).isEqualTo(64);
        assertThat(err).contains("--cli, --prompt and --cwd are required");
        assertThat(main(new String[] {"launch", work.resolve("o2").toString(), "--cli", "gemini", "--prompt", "hi", "--cwd", work.toString()})).isEqualTo(64);
        assertThat(err).contains("--cli must be claude, codex or antigravity");
        assertThat(main(new String[] {"launch", work.resolve("o3").toString(), "--cli", "claude", "--bogus", "x"})).isEqualTo(64);
        assertThat(err).contains("unknown option --bogus");
        assertThat(Files.exists(work.resolve("o1"))).as("a refused launch creates nothing").isFalse();
    }

    @Test
    void theCommandLineParsesSandboxExtraArgumentsAndTheDeadline(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        // a codex stand-in that answers --version and otherwise stays silent until SIGTERM: the deadline parsed from the command line must end it
        Path fake = script(work.resolve("fake-codex-silent.sh"), "if [ \"$1\" = \"--version\" ]; then echo 'codex-cli 0.160.1'; exit 0; fi\n" + CliCaptureLifecycleTest.HOLD_THEN_EXIT_143_ON_TERM);
        int exit = main(new String[] {"launch", work.resolve("out").toString(), "--cli", "codex", "--prompt", "hi", "--cwd", task.toString(), "--executable", fake.toString(),
                "--home", work.resolve("home").toString(), "--run-uid", "opts-test", "--sandbox", "workspace-write", "--model", "gpt-5", "--arg", "--extra-flag", "--cancel-after-ms", "500"});
        assertThat(exit).as(err).isZero();
        assertThat(out).contains("launch INTERRUPTED process cancelled exit 143").contains("cli_version 0.160.1").contains("records 0");
        OcsfPackage p = PackageReader.open(work.resolve("out/package/events.jsonl"));
        assertThat(p.manifest.text("run", "launch_settings", "sandbox_requested")).isEqualTo("workspace-write");
        assertThat(p.manifest.text("run", "launch_settings", "model_requested")).isEqualTo("gpt-5");
        List<String> command = p.manifest.json().member("run").member("launch_settings").member("command").elements().stream().map(j -> j.text()).toList();
        assertThat(command).containsSequence("-s", "workspace-write").containsSequence("-m", "gpt-5").contains("--extra-flag").endsWith("hi");
        assertThat(p.manifest.text("recording", "process", "cancel_reason")).isEqualTo("deliberate cancellation after 500 ms");
    }

    @Test
    void theCommandLineParsesTheRecordCountCancellation(@TempDir Path work) throws Exception {
        Path task = Files.createDirectories(work.resolve("task"));
        Path fake = CliCaptureTest.fakeClaude(work, nativeOf(LIVE_FEATURES, "stream.jsonl"), 100, 0, false);
        int exit = main(new String[] {"launch", work.resolve("out").toString(), "--cli", "claude", "--prompt", "hi", "--cwd", task.toString(), "--executable", fake.toString(),
                "--home", work.resolve("home").toString(), "--cancel-after-records", "4"});
        assertThat(exit).as(err).isZero();
        assertThat(out).contains("launch INTERRUPTED process cancelled");
        OcsfPackage p = PackageReader.open(work.resolve("out/package/events.jsonl"));
        assertThat(p.manifest.text("recording", "process", "cancel_reason")).contains("requested after 4");
    }

    @Test
    void theFrozenManifestCountsRecordsWithOrWithoutATrailingNewlineAndExpectsNoChildOnNoMatch(@TempDir Path work) throws IOException {
        Jv.Obj process = new Jv.Obj().put("how", "exited");
        Path copy = work.resolve("stream.jsonl");
        Files.writeString(copy, "{\"a\":1}\n{\"parent_tool_use_id\":\"t1\"}\n{\"parent_tool_use_id\":\"t1\"}\n{\"parent_tool_use_id\":\"t2\"}\n", StandardCharsets.UTF_8);
        Jv m = Jv.parse(Files.readString(CliCapture.freezeExpectedSources(Files.createDirectories(work.resolve("n1")), copy, "r", "claude_stream", true, process, new Jv.Obj().put("status", "tested"))));
        assertThat(Jv.write(m.path("sources").elements().get(0).path("records"))).isEqualTo("4");
        assertThat(m.path("children").elements()).as("one split core per distinct owner").hasSize(2);
        assertThat(m.path("children").elements().get(0).path("spawn_tool_call_uid").text()).isEqualTo("t1");
        assertThat(Jv.write(m.path("complete"))).isEqualTo("true");

        Files.writeString(copy, "{\"a\":1}\n{\"b\":2", StandardCharsets.UTF_8);
        m = Jv.parse(Files.readString(CliCapture.freezeExpectedSources(Files.createDirectories(work.resolve("n2")), copy, "r", "claude_stream", false, process, null)));
        assertThat(Jv.write(m.path("sources").elements().get(0).path("records"))).as("a cut last line is still a record").isEqualTo("2");
        assertThat(Jv.write(m.path("complete"))).isEqualTo("false");

        Files.writeString(copy, "", StandardCharsets.UTF_8);
        m = Jv.parse(Files.readString(CliCapture.freezeExpectedSources(Files.createDirectories(work.resolve("n3")), copy, "r", "claude_stream", false, process, null)));
        assertThat(Jv.write(m.path("sources").elements().get(0).path("records"))).isEqualTo("0");

        Files.writeString(copy, "{\"parent_tool_use_id\":\"t1\"}\n", StandardCharsets.UTF_8);
        m = Jv.parse(Files.readString(CliCapture.freezeExpectedSources(Files.createDirectories(work.resolve("n4")), copy, "r", "claude_stream", true, process,
                new Jv.Obj().put("status", "no_match").put("detail", "no entry for 9.9.9"))));
        assertThat(m.path("children").elements()).as("a no_match source is retained whole: no split core is expected").isEmpty();
        assertThat(m.path("sources").elements().get(0).path("compatibility").text()).isEqualTo("no_match");
        assertThat(m.path("sources").elements().get(0).path("compatibility_basis").text()).contains("no entry for 9.9.9").contains("no split core");

        m = Jv.parse(Files.readString(CliCapture.freezeExpectedSources(Files.createDirectories(work.resolve("n5")), copy, "r", "codex_stream", true, process, new Jv.Obj().put("status", "tested"))));
        assertThat(m.path("children").elements()).as("the Codex profile has no owner path").isEmpty();
    }

    @Test
    void theVersionProbeParsesBothOutputFormsAndRefusesUnrecognizableOutput(@TempDir Path work) throws IOException {
        assertThat(CliCapture.discoverVersion(List.of(script(work.resolve("v1.sh"), "echo 'codex-cli 0.160.1'\n").toString()), Duration.ofSeconds(5), CliCapture.Cli.CODEX.versionPattern)).isEqualTo("0.160.1");
        assertThat(CliCapture.discoverVersion(List.of(script(work.resolve("v2.sh"), "echo 'codex v1.2.3 (build 7)' >&2\n").toString()), Duration.ofSeconds(5), CliCapture.Cli.CODEX.versionPattern)).as("stderr is read too").isEqualTo("1.2.3");
        assertThatThrownBy(() -> CliCapture.discoverVersion(List.of(script(work.resolve("v3.sh"), "echo 'no version here'\n").toString()), Duration.ofSeconds(5), CliCapture.Cli.CODEX.versionPattern))
                .isInstanceOf(IOException.class).hasMessageContaining("printed no recognizable version").hasMessageContaining("no version here");
    }

    @Test
    void theArchiveReportsWhatItSearchedWhenNothingIsLocated(@TempDir Path work) throws IOException {
        Path task = Files.createDirectories(work.resolve("task"));
        Path home = Files.createDirectories(work.resolve("home"));
        CliCapture claude = new CliCapture(new CliCapture.Launch(CliCapture.Cli.CLAUDE, List.of("x"), task, null, "r", work.resolve("out"), null, null, home, null, null, null), WriterOptions.defaults());
        Jv.Obj none = claude.archiveNativeSessionFile(Files.createDirectories(work.resolve("a1")), null);
        assertThat(none.get("status").text()).isEqualTo("not located");
        assertThat(none.get("detail").text()).contains("no session id");
        assertThat(Jv.write(none.get("parsed"))).isEqualTo("false");
        Jv.Obj missing = claude.archiveNativeSessionFile(Files.createDirectories(work.resolve("a2")), "sess-1");
        assertThat(missing.get("status").text()).isEqualTo("not located");
        assertThat(missing.get("session_uid").text()).isEqualTo("sess-1");
        assertThat(missing.get("searched").elements().get(0).text()).startsWith(home.resolve(".claude").resolve("projects").toString()).endsWith("sess-1.jsonl");
        CliCapture codex = new CliCapture(new CliCapture.Launch(CliCapture.Cli.CODEX, List.of("x"), task, null, "r", work.resolve("out"), null, null, home, null, null, null), WriterOptions.defaults());
        Jv.Obj noSessions = codex.archiveNativeSessionFile(Files.createDirectories(work.resolve("a3")), "thread-1");
        assertThat(noSessions.get("status").text()).isEqualTo("not located");
        assertThat(noSessions.get("searched").elements().get(0).text()).contains(".codex/sessions").contains("thread-1");
    }

    @Test
    void anExistingOutputDirectoryIsRefusedBeforeAnythingIsLaunched(@TempDir Path work) throws IOException {
        Path out = Files.createDirectories(work.resolve("out"));
        CliCapture capture = new CliCapture(new CliCapture.Launch(CliCapture.Cli.CLAUDE, List.of("/nonexistent"), work, null, "r", out, null, null, null, null, null, null), WriterOptions.defaults());
        assertThatThrownBy(capture::run).isInstanceOf(IOException.class).hasMessageContaining("out directory exists");
        assertThat(Files.exists(out.resolve("native"))).isFalse();
    }

    @Test
    void theDefaultLimitsAreFiveSecondsAndThirtySeconds() {
        CliCapture.Launch launch = new CliCapture.Launch(CliCapture.Cli.CODEX, List.of("x"), Path.of("."), null, "r", Path.of("out"), null, null, null, null, null, null);
        assertThat(launch.limits()).isNull();
        assertThat(CliCapture.Limits.DEFAULTS.terminationGrace()).isEqualTo(Duration.ofSeconds(5));
        assertThat(CliCapture.Limits.DEFAULTS.versionProbeTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private String out;
    private String err;

    private int main(String[] args) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int exit = CliCapture.main(args, new PrintStream(o, true, StandardCharsets.UTF_8), new PrintStream(e, true, StandardCharsets.UTF_8));
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return exit;
    }

    static Path script(Path file, String body) throws IOException {
        Files.writeString(file, "#!/bin/bash\n# TEST-ONLY stand-in\n" + body, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }
}
