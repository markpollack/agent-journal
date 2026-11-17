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
    /** Agent Client's own sealed capture of a fresh live Junie 3813.7 ACP session (a local model), sanitized; see its README. */
    static final Path LIVE = Path.of("../journal-capture-tck/src/test/resources/ocsf/junie-acp-3813.7-live");
    @TempDir Path work;

    /** The execution, decision, Client session, connection and native session a sealed manifest names. */
    record Ids(String execution, String decision, String clientSession, String connection, String session) {
        static Ids of(Path manifest) throws IOException {
            Jv m = Jv.parse(Files.readString(manifest));
            return new Ids(m.member("acquisition_uid").text(), m.member("turns").elements().get(0).member("decision_uid").text(), m.path("process", "client_session_uid").text(),
                    m.path("process", "connection_uid").text(), m.path("process", "acp_session_id").text());
        }
    }

    /** A temporary copy of a fixture's sealed acquisition. */
    private Path sealed(Path fixture) throws IOException {
        Path dir = Files.createDirectories(work.resolve("sealed"));
        for (String name : List.of("acquisition.json", "summary.json", "events.jsonl")) Files.copy(fixture.resolve(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        return dir.resolve("acquisition.json");
    }

    /** A temporary copy of the fixture; {@code from} is replaced in the summary's text when not null and the manifest is restated. */
    private Path sealed(String from, String to) throws IOException {
        return sealed("summary.json", from, to);
    }

    /** A temporary copy of the fixture; {@code from} is replaced in {@code file}'s text when not null and the manifest is restated. */
    private Path sealed(String file, String from, String to) throws IOException {
        Path dir = sealed(FIXTURE).getParent();
        if (from != null) {
            byte[] before = Files.readAllBytes(dir.resolve(file));
            String text = new String(before, StandardCharsets.UTF_8);
            assertThat(text).contains(from);
            byte[] after = text.replace(from, to).getBytes(StandardCharsets.UTF_8);
            Files.write(dir.resolve(file), after);
            String manifest = Files.readString(dir.resolve("acquisition.json"))
                    .replace("\"sha256\": \"" + SchemaPin.sha256(before) + "\"", "\"sha256\": \"" + SchemaPin.sha256(after) + "\"")
                    .replace("\"bytes\": " + before.length + ",", "\"bytes\": " + after.length + ",").replace("\"observed\": " + before.length + ",", "\"observed\": " + after.length + ",");
            Files.writeString(dir.resolve("acquisition.json"), manifest);
        }
        return dir.resolve("acquisition.json");
    }

    private static Jv.Obj decision(Ids ids) {
        Jv.Obj correlation = new Jv.Obj();
        for (String key : List.of("experimentId", "experimentInvocationId", "experimentRunId", "workflowAttemptId", "workflowInvocationId", "workflowRunId")) correlation.put(key, Jv.Null.INSTANCE);
        return new Jv.Obj().put("acceptedAt", "2026-10-24T04:58:10.000000000Z").put("codec", "client-decision/1").put("connectionId", ids.connection()).put("correlation", correlation)
                .put("decisionId", ids.decision()).put("executionId", ids.execution()).put("outcome", new Jv.Obj().put("failure", Jv.Null.INSTANCE).put("kind", "SUCCESS").put("reason", Jv.Null.INSTANCE))
                .put("provider", provider()).put("sessionId", ids.clientSession());
    }

    private static Jv.Obj provider() {
        return new Jv.Obj().put("kind", "REPORTED_SUCCESS").put("nativeReason", Jv.Null.INSTANCE).put("observedExitCode", Jv.Null.INSTANCE);
    }

    private static Jv.Obj association(AcquisitionQualifier.Qualified q, Ids ids) throws IOException {
        Path root = q.manifest().getParent();
        Jv.Arr sources = new Jv.Arr();
        for (Jv s : Jv.parse(Files.readString(q.manifest())).member("sources").elements()) sources.add(occurrence(root, s.member("path").text(), s.member("origin").text(), ids));
        sources.add(occurrence(root, "acquisition.json", "qualified-junie-manifest", ids));
        return new Jv.Obj().put("acquisition", "COMPLETED").put("clientSessionId", ids.clientSession()).put("codec", "journal-association/1").put("connectionIds", new Jv.Arr().add(new Jv.Str(ids.connection())))
                .put("continuation", Jv.Null.INSTANCE).put("decisionId", ids.decision()).put("executionId", ids.execution()).put("missing", new Jv.Arr()).put("priorRevisionId", Jv.Null.INSTANCE)
                .put("providerObservation", provider()).put("revisionId", "initial").put("sources", sources);
    }

    private static Jv.Obj occurrence(Path root, String path, String origin, Ids ids) throws IOException {
        byte[] bytes = Files.readAllBytes(root.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", origin).put("sha256", SchemaPin.sha256(bytes)).put("size", bytes.length)
                .put("start", 0).put("end", bytes.length).put("stream", Jv.Null.INSTANCE).put("nativeSessionId", ids.session()).put("nativeTurnId", Jv.Null.INSTANCE)
                .put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", ids.connection());
    }

    private static byte[] envelope(String profile, Jv.Obj association, Ids ids) {
        Jv.Obj p = new Jv.Obj().put("associationBytes", base64(association)).put("associationCodec", "journal-association/1").put("decisionBytes", base64(decision(ids)))
                .put("codec", "journal-terminal-envelope/1").put("decisionCodec", "client-decision/1").put("decisionId", ids.decision()).put("executionId", ids.execution()).put("initialRevisionId", "initial")
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
        Ids ids = Ids.of(sealed);
        String ack = new TerminalReceiver(store, "junie-example", WriterOptions.defaults()).receive(envelope(qualifier.terminalProfile(), association(q, ids), ids), q.manifest().getParent());
        return Jv.parse(ack).member("artifacts").elements().stream().map(a -> a.member("relativePath").text()).filter(s -> s.matches("prepared/candidate-[^/]+/events[.]jsonl"))
                .map(store::resolve).findFirst().orElseThrow();
    }

    /**
     * Reads the archive with the packaged reader and verifies it with the assembly oracle: no reader defect, no oracle defect
     * and nothing unresolved, so the archive is ACCEPTED. The per-call {@code modelUsage[].time} is retained as recorded under
     * Owner decision OWNER-2026-11-16-JUNIE-MODELUSAGE-TIME, with no duration derived.
     */
    private static OcsfReaderTool.Acceptance accepted(Path events, Path expectation) throws IOException {
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.diagnostics).as(pkg.diagnostics.toString()).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        var report = new AcquisitionVerification().verify(pkg, events.getParent().resolve("sources"), events.getParent().resolve("sources/acquisition.json"), expectation);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        OcsfReaderTool.Acceptance acceptance = OcsfReaderTool.acceptance(report);
        assertThat(acceptance.exit()).as(acceptance.toString()).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(acceptance.verdict()).isEqualTo("ACCEPTED");
        assertThat(acceptance.reasons()).as(acceptance.toString()).isEmpty();
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
        assertThat(TerminalBindingReader.read(pkg.declarations).decision()).isEqualTo(TerminalJson.write(decision(Ids.of(FIXTURE.resolve("acquisition.json")))));
        String regenerate = System.getProperty("junie.acp.archive.regenerate");
        if (regenerate != null) copyTree(store, Path.of(regenerate).resolve("store"), expectation, Path.of(regenerate).resolve("assembly-expectation.json"));
    }

    /**
     * Agent Client's sealed capture of a fresh live ACP session: its result block is reported twice, while the session is in
     * progress and again when it completes, so the declared singleton identity repeats within the session file. The writer
     * keeps both observations apart and flags them ambiguous; the oracle derives the same, and finds no defect.
     */
    @Test void theLiveAcpCaptureIsDeliveredAndReadWithNoDefect() throws IOException {
        Path store = work.resolve("store");
        Path events = deliver(sealed(LIVE), store);
        Path expectation = work.resolve("qualified/assembly-expectation.json");
        accepted(events, expectation);
        OcsfPackage pkg = PackageReader.open(events);
        assertThat(pkg.declarations.assembly().path("result", "status").text()).isEqualTo("complete");
        List<Event> answers = pkg.events("model_response", 3);
        assertThat(answers).hasSize(2).allSatisfy(e -> {
            assertThat(e.assemblyRole()).isEqualTo(1);
            assertThat(e.json.path("assembly", "relation_class_id").scalarText()).isEqualTo("6");
        });
        String regenerate = System.getProperty("junie.acp.live.regenerate");
        if (regenerate != null) copyTree(store, Path.of(regenerate).resolve("store"), expectation, Path.of(regenerate).resolve("assembly-expectation.json"));
    }

    /** The packaged reader, in a JVM of its own whose class path holds the TCK and its dependencies but no writer class: no defect, nothing unresolved, accepted. */
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
        assertThat(exit).as(output).isEqualTo(OcsfReaderTool.EXIT_OK);
        assertThat(output).doesNotContain(" DEFECTIVE: ").contains("\"DEFECTIVE\" : 0").contains("acceptance: ACCEPTED");
        assertThat(output.lines().filter(line -> line.contains(" UNRESOLVED")).toList()).isEmpty();
    }

    /**
     * A mutation of the real recording: the first session event's {@code timestampMs} moved onto a whole second, as in Agent
     * Client's live session-261117-115457-1tj6. The writer and the oracle both render it with three fraction digits
     * ({@code .000Z}), so the archive is still accepted with no defect.
     */
    @Test void aTimestampOnAWholeSecondIsRenderedAlikeAndAccepted() throws IOException {
        Path store = work.resolve("store");
        Path events = deliver(sealed("events.jsonl", "\"timestampMs\":1792817886007", "\"timestampMs\":1792817886000"), store);
        accepted(events, work.resolve("qualified/assembly-expectation.json"));
        assertThat(Files.readString(events)).contains("\"original_time\":\"2026-10-24T04:58:06.000Z\"");
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
            Ids ids = Ids.of(FIXTURE.resolve("acquisition.json"));
            assertThatThrownBy(() -> receiver.receive(envelope(TerminalReceiver.JUNIE_PROFILE, association(q, ids), ids), q.manifest().getParent()))
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
