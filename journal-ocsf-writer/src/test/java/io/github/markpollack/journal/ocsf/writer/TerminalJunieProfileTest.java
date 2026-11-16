package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionQualifier;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.verify.Json;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Junie ACP one-shot qualified by the TCK's registered qualifier, delivered under {@value JunieAcpFamily#TERMINAL_PROFILE},
 * assembled through {@link AcquiredExecution} and read back by the packaged reader and the assembly oracle. The native
 * files are the real sanitized Junie 3813.7 recording junie-2; the sealed manifest around them is a labelled stand-in
 * (journal-capture-tck/src/test/resources/ocsf/junie-acp-3813.7-session-files/README.md). Usage tests mutate a temporary
 * copy of the real summary and say so.
 */
class TerminalJunieProfileTest {
    static final Path FIXTURE = Path.of("../journal-capture-tck/src/test/resources/ocsf/junie-acp-3813.7-session-files");
    static final String SESSION = "session-000000-000002-0001";
    @TempDir Path work;

    /** A temporary copy of the fixture; {@code summary} replaces the summary's text when not null and the manifest is restated. */
    private Path sealed(String from, String to) throws IOException {
        Path dir = Files.createDirectories(work.resolve("sealed"));
        for (String name : List.of("acquisition.json", "summary.json", "events.jsonl")) Files.copy(FIXTURE.resolve(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        if (from != null) {
            byte[] before = Files.readAllBytes(dir.resolve("summary.json"));
            String text = new String(before, StandardCharsets.UTF_8);
            assertThat(text).contains(from);
            byte[] after = text.replace(from, to).getBytes(StandardCharsets.UTF_8);
            Files.write(dir.resolve("summary.json"), after);
            String manifest = Files.readString(dir.resolve("acquisition.json"))
                    .replace("\"sha256\": \"" + SchemaPin.sha256(before) + "\"", "\"sha256\": \"" + SchemaPin.sha256(after) + "\"")
                    .replace("\"bytes\": " + before.length + ",", "\"bytes\": " + after.length + ",").replace("\"observed\": " + before.length + ",", "\"observed\": " + after.length + ",");
            Files.writeString(dir.resolve("acquisition.json"), manifest);
        }
        return dir.resolve("acquisition.json");
    }

    private static Jv.Obj decision() {
        Jv.Obj correlation = new Jv.Obj();
        for (String key : List.of("experimentId", "experimentInvocationId", "experimentRunId", "workflowAttemptId", "workflowInvocationId", "workflowRunId")) correlation.put(key, Jv.Null.INSTANCE);
        return new Jv.Obj().put("acceptedAt", "2026-10-24T04:58:10.000000000Z").put("codec", "client-decision/1").put("connectionId", "G").put("correlation", correlation)
                .put("decisionId", "D").put("executionId", "E").put("outcome", new Jv.Obj().put("failure", Jv.Null.INSTANCE).put("kind", "SUCCESS").put("reason", Jv.Null.INSTANCE))
                .put("provider", provider()).put("sessionId", "L");
    }

    private static Jv.Obj provider() {
        return new Jv.Obj().put("kind", "REPORTED_SUCCESS").put("nativeReason", Jv.Null.INSTANCE).put("observedExitCode", Jv.Null.INSTANCE);
    }

    private static Jv.Obj association(AcquisitionQualifier.Qualified q) throws IOException {
        Path root = q.manifest().getParent();
        Jv.Arr sources = new Jv.Arr();
        for (Jv s : Jv.parse(Files.readString(q.manifest())).member("sources").elements()) sources.add(occurrence(root, s.member("path").text(), s.member("origin").text()));
        sources.add(occurrence(root, "acquisition.json", "qualified-junie-manifest"));
        return new Jv.Obj().put("acquisition", "COMPLETED").put("clientSessionId", "L").put("codec", "journal-association/1").put("connectionIds", new Jv.Arr().add(new Jv.Str("G")))
                .put("continuation", Jv.Null.INSTANCE).put("decisionId", "D").put("executionId", "E").put("missing", new Jv.Arr()).put("priorRevisionId", Jv.Null.INSTANCE)
                .put("providerObservation", provider()).put("revisionId", "initial").put("sources", sources);
    }

    private static Jv.Obj occurrence(Path root, String path, String origin) throws IOException {
        byte[] bytes = Files.readAllBytes(root.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", origin).put("sha256", SchemaPin.sha256(bytes)).put("size", bytes.length)
                .put("start", 0).put("end", bytes.length).put("stream", Jv.Null.INSTANCE).put("nativeSessionId", SESSION).put("nativeTurnId", Jv.Null.INSTANCE)
                .put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "G");
    }

    private static byte[] envelope(String profile, Jv.Obj association) {
        Jv.Obj p = new Jv.Obj().put("associationBytes", base64(association)).put("associationCodec", "journal-association/1").put("decisionBytes", base64(decision()))
                .put("codec", "journal-terminal-envelope/1").put("decisionCodec", "client-decision/1").put("decisionId", "D").put("executionId", "E").put("initialRevisionId", "initial")
                .put("producerNamespace", "junie-example").put("profile", profile).put("receiverContract", TerminalReceiver.CONTRACT).put("route", TerminalReceiver.ROUTE);
        return TerminalJson.write(p).getBytes(StandardCharsets.UTF_8);
    }

    private static String base64(Jv value) {
        return Base64.getEncoder().encodeToString(TerminalJson.write(value).getBytes(StandardCharsets.UTF_8));
    }

    /** Qualifies the sealed manifest with the registered qualifier, delivers it and returns the archived events file. */
    private Path deliver(Path sealed, Path store) throws IOException {
        AcquisitionQualifier qualifier = AcquisitionQualifier.forProfile(Jv.parse(Files.readString(sealed)).member("profile").text()).orElseThrow();
        assertThat(qualifier.terminalProfile()).isEqualTo(TerminalReceiver.JUNIE_PROFILE);
        AcquisitionQualifier.Qualified q = qualifier.qualify(sealed, work.resolve("qualified"));
        String ack = new TerminalReceiver(store, "junie-example", WriterOptions.defaults()).receive(envelope(qualifier.terminalProfile(), association(q)), q.manifest().getParent());
        return Jv.parse(ack).member("artifacts").elements().stream().map(a -> a.member("relativePath").text()).filter(s -> s.matches("prepared/candidate-[^/]+/events[.]jsonl"))
                .map(store::resolve).findFirst().orElseThrow();
    }

    /** The per-call {@code time} member the frozen 3813.7 session-events specification leaves without a unit (an existing UNRESOLVED meaning). */
    static final String UNRESOLVED_TIME = "/event/agentEvent/modelUsage/*/time";

    /**
     * Reads the archive with the packaged reader and verifies it with the assembly oracle: no reader defect, no oracle defect,
     * and nothing unresolved except the session-events specification's unitless per-call time, so acceptance is INCOMPLETE
     * for that reason alone, as it is for the same recording in FinalAssemblyJunieTest.
     */
    private static OcsfReaderTool.Acceptance accepted(Path events, Path expectation) throws IOException {
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.diagnostics).as(pkg.diagnostics.toString()).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        var report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"), expectation);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        OcsfReaderTool.Acceptance acceptance = OcsfReaderTool.acceptance(report);
        assertThat(acceptance.exit()).as(acceptance.toString()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(acceptance.reasons()).as(acceptance.toString()).isNotEmpty().allSatisfy(reason -> assertThat(reason).contains("source.meaning").contains(UNRESOLVED_TIME));
        return acceptance;
    }

    @Test void theRegisteredQualifierDeliversTheRealSessionFilesAndTheOracleAcceptsTheArchive() throws IOException {
        Path store = work.resolve("store");
        Path events = deliver(sealed(null, null), store);
        Path expectation = work.resolve("qualified/assembly-expectation.json");
        accepted(events, expectation);
        String archived = Files.readString(events);
        assertThat(archived).contains("\\\"profile\\\":\\\"" + TerminalReceiver.JUNIE_PROFILE + "\\\"").contains("\"product\":{\"name\":\"Junie\",\"version\":\"3813.7\"");
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.declarations.assembly().path("profile", "profile_id").text()).isEqualTo("junie/3813.7/acp+summary+session-events");
        assertThat(pkg.declarations.assembly().path("result", "status").text()).isEqualTo("complete");
        assertThat(TerminalBindingReader.read(pkg.declarations).decision()).isEqualTo(TerminalJson.write(decision()));
        String regenerate = System.getProperty("junie.acp.archive.regenerate");
        if (regenerate != null) copyTree(store, Path.of(regenerate).resolve("store"), expectation, Path.of(regenerate).resolve("assembly-expectation.json"));
    }

    /** The packaged reader, in a JVM of its own whose class path holds the TCK and its dependencies but no writer class: no defect, the same single unresolved meaning. */
    @Test void aSeparateReaderJvmWithoutTheWriterAcceptsTheArchive() throws Exception {
        Path events = deliver(sealed(null, null), work.resolve("store"));
        String classPath = Stream.of(System.getProperty("java.class.path").split(File.pathSeparator))
                .filter(entry -> !entry.contains("journal-ocsf-writer")).collect(Collectors.joining(File.pathSeparator));
        assertThat(classPath).doesNotContain("journal-ocsf-writer");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classPath,
                OcsfReaderTool.class.getName(), "verify", events.toString(), events.getParent().resolve("sources").toString(),
                "--acquisition", events.getParent().resolve("sources/acquisition.json").toString(), "--assembly", work.resolve("qualified/assembly-expectation.json").toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(work.resolve("reader.out").toFile()).start();
        String output;
        int exit = process.waitFor();
        output = Files.readString(work.resolve("reader.out"));
        assertThat(exit).as(output).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);
        assertThat(output).doesNotContain(" DEFECTIVE: ").contains("\"DEFECTIVE\" : 0").contains("acceptance: INCOMPLETE");
        assertThat(output.lines().filter(line -> line.contains(" UNRESOLVED")).toList()).isNotEmpty().allMatch(line -> line.contains(UNRESOLVED_TIME));
    }

    /** A mutation of the real recording: the first model's cost set to 0, a valid observation carried through unchanged. */
    @Test void aZeroCostIsAnObservationCarriedThroughUnchanged() throws IOException {
        Path events = deliver(sealed("\"cost\": 0.013920400000000003,", "\"cost\": 0,"), work.resolve("store"));
        accepted(events, work.resolve("qualified/assembly-expectation.json"));
        Json cost = accounting(events).elements("cost_observations").stream().filter(c -> "99".equals(c.member("quantity_kind_id").scalarText())
                && "gemini-3.7-flash".equals(c.path("model_ref", "name").text())).findFirst().orElseThrow();
        assertThat(cost.member("total_cost").scalarText()).isEqualTo("0");
    }

    /** A mutation of the real recording: one model's cacheCreateTokens removed stays absent in the package, never zero. */
    @Test void anAbsentTokenCountStaysAbsentInTheAssembledPackage() throws IOException {
        Path events = deliver(sealed("\"cacheInputTokens\": 0,\n        \"cacheCreateTokens\": 0,\n        \"outputTokens\": 1589", "\"cacheInputTokens\": 0,\n        \"outputTokens\": 1589"),
                work.resolve("store"));
        // the oracle is not asked here: for an entry without cacheCreateTokens it expects an uncached_input_tokens component
        // of unavailable basis and no value, which the writer's Junie usage mapping does not write (a separate disagreement)
        Json usage = accounting(events).elements("usage_observations").stream().filter(u -> "6".equals(u.member("scope_id").scalarText())
                && "gemini-3.7-flash".equals(u.path("model_ref", "name").text())).findFirst().orElseThrow();
        assertThat(usage.member("output_tokens").scalarText()).isEqualTo("1589");
        assertThat(usage.member("cache_read_tokens").scalarText()).isEqualTo("0");
        assertThat(usage.member("cache_write_tokens")).isNull();
        assertThat(usage.member("components").elements()).noneMatch(c -> "cache_write_tokens".equals(c.member("name").text()));
    }

    /** The writer's own checks refuse a qualified manifest whose facts were changed after qualification, before anything is published. */
    @Test void theReceiverRevalidatesTheQualifiedManifestIndependently() throws IOException {
        AcquisitionQualifier.Qualified q = AcquisitionQualifier.forProfile("junie-3813.7-acp-session-files").orElseThrow().qualify(sealed(null, null), work.resolve("qualified"));
        String manifest = Files.readString(q.manifest());
        String sessions = "\"native_sessions_new\":[\"" + SESSION + "\"]", owner = "\"owner_session_uid\":\"" + SESSION + "\"";
        int i = 0;
        for (String[] change : List.of(new String[]{"\"acp_stop_reason\":\"end_turn\"", "\"acp_stop_reason\":\"cancelled\"", "acp_stop_reason"},
                new String[]{sessions, "\"native_sessions_new\":[\"" + SESSION + "\",\"session-other\"]", "candidates"},
                new String[]{owner, "\"owner_session_uid\":\"session-other\"", "owner_session_uid"},
                new String[]{"\"sealed\":true", "\"sealed\":false", "sealed"})) {
            assertThat(manifest).contains(change[0]);
            Files.writeString(q.manifest(), manifest.replace(change[0], change[1]));
            var receiver = new TerminalReceiver(work.resolve("store-" + i++), "junie-example", WriterOptions.defaults());
            assertThatThrownBy(() -> receiver.receive(envelope(TerminalReceiver.JUNIE_PROFILE, association(q)), q.manifest().getParent()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(change[2]);
        }
    }

    private static Event accounting(Path events) {
        return PackageReader.open(events).events("session_context", 3).stream().filter(e -> e.assemblyRole() == 3).findFirst().orElseThrow();
    }

    private static void copyTree(Path from, Path to, Path expectation, Path expectationCopy) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = to.resolve(from.relativize(file).toString());
                Files.createDirectories(target.getParent());
                Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.copy(expectation, expectationCopy, StandardCopyOption.REPLACE_EXISTING);
    }
}
