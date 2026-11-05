package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition;
import io.github.markpollack.journal.tck.ocsf.inventory.CodexRetainedAcquisition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiver.CODEX_RETAINED_ORDINARY_PROFILE;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiver.CODEX_RETAINED_PROFILE;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiverTest.*;
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic Codex threads through the receiver: each turn attaches to its own frozen decision under the profile that admits
 * the thread, the one-shot, frozen and ordinary profiles refuse each other's evidence, and independent readers accept exact originals. */
class TerminalCodexRetainedTest {
    @TempDir Path temp;
    private static final Path THREAD = Path.of("../journal-capture-tck/src/test/resources/ocsf/syn-codex-retained-0.166.0/codex-thread.json");
    private static final Path THREE = Path.of("../journal-capture-tck/src/test/resources/ocsf/syn-codex-retained-3turn-0.166.0/codex-thread.json");

    static List<String> executions(Path thread) { return Jv.parse(read(thread)).member("turns").elements().stream().map(t -> t.member("execution_id").text()).toList(); }
    static String read(Path p) { try { return Files.readString(p); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }
    static Jv perTurn(Path thread, int i) { return Jv.parse(read(thread.resolveSibling("turn-" + i + "/codex-acquisition.json"))); }
    static String nativeTurn(Path thread, int i) { return perTurn(thread, i).member("turns").elements().get(0).member("native_turn_uid").text(); }
    static Jv.Obj decisionFor(Jv m) {
        return decision().put("executionId", m.member("acquisition_uid").text()).put("decisionId", m.member("turns").elements().get(0).member("decision_uid").text())
                .put("sessionId", m.path("process", "client_session_uid").text()).put("connectionId", m.path("process", "connection_uid").text())
                .put("outcome", Jv.parse("{\"kind\":\"SUCCESS\",\"reason\":null,\"failure\":null}"))
                .put("provider", Jv.parse("{\"kind\":\"REPORTED_SUCCESS\",\"nativeReason\":\"SUCCESS\",\"observedExitCode\":null}"));
    }
    static Jv.Obj pendingFor(Jv m, int i) {
        return association().put("executionId", m.member("acquisition_uid").text()).put("decisionId", m.member("turns").elements().get(0).member("decision_uid").text())
                .put("clientSessionId", m.path("process", "client_session_uid").text()).put("connectionIds", new Jv.Arr().add(new Jv.Str(m.path("process", "connection_uid").text())))
                .put("revisionId", "initial-" + i);
    }
    static byte[] envelopeFor(Path thread, int i, String profile) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(decisionFor(perTurn(thread, i)), pendingFor(perTurn(thread, i), i)), StandardCharsets.UTF_8));
        return bytes(p.put("profile", profile));
    }
    static Jv.Obj completeFor(Path thread, int i, Path root) throws Exception {
        Jv m = Jv.parse(Files.readString(root.resolve("acquisition.json"))); String connection = perTurn(thread, i).path("process", "connection_uid").text();
        Jv.Arr sources = new Jv.Arr();
        for (Jv s : m.member("sources").elements()) sources.add(occurrence(root, s.member("path").text(), s.member("origin").text(), Map.of("stdin", "STDIN", "stream", "STDOUT", "stderr", "STDERR").get(s.member("role").text()), m, connection));
        sources.add(occurrence(root, "acquisition.json", "qualified-codex-retained-manifest", null, m, connection));
        return pendingFor(perTurn(thread, i), i).put("revisionId", "final-" + i).put("priorRevisionId", "initial-" + i).put("acquisition", "COMPLETED").put("missing", new Jv.Arr()).put("sources", sources);
    }
    private static Jv.Obj occurrence(Path base, String path, String origin, String stream, Jv m, String connection) throws Exception {
        byte[] bytes = Files.readAllBytes(base.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", origin).put("size", bytes.length).put("sha256", SchemaPin.sha256(bytes))
                .put("start", 0).put("end", bytes.length).put("stream", stream == null ? Jv.Null.INSTANCE : new Jv.Str(stream)).put("nativeSessionId", m.path("run", "session_uid").text())
                .put("nativeTurnId", Jv.Null.INSTANCE).put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", connection);
    }
    static Path events(Path store, String ack) {
        return Jv.parse(ack).member("artifacts").elements().stream().map(a -> store.resolve(a.member("relativePath").text())).filter(p -> p.getFileName().toString().equals("events.jsonl")).findFirst().orElseThrow();
    }

    /** Binds every turn's frozen decision first, then attaches the qualified evidence in the given publication order and reads each archive independently. */
    private void eachTurnAttachesAndReadsBack(Path thread, String profile, List<Integer> order) throws Exception {
        Path store = temp.resolve("store-" + profile.replace('/', '-')); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        List<String> executions = executions(thread); String[] initial = new String[executions.size()];
        for (int i = 0; i < executions.size(); i++) initial[i] = receiver.receive(envelopeFor(thread, i, profile), temp);
        for (int i : order) {
            var qualified = CodexRetainedAcquisition.qualify(thread, executions.get(i), temp.resolve("q-" + profile.replace('/', '-') + "-" + i), profile); Path root = qualified.manifest().getParent();
            String id = Jv.parse(initial[i]).member("bindingId").text(); Jv.Obj a = completeFor(thread, i, root);
            String ack = receiver.receiveRevision(id, bytes(a), root);
            assertThat(receiver.receiveRevision(id, bytes(a), root)).isEqualTo(ack);
            var pkg = PackageReader.open(events(store, ack));
            assertThat(pkg.diagnostics).as("turn %d diagnostics", i).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
            var binding = TerminalBindingReader.read(pkg.declarations);
            assertThat(binding.decision()).isEqualTo(TerminalJson.write(decisionFor(perTurn(thread, i)))); assertThat(binding.originalBindingId()).isEqualTo(id);
            Path sources = events(store, ack).getParent().resolve("sources");
            var report = new AcquisitionVerification().verify(pkg, sources, sources.resolve("acquisition.json"), qualified.assemblyExpectation());
            assertThat(report.defects()).as("turn %d defects", i).isEmpty();
            assertThat(OcsfReaderTool.acceptance(report).exit()).as(OcsfReaderTool.acceptance(report).toString()).isZero();
            String projection = Files.readString(sources.resolve("turn/transcript.jsonl"));
            assertThat(projection).contains(nativeTurn(thread, i));
            for (int other = 0; other < executions.size(); other++) if (other != i) assertThat(projection).doesNotContain(nativeTurn(thread, other));
            assertThat(sources.resolve("generation/codex-thread.original.json")).hasSameBinaryContentAs(thread);
        }
    }

    @Test void twoTurnThreadAttachesUnderTheFrozenProfileInAnyPublicationOrder() throws Exception { eachTurnAttachesAndReadsBack(THREAD, CODEX_RETAINED_PROFILE, List.of(1, 0)); }

    @Test void twoTurnThreadAttachesUnderTheOrdinaryProfile() throws Exception { eachTurnAttachesAndReadsBack(THREAD, CODEX_RETAINED_ORDINARY_PROFILE, List.of(0, 1)); }

    @Test void threeTurnThreadAttachesEveryTurnToItsOwnDecisionUnderTheOrdinaryProfile() throws Exception { eachTurnAttachesAndReadsBack(THREE, CODEX_RETAINED_ORDINARY_PROFILE, List.of(2, 0, 1)); }

    @Test void frozenProfileRefusesThreeTurnEvidenceAndProfilesDoNotRelabelEachOther() throws Exception {
        List<String> executions = executions(THREE);
        var ordinary = CodexRetainedAcquisition.qualify(THREE, executions.get(2), temp.resolve("o2"), CODEX_RETAINED_ORDINARY_PROFILE); Path ordinaryRoot = ordinary.manifest().getParent();
        TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store"), "producer", WriterOptions.defaults());
        String frozenAck = receiver.receive(envelopeFor(THREE, 2, CODEX_RETAINED_PROFILE), temp), frozenId = Jv.parse(frozenAck).member("bindingId").text();
        assertThatThrownBy(() -> receiver.receiveRevision(frozenId, bytes(completeFor(THREE, 2, ordinaryRoot)), ordinaryRoot)).as("ordinary evidence under the frozen envelope").isInstanceOf(IllegalArgumentException.class);
        Jv.Obj relabelled = (Jv.Obj) Jv.parse(Files.readString(ordinary.manifest())); relabelled.put("retained_profile", CODEX_RETAINED_PROFILE);
        Files.writeString(ordinary.manifest(), Jv.write(relabelled));
        assertThatThrownBy(() -> receiver.receiveRevision(frozenId, bytes(completeFor(THREE, 2, ordinaryRoot)), ordinaryRoot)).as("three-turn thread relabelled as frozen").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly two turns");
    }

    @Test void oneShotAndRetainedProfilesRefuseEachOthersEvidenceAndForeignTurns() throws Exception {
        List<String> executions = executions(THREAD);
        var retained0 = CodexRetainedAcquisition.qualify(THREAD, executions.get(0), temp.resolve("r0"), CODEX_RETAINED_PROFILE); Path retainedRoot0 = retained0.manifest().getParent();
        var retained1 = CodexRetainedAcquisition.qualify(THREAD, executions.get(1), temp.resolve("r1"), CODEX_RETAINED_PROFILE); Path retainedRoot1 = retained1.manifest().getParent();
        var oneShot = CodexAcquisition.qualify(THREAD.resolveSibling("turn-0/codex-acquisition.json"), temp.resolve("o0")); Path oneShotRoot = oneShot.manifest().getParent();
        TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store"), "producer", WriterOptions.defaults());
        String oneShotAck = receiver.receive(envelopeFor(THREAD, 0, TerminalReceiver.CODEX_PROFILE), temp), oneShotId = Jv.parse(oneShotAck).member("bindingId").text();
        assertThatThrownBy(() -> receiver.receiveRevision(oneShotId, bytes(completeFor(THREAD, 0, retainedRoot0)), retainedRoot0)).as("thread evidence under the one-shot profile").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receiver.receive(envelopeFor(THREAD, 0, CODEX_RETAINED_PROFILE), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
        TerminalReceiver other = new TerminalReceiver(temp.resolve("store2"), "producer", WriterOptions.defaults());
        String ack0 = other.receive(envelopeFor(THREAD, 0, CODEX_RETAINED_PROFILE), temp), id0 = Jv.parse(ack0).member("bindingId").text();
        assertThatThrownBy(() -> other.receiveRevision(id0, bytes(completeFor(THREAD, 0, oneShotRoot)), oneShotRoot)).as("one-shot evidence under the retained profile").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> other.receiveRevision(id0, bytes(completeFor(THREAD, 1, retainedRoot1)), retainedRoot1)).as("foreign turn attached to the first decision").isInstanceOfAny(IllegalArgumentException.class, TerminalIntegrityException.class);
        TerminalReceiver third = new TerminalReceiver(temp.resolve("store3"), "producer", WriterOptions.defaults());
        String ordinaryAck = third.receive(envelopeFor(THREAD, 0, CODEX_RETAINED_ORDINARY_PROFILE), temp), ordinaryId = Jv.parse(ordinaryAck).member("bindingId").text();
        assertThatThrownBy(() -> third.receiveRevision(ordinaryId, bytes(completeFor(THREAD, 0, retainedRoot0)), retainedRoot0)).as("frozen-profile evidence under the ordinary envelope").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retained_profile");
        Jv.Obj forged = (Jv.Obj) Jv.parse(Files.readString(retained0.manifest())); forged.put("wire_usage_scope", "cumulative");
        Files.writeString(retained0.manifest(), Jv.write(forged));
        assertThatThrownBy(() -> other.receiveRevision(id0, bytes(completeFor(THREAD, 0, retainedRoot0)), retainedRoot0)).as("forged usage scope").isInstanceOf(IllegalArgumentException.class);
    }
}
