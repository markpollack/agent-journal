package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OcsfReaderTool;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AcquisitionVerification;
import io.github.markpollack.journal.tck.ocsf.inventory.FreshRetainedClaudeAcquisition;
import io.github.markpollack.journal.tck.ocsf.inventory.RetainedClaudeAcquisition;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiverTest.*;
import static io.github.markpollack.journal.ocsf.writer.TerminalRetainedTest.*;
import static org.assertj.core.api.Assertions.*;

/** Synthetic fresh-transcript-start integration through the receiver; no native/provider qualification claim. */
class TerminalFreshRetainedTest {
    @TempDir Path temp;
    private static final Path FRESH = Path.of("../journal-capture-tck/src/test/resources/retained-fresh-generation/retained-fresh-generation.json");
    private static final Path THREE = Path.of("../journal-capture-tck/src/test/resources/retained-fresh-generation-3turn/retained-fresh-generation.json");
    private static final Path FROZEN = Path.of("../journal-capture-tck/src/test/resources/retained-text-generation/retained-generation.json");
    /** DERIVED-REDACTED native-shaped generation (22 and 15 native records per turn); only the native /3 profile admits it. */
    private static final Path NATIVE = Path.of("../journal-capture-tck/src/test/resources/retained-fresh-generation-native/retained-fresh-generation.json");

    /** Identity of one turn as its generation manifest records it: the synthetic fixtures carry E/D/L/G, the native-shaped one its UUIDs. */
    record Ids(String execution, String decisionId, String session, String connection) {
        static Ids of(Path generation, int turn) throws Exception {
            Jv m = Jv.parse(Files.readString(generation)); Jv t = m.member("turns").elements().get(turn - 1);
            return new Ids(t.member("executionId").text(), t.member("decisionId").text(), m.member("logicalSessionId").text(), m.member("connectionId").text());
        }
        Jv.Obj successful() { return decision().put("executionId", execution).put("decisionId", decisionId).put("sessionId", session).put("connectionId", connection).put("outcome", Jv.parse("{\"kind\":\"SUCCESS\",\"reason\":null,\"failure\":null}")); }
        Jv.Obj pending(int turn) { return association().put("executionId", execution).put("decisionId", decisionId).put("clientSessionId", session).put("connectionIds", new Jv.Arr().add(new Jv.Str(connection))).put("revisionId", "initial-" + turn); }
        byte[] envelope(int turn, String profile) { Jv.Obj p = (Jv.Obj) Jv.parse(new String(TerminalReceiverTest.envelope(successful(), pending(turn)), StandardCharsets.UTF_8)); return bytes(p.put("profile", profile)); }
        Jv.Obj complete(int turn, Path root) throws Exception {
            Jv.Obj a = completeFresh(turn, root).put("executionId", execution).put("decisionId", decisionId).put("clientSessionId", session).put("connectionIds", new Jv.Arr().add(new Jv.Str(connection)));
            for (Jv source : a.member("sources").elements()) ((Jv.Obj) source).put("clientConnectionId", connection);
            return a;
        }
    }

    static byte[] freshEnvelope(int turn) { return freshEnvelope(turn, TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE); }
    static byte[] freshEnvelope(int turn, String profile) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(successful(turn), pending(turn)), StandardCharsets.UTF_8));
        return bytes(p.put("profile", profile));
    }
    static Jv.Obj completeFresh(int turn, Path root) throws Exception {
        Jv manifest = Jv.parse(Files.readString(root.resolve("acquisition.json"))); String session = manifest.member("run").member("session_uid").text();
        Jv.Obj a = complete(turn, root);
        for (Jv source : a.member("sources").elements()) ((Jv.Obj) source).put("nativeSessionId", session);
        return a;
    }

    /** Binds every turn's frozen decision under {@code profile}, attaches the qualified evidence in the given order and reads each archive independently. */
    private void eachTurnAttachesAndReadsBack(Path generation, String profile, List<Integer> order) throws Exception {
        Path store = temp.resolve("store-" + profile.replace('/', '-')); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        String[] initial = new String[order.size()];
        for (int turn = 1; turn <= order.size(); turn++) initial[turn - 1] = receiver.receive(Ids.of(generation, turn).envelope(turn, profile), temp);
        for (int turn : order) {
            Ids ids = Ids.of(generation, turn);
            var qualified = FreshRetainedClaudeAcquisition.qualify(generation, ids.execution(), temp.resolve("q-" + profile.replace('/', '-') + turn), profile);
            Path root = qualified.manifest().getParent(); Jv.Obj a = ids.complete(turn, root);
            String id = Jv.parse(initial[turn - 1]).member("bindingId").text();
            String ack = receiver.receiveRevision(id, bytes(a), root);
            assertThat(receiver.receiveRevision(id, bytes(a), root)).isEqualTo(ack);
            var pkg = PackageReader.open(events(store, ack));
            assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
            var binding = TerminalBindingReader.read(pkg.declarations);
            assertThat(binding.decision()).isEqualTo(TerminalJson.write(ids.successful()));
            var report = new AcquisitionVerification().verify(pkg, root, qualified.manifest(), qualified.assemblyExpectation());
            assertThat(report.defects()).isEmpty();
            assertThat(OcsfReaderTool.acceptance(report).exit()).as(OcsfReaderTool.acceptance(report).toString()).isZero();
            assertThat(Files.readAllBytes(root.resolve("generation/observations.jsonl"))).isEqualTo(Files.readAllBytes(generation.resolveSibling("observations.jsonl")));
            assertThat(Files.readString(root.resolve("turn/stdin.jsonl"))).contains(generation.equals(NATIVE) ? List.of("ALPHA", "you replied last time").get(turn - 1) : List.of("first", "second", "third").get(turn - 1));
        }
    }

    /** Native /3: both turns of the native-shaped generation attach through the receiver and read back on the TCK-only path. */
    @Test void nativeFreshProfileAttachesBothTurnsOfTheNativeShapedGeneration() throws Exception { eachTurnAttachesAndReadsBack(NATIVE, TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE, List.of(2, 1)); }

    /** The versioned 2.1.316 native profile earlier recordings carry stays readable: it attaches the same real-derived generation. */
    @Test void theVersionedNativeProfileStillAttachesThe2_1_316Generation() throws Exception {
        eachTurnAttachesAndReadsBack(NATIVE, TerminalReceiver.LEGACY_FRESH_RETAINED_NATIVE_PROFILE, List.of(1, 2));
    }

    @Test void freshProfileAttachesBothTurnsAndIndependentReadersAcceptExactOriginals() throws Exception { eachTurnAttachesAndReadsBack(FRESH, TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE, List.of(2, 1)); }

    @Test void freshProfileAttachesTwoTurnsInPublicationOrder() throws Exception { eachTurnAttachesAndReadsBack(FRESH, TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE, List.of(1, 2)); }

    @Test void freshProfileAttachesEveryTurnOfAThreeTurnGeneration() throws Exception { eachTurnAttachesAndReadsBack(THREE, TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE, List.of(3, 1, 2)); }

    /** Independent review finding 1 (2026-11-05): admission must compare adjacent native boundaries of every turn, not only the selected one. */
    @Test void receiverRefusesAGenerationWhoseUnselectedTurnBreaksContinuity() throws Exception {
        var q = FreshRetainedClaudeAcquisition.qualify(THREE, "E1", temp.resolve("u1"), TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE); Path root = q.manifest().getParent();
        Jv.Obj m = (Jv.Obj) Jv.parse(Files.readString(q.manifest())); Jv.Obj generation = (Jv.Obj) m.member("retained_generation");
        Jv.Obj third = (Jv.Obj) generation.member("turns").elements().get(2); third.put("nativePrefixBefore", generation.member("turns").elements().get(0).member("nativePrefixAfter"));
        Files.writeString(root.resolve("generation/retained-fresh-generation.json"), Jv.write(generation));
        for (Jv source : m.member("sources").elements()) { byte[] actual = Files.readAllBytes(root.resolve(source.member("path").text())); ((Jv.Obj) source).put("sha256", SchemaPin.sha256(actual)).put("bytes", actual.length); }
        Files.writeString(q.manifest(), Jv.write(m));
        TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store-u1"), "producer", WriterOptions.defaults());
        String ack = receiver.receive(freshEnvelope(1), temp); Jv.Obj a = completeFresh(1, root);
        assertThatThrownBy(() -> receiver.receiveRevision(Jv.parse(ack).member("bindingId").text(), bytes(a), root)).as("E3 before-prefix relabelled to E1 after-prefix").isInstanceOf(IllegalArgumentException.class);
    }

    @Test void frozenAndFreshProfilesCannotBeAttachedToEachOthersGenerations() throws Exception {
        Path store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        var fresh = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("fresh"), TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE); Path freshRoot = fresh.manifest().getParent();
        var frozen = RetainedClaudeAcquisition.qualify(FROZEN, "E1", temp.resolve("frozen")); Path frozenRoot = frozen.manifest().getParent();
        String frozenAck = receiver.receive(retainedEnvelope(1), temp), frozenId = Jv.parse(frozenAck).member("bindingId").text();
        assertThatThrownBy(() -> receiver.receiveRevision(frozenId, bytes(completeFresh(1, freshRoot)), freshRoot)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receiver.receive(freshEnvelope(1), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
        TerminalReceiver other = new TerminalReceiver(temp.resolve("store2"), "producer", WriterOptions.defaults());
        String freshAck = other.receive(freshEnvelope(1), temp), freshId = Jv.parse(freshAck).member("bindingId").text();
        assertThatThrownBy(() -> other.receiveRevision(freshId, bytes(complete(1, frozenRoot)), frozenRoot)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void writerRefusesRelabelledZeroPrefixMissingIdentityAndChangedJournalWithFreshHashes() throws Exception {
        for (String fault : List.of("zero-prefix", "identity", "journal", "profile")) {
            var q = FreshRetainedClaudeAcquisition.qualify(FRESH, "E1", temp.resolve("q-" + fault), TerminalReceiver.FRESH_RETAINED_NATIVE_PROFILE); Path root = q.manifest().getParent();
            Jv.Obj m = (Jv.Obj) Jv.parse(Files.readString(q.manifest())); Jv.Obj generation = (Jv.Obj) m.member("retained_generation");
            switch (fault) {
                case "zero-prefix" -> ((Jv.Obj) generation.member("turns").elements().get(0)).put("nativePrefixBefore",
                        Jv.parse("{\"bytes\":0,\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}"));
                case "identity" -> generation.put("observedNativeIdentity", Jv.Null.INSTANCE);
                case "journal" -> Files.writeString(root.resolve("generation/observations.jsonl"), Files.readString(root.resolve("generation/observations.jsonl")).replace("ENOENT", "EXISTS"));
                case "profile" -> m.put("retained_profile", TerminalReceiver.RETAINED_PROFILE);
                default -> throw new AssertionError(fault);
            }
            Files.writeString(root.resolve("generation/retained-fresh-generation.json"), Jv.write(generation));
            for (Jv source : m.member("sources").elements()) {
                byte[] actual = Files.readAllBytes(root.resolve(source.member("path").text()));
                ((Jv.Obj) source).put("sha256", SchemaPin.sha256(actual)).put("bytes", actual.length);
            }
            Files.writeString(q.manifest(), Jv.write(m));
            TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store-" + fault), "producer", WriterOptions.defaults());
            String ack = receiver.receive(freshEnvelope(1), temp); Jv.Obj a = completeFresh(1, root);
            assertThatThrownBy(() -> receiver.receiveRevision(Jv.parse(ack).member("bindingId").text(), bytes(a), root)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
