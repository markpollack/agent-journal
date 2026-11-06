package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/**
 * Independent interpretation of terminal binding declarations from a detached event file. No writer
 * class, Client class or receiver marker is loaded. Valid declaration bytes do not prove receiver
 * publication, native source membership, complete archive acceptance or Client arbitration.
 */
public final class TerminalBindingReader {
    private TerminalBindingReader() { }

    /** Immutable text snapshots; decision and association remain distinct facts. */
    public record Binding(String producerNamespace, String decision, String association, String originalBindingId) { }

    /** Returns null for a historical file with no binding; refuses unsupported or inconsistent declarations. */
    public static Binding read(DeclaredFile file) {
        Json binding = file.run().member("terminal_binding");
        if (binding == null) return null;
        if (!"0.4.0-prototype".equals(file.format().member("package_version").text())) throw new IllegalArgumentException("terminal binding requires file version 0.4");
        fields(binding, "terminalEnvelope association originalBindingId");
        Decoded decoded = decodeEnvelope(string(binding, "terminalEnvelope"));
        Json p = decoded.envelope(), d = decoded.decision(), initial = decoded.initial();
        String associationText = string(binding, "association"); Json a = exact(associationText); TerminalAssociationReading.check(a, d);
        String parent = binding.member("originalBindingId").isNull() ? null : string(binding, "originalBindingId");
        revision(initial, a, associationText, parent);
        Json spawn = file.run().member("parent");
        if (spawn != null) child(file, spawn, d);
        else {
            TerminalRetainedReading.check(file, p, d, a);
            TerminalNativeReading.check(file, p, d, a);
            TerminalCodexReading.check(file, p, d, a);
        }
        return new Binding(string(p, "producerNamespace"), canonical(d), associationText, parent);
    }

    /**
     * A split child core repeats its root's binding. The root-only acquisition checks belong to the root file; a
     * child is bound by being the executionId's child of the call that spawned it.
     */
    private static void child(DeclaredFile file, Json spawn, Json d) {
        String execution = string(d, "executionId");
        expect(spawn, "run_uid", execution);
        expect(file.run(), "run_uid", execution + "/child/" + string(spawn, "spawn_tool_call_uid"));
    }

    private static void revision(Json initial, Json a, String associationText, String parent) {
        if (parent == null && !canonical(initial).equals(associationText)) throw new IllegalArgumentException("initial association changed");
        if (parent != null) {
            expect(a, "priorRevisionId", string(initial, "revisionId"));
            if (string(a, "revisionId").equals(string(initial, "revisionId"))) throw new IllegalArgumentException("revision identity reused");
        }
    }

    private record Decoded(Json envelope, Json decision, Json initial) { }
    private static Decoded decodeEnvelope(String envelope) {
        Json p = exact(envelope);
        fields(p, "codec producerNamespace executionId decisionId decisionCodec decisionBytes associationCodec associationBytes initialRevisionId receiverContract route profile");
        expect(p, "codec", "journal-terminal-envelope/1"); expect(p, "receiverContract", "journal-terminal-receiver/1");
        expect(p, "route", "client-terminal-evidence/1");
        oneOf(p, "profile", "client-terminal-evidence-only/1 client-terminal-claude-2.1.316/1 client-terminal-claude-retained-2.1.316/1 client-terminal-claude-retained-fresh-2.1.316/1 client-terminal-claude-retained-fresh-2.1.316/2 client-terminal-claude-retained-fresh-2.1.316/3 client-terminal-codex-0.166.0/1 client-terminal-codex-retained-0.166.0/1 client-terminal-codex-retained-0.166.0/2");
        expect(p, "decisionCodec", "client-decision/1"); expect(p, "associationCodec", "journal-association/1");
        Json d = decode(string(p, "decisionBytes")); Json initial = decode(string(p, "associationBytes"));
        TerminalDecisionReading.check(d); TerminalAssociationReading.check(initial, d); expect(initial, "revisionId", string(p, "initialRevisionId"));
        if (!initial.member("priorRevisionId").isNull()) throw new IllegalArgumentException("initial parent is not null");
        expect(p, "executionId", string(d, "executionId")); expect(p, "decisionId", string(d, "decisionId"));
        TerminalRetainedReading.initial(p, d, initial);
        return new Decoded(p, d, initial);
    }

}
