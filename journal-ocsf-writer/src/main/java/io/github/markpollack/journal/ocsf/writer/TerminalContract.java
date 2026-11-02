package io.github.markpollack.journal.ocsf.writer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;

/** Validation of the explicitly versioned, evidence-only terminal receiver profile. */
final class TerminalContract {
    static final String CONTRACT = "journal-terminal-receiver/1";
    static final String ROUTE = "client-terminal-evidence/1";
    static final String PROFILE = "client-terminal-evidence-only/1";
    static final String CLAUDE_PROFILE = "client-terminal-claude-2.1.316/1";
    private TerminalContract() { }

    record Input(String envelope, Jv decision, Jv association) { }

    static Input envelope(byte[] bytes, String namespace) {
        Jv p = TerminalJson.read(bytes);
        fields(p, "codec producerNamespace executionId decisionId decisionCodec decisionBytes associationCodec associationBytes initialRevisionId receiverContract route profile");
        equal(p, "codec", "journal-terminal-envelope/1");
        equal(p, "producerNamespace", namespace);
        equal(p, "receiverContract", CONTRACT); equal(p, "route", ROUTE);
        if (!Set.of(PROFILE, CLAUDE_PROFILE).contains(text(p, "profile"))) throw new IllegalArgumentException("unsupported terminal profile");
        equal(p, "decisionCodec", "client-decision/1"); equal(p, "associationCodec", "journal-association/1");
        Jv d = decoded(p, "decisionBytes"); TerminalDecision.check(d);
        Jv a = decoded(p, "associationBytes"); TerminalAssociation.check(a, d);
        equal(p, "executionId", text(d, "executionId")); equal(p, "decisionId", text(d, "decisionId"));
        equal(p, "initialRevisionId", text(a, "revisionId"));
        if (!a.member("priorRevisionId").isNull()) throw new IllegalArgumentException("initial association has a parent");
        return new Input(new String(bytes, StandardCharsets.UTF_8), d, a);
    }

    static Jv decoded(Jv object, String key) {
        String encoded = string(object, key);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new IllegalArgumentException("noncanonical base64");
        return TerminalJson.read(bytes);
    }

}
