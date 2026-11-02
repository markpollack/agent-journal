package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Synthetic decisions/bytes: deterministic receiver proof, not native provider qualification. */
class TerminalReceiverTest {
    @TempDir Path temp;
    private final WriterOptions options = WriterOptions.defaults().withClock(Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC));

    static Jv.Obj decision() {
        return (Jv.Obj) Jv.parse("""
                {"codec":"client-decision/1","decisionId":"decision-1","executionId":"execution-1","sessionId":"session-1","connectionId":"connection-1","acceptedAt":"2026-11-02T00:00:00.000000000Z",
                "outcome":{"kind":"FAILED","reason":null,"failure":{"code":"TIMEOUT","exceptionClass":"java.util.concurrent.TimeoutException","message":"Execution deadline exceeded","causes":[]}},
                "provider":{"kind":"NOT_OBSERVED","nativeReason":null,"observedExitCode":null},
                "correlation":{"experimentId":null,"experimentRunId":null,"experimentInvocationId":null,"workflowRunId":null,"workflowInvocationId":null,"workflowAttemptId":null}}
                """);
    }
    static Jv.Obj association() {
        return (Jv.Obj) Jv.parse("""
                {"codec":"journal-association/1","executionId":"execution-1","decisionId":"decision-1","revisionId":"initial-1","priorRevisionId":null,"clientSessionId":"session-1","connectionIds":["connection-1"],
                "providerObservation":{"kind":"NOT_OBSERVED","nativeReason":null,"observedExitCode":null},"acquisition":"PENDING","continuation":null,"sources":[],"missing":[{"origin":"stdout","reason":"not sealed"}]}
                """);
    }
    static byte[] envelope(Jv d, Jv a) {
        Jv.Obj p = new Jv.Obj().put("codec", "journal-terminal-envelope/1").put("producerNamespace", "producer")
                .put("executionId", d.member("executionId")).put("decisionId", d.member("decisionId"))
                .put("decisionCodec", "client-decision/1").put("decisionBytes", Base64.getEncoder().encodeToString(bytes(d)))
                .put("associationCodec", "journal-association/1").put("associationBytes", Base64.getEncoder().encodeToString(bytes(a)))
                .put("initialRevisionId", a.member("revisionId")).put("receiverContract", TerminalReceiver.CONTRACT).put("route", TerminalReceiver.ROUTE).put("profile", TerminalReceiver.PROFILE);
        return bytes(p);
    }
    static byte[] bytes(Jv j) { return TerminalJson.write(j).getBytes(StandardCharsets.UTF_8); }
    TerminalReceiver receiver() throws IOException { return new TerminalReceiver(temp.resolve("store"), "producer", options); }
    Path marker() { return temp.resolve("store/terminal").resolve(TerminalReceiver.key("producer", "execution-1") + ".json"); }
    Path events(String ack) { return temp.resolve("store").resolve(Jv.parse(ack).member("artifacts").elements().get(Jv.parse(ack).member("artifacts").elements().size() - 1).member("relativePath").text()); }

    @Test void concurrentDuplicatesReturnOneOriginalAckAndDetachedReaderKeepsTimeoutDistinct() throws Exception {
        TerminalReceiver receiver = receiver(); byte[] p = envelope(decision(), association());
        CyclicBarrier barrier = new CyclicBarrier(8); List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) calls.add(() -> { barrier.await(10, TimeUnit.SECONDS); return receiver.receive(p, temp); });
        String ack;
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<String> acks = new ArrayList<>(); for (var future : executor.invokeAll(calls)) acks.add(future.get());
            assertThat(acks).hasSize(8).containsOnly(acks.get(0)); ack = acks.get(0);
        }
        finally { executor.shutdownNow(); }
        Path detached = Files.createDirectory(temp.resolve("detached")).resolve("events.jsonl"); Files.copy(events(ack), detached);
        var read = PackageReader.open(detached); assertThat(read.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        var binding = TerminalBindingReader.read(read.declarations);
        assertThat(binding.decision()).contains("\"kind\":\"FAILED\"", "\"code\":\"TIMEOUT\"");
        assertThat(binding.association()).contains("\"acquisition\":\"PENDING\"");
        assertThat(receiver.receive(p, temp)).isEqualTo(ack);
    }

    @Test void changedDecisionAndAssociationConflictWithoutTouchingOriginal() throws Exception {
        TerminalReceiver receiver = receiver(); receiver.receive(envelope(decision(), association()), temp); byte[] original = Files.readAllBytes(marker());
        Jv.Obj changed = decision(); ((Jv.Obj) changed.path("outcome", "failure")).put("message", "changed");
        assertThatThrownBy(() -> receiver.receive(envelope(changed, association()), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
        Jv.Obj revised = association().put("revisionId", "different");
        assertThatThrownBy(() -> receiver.receive(envelope(decision(), revised), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
        assertThat(Files.readAllBytes(marker())).isEqualTo(original);
    }

    @Test void lostReplyRecoversOriginalAndLateEvidenceCannotReplaceAcceptedDecision() throws Exception {
        AtomicBoolean lost = new AtomicBoolean();
        TerminalReceiver receiver = new TerminalReceiver(temp.resolve("store"), "producer", options.withFaults((op, detail) -> {
            if (op.equals("terminal-reply") && lost.compareAndSet(false, true)) throw new IOException("reply lost");
        }));
        byte[] p = envelope(decision(), association()); assertThatThrownBy(() -> receiver.receive(p, temp)).isInstanceOf(IOException.class);
        String original = receiver.receive(p, temp); byte[] markerBytes = Files.readAllBytes(marker());
        Jv.Obj a = association().put("revisionId", "final-1").put("priorRevisionId", "initial-1").put("acquisition", "COMPLETED");
        ((Jv.Obj) a.member("providerObservation")).put("kind", "REPORTED_SUCCESS").put("observedExitCode", 0);
        String id = Jv.parse(original).member("bindingId").text(); String late = receiver.receiveRevision(id, bytes(a), temp);
        assertThat(receiver.receiveRevision(id, bytes(a), temp)).isEqualTo(late);
        assertThat(Files.readAllBytes(marker())).isEqualTo(markerBytes);
        var binding = TerminalBindingReader.read(PackageReader.open(events(late)).declarations);
        assertThat(binding.decision()).contains("\"kind\":\"FAILED\""); assertThat(binding.association()).contains("REPORTED_SUCCESS");
        a.put("acquisition", "FAILED"); assertThatThrownBy(() -> receiver.receiveRevision(id, bytes(a), temp)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("conflict");
    }

    @Test void partialPreparationAndCorruptionNeverPublishOrOverwrite() throws Exception {
        TerminalReceiver failing = new TerminalReceiver(temp.resolve("store"), "producer", options.withFaults((op, detail) -> {
            if (op.equals("terminal-publish")) throw new IOException("prepublication failure");
        }));
        byte[] p = envelope(decision(), association()); assertThatThrownBy(() -> failing.receive(p, temp)).isInstanceOf(IOException.class);
        assertThat(marker()).doesNotExist(); TerminalReceiver receiver = receiver(); String ack = receiver.receive(p, temp);
        Files.writeString(events(ack), "broken"); assertThatThrownBy(() -> receiver.receive(p, temp)).isInstanceOf(IOException.class).hasMessageContaining("integrity");
        Files.writeString(marker(), "{"); assertThatThrownBy(() -> receiver.receive(p, temp)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(marker())).isEqualTo("{");
    }

    @Test void wrongIdentityNoncanonicalBytesUnknownCodeAndUnsupportedProfileReject() throws Exception {
        TerminalReceiver receiver = receiver(); Jv.Obj d = decision(); Jv.Obj a = association();
        a.put("clientSessionId", "other"); assertThatThrownBy(() -> receiver.receive(envelope(d, a), temp)).isInstanceOf(IllegalArgumentException.class);
        byte[] normal = envelope(d, association());
        assertThatThrownBy(() -> receiver.receive((new String(normal, StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8), temp)).isInstanceOf(IllegalArgumentException.class);
        Jv.Obj unsupported = (Jv.Obj) Jv.parse(new String(normal, StandardCharsets.UTF_8)); unsupported.put("profile", "native-codex");
        assertThatThrownBy(() -> receiver.receive(bytes(unsupported), temp)).isInstanceOf(IllegalArgumentException.class);
        ((Jv.Obj) d.path("outcome", "failure")).put("code", "invented"); assertThatThrownBy(() -> receiver.receive(envelope(d, association()), temp)).isInstanceOf(IllegalArgumentException.class);
        assertThat(marker()).doesNotExist();
    }

    @Test void sealedSourcesValidateBytesSpansConnectionAndPaths() throws Exception {
        byte[] raw = "synthetic raw stdout\n".getBytes(StandardCharsets.UTF_8); Files.write(temp.resolve("stdout.bin"), raw);
        Jv.Obj source = new Jv.Obj().put("sourceId", "source-1").put("origin", "original-wire").put("relativePath", "stdout.bin")
                .put("sha256", TerminalReceiver.hash(raw)).put("size", raw.length).put("start", 0).put("end", raw.length)
                .put("stream", "STDOUT").put("nativeSessionId", Jv.Null.INSTANCE).put("nativeTurnId", Jv.Null.INSTANCE)
                .put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "connection-1");
        Jv.Obj a = association().put("sources", new Jv.Arr().add(source)); TerminalReceiver receiver = receiver();
        source.put("end", raw.length + 1); assertThatThrownBy(() -> receiver.receive(envelope(decision(), a), temp)).isInstanceOf(IllegalArgumentException.class);
        source.put("end", raw.length).put("clientConnectionId", "other"); assertThatThrownBy(() -> receiver.receive(envelope(decision(), a), temp)).isInstanceOf(IllegalArgumentException.class);
        source.put("clientConnectionId", "connection-1").put("relativePath", "../stdout.bin"); assertThatThrownBy(() -> receiver.receive(envelope(decision(), a), temp)).isInstanceOf(IllegalArgumentException.class);
        source.put("relativePath", "stdout.bin").put("sha256", "0".repeat(64)); assertThatThrownBy(() -> receiver.receive(envelope(decision(), a), temp)).isInstanceOf(IOException.class);
        source.put("sha256", TerminalReceiver.hash(raw)); String ack = receiver.receive(envelope(decision(), a), temp);
        assertThat(PackageReader.open(events(ack)).diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT"));
        Files.writeString(temp.resolve("stdout.bin"), "source later mutated"); assertThat(receiver.receive(envelope(decision(), a), temp)).isEqualTo(ack);
    }
    @Test void markerInventoryMustCoverExactSourcesWithoutDuplicatesOrSubstitution() throws Exception {
        byte[] raw = "synthetic".getBytes(StandardCharsets.UTF_8); Files.write(temp.resolve("wire"), raw);
        Jv.Obj source = new Jv.Obj().put("sourceId", "wire").put("origin", "stdout").put("relativePath", "wire")
                .put("sha256", TerminalReceiver.hash(raw)).put("size", raw.length).put("start", 0).put("end", raw.length)
                .put("stream", "STDOUT").put("nativeSessionId", Jv.Null.INSTANCE).put("nativeTurnId", Jv.Null.INSTANCE)
                .put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "connection-1");
        byte[] input = envelope(decision(), association().put("sources", new Jv.Arr().add(source)));
        TerminalReceiver receiver = receiver(); String ack = receiver.receive(input, temp);
        String original = Files.readString(marker());
        Jv stored = Jv.parse(original); Jv.Arr artifacts = (Jv.Arr) stored.path("ack", "artifacts");
        artifacts.elements().remove(0); Files.write(marker(), bytes(stored));
        assertThatThrownBy(() -> receiver.receive(input, temp)).isInstanceOf(IOException.class).hasMessageContaining("inventory");
        stored = Jv.parse(original); artifacts = (Jv.Arr) stored.path("ack", "artifacts");
        artifacts.add(artifacts.elements().get(0)); Files.write(marker(), bytes(stored));
        assertThatThrownBy(() -> receiver.receive(input, temp)).isInstanceOf(IOException.class).hasMessageContaining("duplicate");
        stored = Jv.parse(original); ((Jv.Obj) stored.path("ack", "artifacts").elements().get(0)).put("relativePath", "prepared/other/sources/wire"); Files.write(marker(), bytes(stored));
        assertThatThrownBy(() -> receiver.receive(input, temp)).isInstanceOf(IOException.class).hasMessageContaining("inventory");
        Files.writeString(marker(), original);
        Files.writeString(events(ack).getParent().resolve("sources/wire"), "corrupted");
        assertThat(PackageReader.open(events(ack)).diagnosticsWithCode("TERMINAL_SOURCE_INVALID")).hasSize(1);
        assertThatThrownBy(() -> receiver.receive(input, temp)).isInstanceOf(IOException.class).hasMessageContaining("integrity");
    }

    @Test void independentReaderRejectsChangedAssociationAndUnsupportedFailureCode() throws Exception {
        String ack = receiver().receive(envelope(decision(), association()), temp);
        List<String> lines = Files.readAllLines(events(ack));
        Jv.Obj run = (Jv.Obj) Jv.parse(lines.get(2));
        Jv.Obj binding = (Jv.Obj) run.path("run", "terminal_binding");
        Jv.Obj wrong = association().put("clientSessionId", "wrong"); binding.put("association", TerminalJson.write(wrong));
        lines.set(2, Jv.write(run));
        Path detached = Files.createDirectory(temp.resolve("bad-reader")).resolve("events.jsonl"); Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
        Jv.Obj invalid = decision(); ((Jv.Obj) invalid.path("outcome", "failure")).put("code", "UNKNOWN");
        binding.put("terminalEnvelope", new String(envelope(invalid, association()), StandardCharsets.UTF_8)).put("association", TerminalJson.write(association()));
        lines.set(2, Jv.write(run)); Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
    }

    @Test void malformedEncodingAndRevisionParentRefuseBeforePublication() throws Exception {
        TerminalReceiver receiver = receiver(); byte[] input = envelope(decision(), association());
        for (byte[] invalid : List.of(new byte[]{(byte) 0xff}, "{}".getBytes(StandardCharsets.UTF_8),
                "{\"x\":1,\"x\":1}".getBytes(StandardCharsets.UTF_8), new byte[1024 * 1024 + 1])) {
            assertThatThrownBy(() -> receiver.receive(invalid, temp)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TerminalJson.read("1.0".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TerminalJson.read("true".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receiver.receiveRevision("unknown", bytes(association()), temp)).isInstanceOf(IOException.class);
        String ack = receiver.receive(input, temp); String id = Jv.parse(ack).member("bindingId").text();
        Jv.Obj a = association().put("revisionId", "next").put("priorRevisionId", "wrong");
        assertThatThrownBy(() -> receiver.receiveRevision(id, bytes(a), temp)).isInstanceOf(IllegalArgumentException.class);
        a.put("priorRevisionId", "initial-1");
        assertThatThrownBy(() -> receiver.receiveRevision("wrong", bytes(a), temp)).isInstanceOf(IllegalArgumentException.class);
    }

}
