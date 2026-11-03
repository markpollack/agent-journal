package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.RetainedClaudeAcquisition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiverTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic two-text-turn integration; it makes no native/provider qualification claim. */
class TerminalRetainedTest {
    @TempDir Path temp;
    private static final Path FIXTURE = Path.of("../journal-capture-tck/src/test/resources/retained-text-generation/retained-generation.json");

    static Jv.Obj successful(int turn) {
        return decision().put("executionId", "E" + turn).put("decisionId", "D" + turn).put("sessionId", "L").put("connectionId", "G")
                .put("outcome", Jv.parse("{\"kind\":\"SUCCESS\",\"reason\":null,\"failure\":null}"));
    }
    static Jv.Obj pending(int turn) {
        return association().put("executionId", "E" + turn).put("decisionId", "D" + turn).put("clientSessionId", "L")
                .put("connectionIds", Jv.parse("[\"G\"]")).put("revisionId", "initial-" + turn);
    }
    static byte[] retainedEnvelope(int turn) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(successful(turn), pending(turn)), StandardCharsets.UTF_8));
        return bytes(p.put("profile", TerminalReceiver.RETAINED_PROFILE));
    }
    static Jv.Obj complete(int turn, Path root) throws Exception {
        Jv manifest = Jv.parse(Files.readString(root.resolve("acquisition.json"))); Jv.Arr sources = new Jv.Arr();
        for (Jv source : manifest.member("sources").elements()) {
            String role = source.member("role").text();
            String stream = switch (role) { case "stdin" -> "STDIN"; case "stream" -> "STDOUT"; case "stderr" -> "STDERR"; default -> null; };
            sources.add(occurrence(root, source.member("path").text(), source.member("origin").text(), stream));
        }
        sources.add(occurrence(root, "acquisition.json", "qualified-retained-manifest", null));
        return pending(turn).put("revisionId", "final-" + turn).put("priorRevisionId", "initial-" + turn)
                .put("acquisition", "COMPLETED").put("missing", new Jv.Arr()).put("sources", sources);
    }
    private static Jv.Obj occurrence(Path base, String path, String origin, String stream) throws Exception {
        byte[] bytes = Files.readAllBytes(base.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", origin).put("size", bytes.length)
                .put("sha256", SchemaPin.sha256(bytes)).put("start", 0).put("end", bytes.length)
                .put("stream", stream == null ? Jv.Null.INSTANCE : new Jv.Str(stream)).put("nativeSessionId", "N")
                .put("nativeTurnId", Jv.Null.INSTANCE).put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "G");
    }
    static Path events(Path store, String ack) {
        return Jv.parse(ack).member("artifacts").elements().stream().map(a -> store.resolve(a.member("relativePath").text()))
                .filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
    }

    @Test void twoInitialDecisionsReceiveSeparateCompleteAttachmentsAndKeepExactOriginals() throws Exception {
        Path store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        String[] initial = { receiver.receive(retainedEnvelope(1), temp), receiver.receive(retainedEnvelope(2), temp) };
        for (int turn : List.of(2, 1)) { // Final publication order deliberately differs from execution order.
            var qualified = RetainedClaudeAcquisition.qualify(FIXTURE, "E" + turn, temp.resolve("q" + turn));
            Path root = qualified.manifest().getParent(); Jv.Obj a = complete(turn, root);
            String original = initial[turn - 1]; String id = Jv.parse(original).member("bindingId").text();
            String ack = receiver.receiveRevision(id, bytes(a), root);
            assertThat(receiver.receiveRevision(id, bytes(a), root)).isEqualTo(ack);
            assertThat(receiver.receive(retainedEnvelope(turn), temp)).isEqualTo(original);
            var pkg = PackageReader.open(events(store, ack));
            assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
            assertThat(pkg.canonical().stream().filter(e -> "model_response".equals(e.className))).isNotEmpty().allSatisfy(e -> {
                assertThat(e.json.member("model_ref")).isNull();
                assertThat(e.json.member("usage_observations")).isNull();
            });
            var binding = TerminalBindingReader.read(pkg.declarations);
            assertThat(binding.decision()).isEqualTo(TerminalJson.write(successful(turn)));
            assertThat(binding.originalBindingId()).isEqualTo(id);
            var report = new AcquisitionVerification().verify(pkg, root, qualified.manifest(), qualified.assemblyExpectation());
            assertThat(report.defects()).isEmpty();
            assertThat(OcsfReaderTool.acceptance(report).exit()).as(OcsfReaderTool.acceptance(report).toString()).isZero();
            Path detached = Files.createDirectory(temp.resolve("detached" + turn)).resolve("events.jsonl"); Files.copy(events(store, ack), detached);
            assertThat(TerminalBindingReader.read(PackageReader.open(detached).declarations).decision()).isEqualTo(binding.decision());
        }
    }

    @Test void changedFrozenProfileAndWrongConnectionCannotAttachRetainedSources() throws Exception {
        var qualified = RetainedClaudeAcquisition.qualify(FIXTURE, "E1", temp.resolve("q")); Path root = qualified.manifest().getParent();
        Path store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        byte[] old = envelope(successful(1), pending(1)); String ack = receiver.receive(old, temp);
        assertThatThrownBy(() -> receiver.receive(retainedEnvelope(1), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
        // Evidence-only does not invoke native assembly; its profile stays evidence-only, never silently upgraded.
        String revision = receiver.receiveRevision(Jv.parse(ack).member("bindingId").text(), bytes(complete(1, root)), root);
        assertThat(PackageReader.open(events(store, revision)).declarations.assembly()).isNull();
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(retainedEnvelope(2), StandardCharsets.UTF_8));
        Jv.Obj a = complete(1, root).put("executionId", "E2").put("decisionId", "D2").put("priorRevisionId", "initial-2");
        String second = receiver.receive(bytes(p), temp);
        assertThatThrownBy(() -> receiver.receiveRevision(Jv.parse(second).member("bindingId").text(), bytes(a), root)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void writerRejectsAlteredPrefixPrematureSealAndProjectionEvenWithFreshAssociationHashes() throws Exception {
        for (String fault : List.of("prefix", "seal", "connection", "session", "projection")) {
            var q = RetainedClaudeAcquisition.qualify(FIXTURE, "E1", temp.resolve("q-" + fault)); Path root = q.manifest().getParent();
            Jv.Obj m = (Jv.Obj) Jv.parse(Files.readString(q.manifest())); Jv.Obj generation = (Jv.Obj) m.member("retained_generation");
            switch (fault) {
                case "prefix" -> ((Jv.Obj) generation.member("turns").elements().get(0).member("nativePrefixAfter")).put("sha256", "0".repeat(64));
                case "seal" -> generation.put("sealed", false);
                case "connection" -> generation.put("connectionId", "wrong");
                case "session" -> generation.put("nativeSessionId", "wrong");
                case "projection" -> Files.writeString(root.resolve("turn/stdin.jsonl"), "{}\n");
                default -> throw new AssertionError(fault);
            }
            Files.writeString(root.resolve("generation/retained-generation.json"), Jv.write(generation));
            for (Jv source : m.member("sources").elements()) {
                byte[] actual = Files.readAllBytes(root.resolve(source.member("path").text()));
                ((Jv.Obj) source).put("sha256", SchemaPin.sha256(actual)).put("bytes", actual.length);
            }
            Files.writeString(q.manifest(), Jv.write(m));
            Path store = temp.resolve("store-" + fault); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
            String ack = receiver.receive(retainedEnvelope(1), temp); Jv.Obj a = complete(1, root);
            assertThatThrownBy(() -> receiver.receiveRevision(Jv.parse(ack).member("bindingId").text(), bytes(a), root)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
