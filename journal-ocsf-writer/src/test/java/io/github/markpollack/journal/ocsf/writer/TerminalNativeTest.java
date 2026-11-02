package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.TerminalBindingReader;
import io.github.markpollack.journal.tck.ocsf.inventory.ClaudeAcquisition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.TerminalReceiverTest.*;
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic native records test admission boundaries; retained actual source proof is separate. */
class TerminalNativeTest {
    @TempDir Path temp;

    private Path qualified() throws IOException {
        Path raw = Files.createDirectory(temp.resolve("raw"));
        Files.writeString(raw.resolve("stdin.bin"), "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"hello\"}}\n");
        Files.writeString(raw.resolve("stdout.bin"), """
                {"type":"system","subtype":"init","session_id":"native-1","claude_code_version":"2.1.316"}
                {"type":"result","subtype":"success","session_id":"native-1","result":"hello","is_error":false}
                """);
        Files.writeString(raw.resolve("stderr.bin"), "");
        Files.writeString(raw.resolve("native.jsonl"), """
                {"type":"user","uuid":"u1","sessionId":"native-1","version":"2.1.316","message":{"role":"user","content":"hello"}}
                """);
        Jv.Obj m = (Jv.Obj) Jv.parse("""
                {"kind":"agent-journal/acquired-execution","version":"0.1","profile":"claude-code-2.1.316-sdk-stdio-session-file",
                "profile_id":"claude-code/2.1.316/sdk-stdio+session-file","acquisition_uid":"execution-1","complete":true,"capture_errors":[],
                "run":{"run_uid":"execution-1","invocation_index":0,"session_uid":"native-1","capture_start":"2026-11-02T00:00:00Z",
                "cli":{"name":"Claude Code","version":"2.1.316","vendor_name":"Anthropic"},"launch_settings":{"route":"sdk_stdio"}},
                "process":{"connection_uid":"connection-1","argv":["claude","--input-format","stream-json","--output-format","stream-json"],"exit_code":null},
                "turns":[{"invocation_uid":"execution-1","start_offsets":{"STDIN":0,"STDOUT":0,"STDERR":0},"end_offsets":{}}],"sources":[]}
                """);
        Jv.Arr sources = (Jv.Arr) m.member("sources");
        for (String role : List.of("stdin", "stream", "stderr", "transcript")) {
            String path = switch (role) { case "stream" -> "stdout.bin"; case "transcript" -> "native.jsonl"; default -> role + ".bin"; };
            byte[] content = Files.readAllBytes(raw.resolve(path));
            Jv.Obj s = new Jv.Obj().put("artifact", path).put("path", path).put("role", role).put("origin", "observed/" + path)
                    .put("state", "acquired").put("acquisition", "execution-1").put("required", true).put("end", "EOF")
                    .put("bytes", content.length).put("sha256", SchemaPin.sha256(content));
            if (role.equals("stream") || role.equals("transcript")) {
                s.put("profile", role.equals("stream") ? "claude_stream" : "claude_transcript")
                        .put("route", role.equals("stream") ? "sdk_stdio" : "native_session_file")
                        .put("framing", "lines").put("records", role.equals("stream") ? 2 : 1).put("owner_session_uid", "native-1");
            } else s.put("archive_only", true);
            sources.add(s);
            if (!role.equals("transcript")) ((Jv.Obj) m.member("turns").elements().get(0).member("end_offsets")).put(role.equals("stream") ? "STDOUT" : role.toUpperCase(java.util.Locale.ROOT), content.length);
        }
        Path sealed = raw.resolve("sealed.json"); Files.writeString(sealed, Jv.write(m));
        return ClaudeAcquisition.qualify(sealed, temp.resolve("qualified")).manifest().getParent();
    }

    private static Jv.Obj completeAssociation(Path base) throws IOException {
        Jv m = Jv.parse(Files.readString(base.resolve("acquisition.json")));
        Jv.Arr sources = new Jv.Arr();
        for (Jv s : m.member("sources").elements()) {
            String role = s.member("role").text();
            sources.add(occurrence(base, s.member("path").text(), s.member("origin").text(), switch (role) { case "stdin" -> "STDIN"; case "stream" -> "STDOUT"; case "stderr" -> "STDERR"; default -> null; }));
        }
        sources.add(occurrence(base, "acquisition.json", "qualified-manifest", null));
        return association().put("acquisition", "COMPLETED").put("missing", new Jv.Arr()).put("sources", sources);
    }

    private static Jv.Obj occurrence(Path base, String path, String origin, String stream) throws IOException {
        byte[] content = Files.readAllBytes(base.resolve(path));
        return new Jv.Obj().put("sourceId", path).put("relativePath", path).put("origin", origin).put("size", content.length)
                .put("sha256", SchemaPin.sha256(content)).put("start", 0).put("end", content.length)
                .put("stream", stream == null ? Jv.Null.INSTANCE : new Jv.Str(stream)).put("nativeSessionId", "native-1")
                .put("nativeTurnId", Jv.Null.INSTANCE).put("nativeConnectionId", Jv.Null.INSTANCE).put("clientConnectionId", "connection-1");
    }
    private static byte[] nativeEnvelope(Jv a) {
        Jv.Obj p = (Jv.Obj) Jv.parse(new String(envelope(decision(), a), StandardCharsets.UTF_8));
        return bytes(p.put("profile", TerminalReceiver.CLAUDE_PROFILE));
    }
    private static Path events(Path store, String ack) {
        var refs = Jv.parse(ack).member("artifacts").elements(); return store.resolve(refs.get(refs.size() - 1).member("relativePath").text());
    }

    @Test void nativeProfileAssemblesOneArchiveAndPreservesFrozenTimeout() throws Exception {
        Path q = qualified(), store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        byte[] p = nativeEnvelope(completeAssociation(q)); String ack = receiver.receive(p, q);
        assertThat(receiver.receive(p, q)).isEqualTo(ack);
        var pkg = PackageReader.open(events(store, ack));
        assertThat(pkg.diagnostics).noneMatch(d -> d.severity().name().equals("DEFECT") || d.severity().name().equals("REFUSED"));
        assertThat(pkg.manifest.runUid()).isEqualTo("execution-1");
        assertThat(TerminalBindingReader.read(pkg.declarations).decision()).contains("TIMEOUT");
        assertThat(Files.readString(events(store, ack))).contains("\"assembly\"", "claude-code/2.1.316/sdk-stdio+session-file");
        Path detached = Files.createDirectory(temp.resolve("detached")).resolve("events.jsonl"); Files.copy(events(store, ack), detached);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_SOURCE_UNAVAILABLE")).hasSize(5);
    }

    @Test void missingOriginalNativeSourceProducesReaderDiagnostic() throws Exception {
        Path q = qualified(), store = temp.resolve("store");
        TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        Path archive = events(store, receiver.receive(nativeEnvelope(completeAssociation(q)), q));
        Path source = archive.getParent().resolve("sources/native.jsonl");
        Files.move(source, temp.resolve("preserved-native.jsonl"));
        assertThat(PackageReader.open(archive).diagnosticsWithCode("TERMINAL_NATIVE_INVALID")).hasSize(1);
    }

    @Test void pendingThenNativeRevisionRetainsInitialDecisionAndAck() throws Exception {
        Path q = qualified(), store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        byte[] pending = nativeEnvelope(association()); String first = receiver.receive(pending, q);
        Jv.Obj a = completeAssociation(q).put("revisionId", "final-1").put("priorRevisionId", "initial-1");
        String last = receiver.receiveRevision(Jv.parse(first).member("bindingId").text(), bytes(a), q);
        assertThat(receiver.receive(pending, q)).isEqualTo(first);
        assertThat(TerminalBindingReader.read(PackageReader.open(events(store, last)).declarations).decision()).contains("TIMEOUT");
    }

    @Test void missingCorruptWrongAssociatedAndPartialSourcesCannotPublish() throws Exception {
        Path q = qualified(); Jv.Obj original = completeAssociation(q);
        for (String fault : List.of("missing", "connection", "session", "span", "stream", "extra", "hash")) {
            Jv.Obj a = (Jv.Obj) Jv.parse(Jv.write(original)); Jv.Arr list = (Jv.Arr) a.member("sources"); Jv.Obj s = (Jv.Obj) list.elements().get(0);
            switch (fault) {
                case "missing" -> list.elements().remove(1);
                case "connection" -> s.put("clientConnectionId", "wrong");
                case "session" -> s.put("nativeSessionId", "wrong");
                case "span" -> s.put("start", 1);
                case "stream" -> s.put("stream", "STDOUT");
                case "extra" -> list.add(occurrence(q, "assembly-expectation.json", "extra", null));
                case "hash" -> s.put("sha256", "0".repeat(64));
                default -> throw new AssertionError(fault);
            }
            Path store = temp.resolve(fault); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
            assertThatThrownBy(() -> receiver.receive(nativeEnvelope(a), q)).isInstanceOfAny(IllegalArgumentException.class, TerminalIntegrityException.class);
            try (var markers = Files.list(store.resolve("terminal"))) { assertThat(markers.toList()).isEmpty(); }
        }
    }

    @Test void readerIndependentlyRejectsReassignedNativeConnectionAndMissingAssembly() throws Exception {
        Path q = qualified(), store = temp.resolve("store"); TerminalReceiver receiver = new TerminalReceiver(store, "producer", WriterOptions.defaults());
        String ack = receiver.receive(nativeEnvelope(completeAssociation(q)), q);
        Path event = events(store, ack); List<String> lines = Files.readAllLines(event);
        Jv.Obj run = (Jv.Obj) Jv.parse(lines.get(2));
        ((Jv.Obj) run.path("run", "launch_settings", "acquired_execution", "process")).put("connection_uid", "other");
        lines.set(2, Jv.write(run)); Path detached = Files.createDirectory(temp.resolve("bad")).resolve("events.jsonl"); Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
        ((Jv.Obj) run.path("run", "launch_settings")).members().remove("acquired_execution");
        lines.set(2, Jv.write(run)); Files.write(detached, lines);
        assertThat(PackageReader.open(detached).diagnosticsWithCode("TERMINAL_BINDING_INVALID")).hasSize(1);
    }

    @Test void interruptedMissingAndMultipleTurnManifestsCannotClaimCompletedNative() throws Exception {
        Path q = qualified(); String original = Files.readString(q.resolve("acquisition.json"));
        for (String fault : List.of("interrupted", "turn", "incomplete", "version", "wrong-run", "shape", "offset-shape", "prompt")) {
            Jv.Obj m = (Jv.Obj) Jv.parse(original);
            switch (fault) {
                case "interrupted" -> ((Jv.Obj) m.member("sources").elements().get(0)).put("end", "INTERRUPTED");
                case "turn" -> ((Jv.Arr) m.member("turns")).add(m.member("turns").elements().get(0));
                case "incomplete" -> m.put("complete", false);
                case "version" -> ((Jv.Obj) m.path("run", "cli")).put("version", "2.1.317");
                case "wrong-run" -> m.put("acquisition_uid", "other");
                case "shape" -> ((Jv.Obj) m.member("run")).members().remove("cli");
                case "offset-shape" -> ((Jv.Obj) m.member("turns").elements().get(0)).members().remove("end_offsets");
                case "prompt" -> {
                    Files.writeString(q.resolve("stdin.bin"), "{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"other\"}}\n");
                    Jv.Obj s = (Jv.Obj) m.member("sources").elements().get(0); byte[] b = Files.readAllBytes(q.resolve("stdin.bin"));
                    s.put("bytes", b.length).put("sha256", SchemaPin.sha256(b));
                    ((Jv.Obj) m.member("turns").elements().get(0).member("end_offsets")).put("STDIN", b.length);
                }
                default -> throw new AssertionError(fault);
            }
            Files.writeString(q.resolve("acquisition.json"), Jv.write(m));
            TerminalReceiver receiver = new TerminalReceiver(temp.resolve(fault), "producer", WriterOptions.defaults());
            assertThatThrownBy(() -> receiver.receive(nativeEnvelope(completeAssociation(q)), q)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
