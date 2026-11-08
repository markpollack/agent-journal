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
    static final String CLAUDE_PROFILE = ClaudeSdkFamily.TERMINAL_PROFILE;
    static final String LEGACY_CLAUDE_PROFILE = ClaudeSdkFamily.LEGACY_TERMINAL_PROFILE;
    static final String RETAINED_PROFILE = "client-terminal-claude-retained-2.1.316/1";
    /** Frozen: exactly two sealed fresh-start Claude turns. */
    static final String FRESH_RETAINED_PROFILE = "client-terminal-claude-retained-fresh-2.1.316/1";
    /** Ordinary: a sealed fresh-start Claude generation of any turn count under the same per-turn rules. */
    static final String FRESH_RETAINED_ORDINARY_PROFILE = "client-terminal-claude-retained-fresh-2.1.316/2";
    /** Native (2026-11-05): a sealed fresh-start Claude generation of any turn count under the native text-turn model; the TCK qualifier decides membership and ancestry. */
    static final String FRESH_RETAINED_NATIVE_PROFILE = "client-terminal-claude-retained-fresh-2.1.316/3";
    static final Set<String> FRESH_RETAINED_PROFILES = Set.of(FRESH_RETAINED_PROFILE, FRESH_RETAINED_ORDINARY_PROFILE, FRESH_RETAINED_NATIVE_PROFILE);
    /** Frozen: exactly two sealed Codex turns. */
    static final String CODEX_RETAINED_PROFILE = "client-terminal-codex-retained-0.166.0/1";
    /** Ordinary: a sealed Codex thread of any turn count under the same per-turn rules. */
    static final String CODEX_RETAINED_ORDINARY_PROFILE = "client-terminal-codex-retained-0.166.0/2";
    static final Set<String> CODEX_RETAINED_PROFILES = Set.of(CODEX_RETAINED_PROFILE, CODEX_RETAINED_ORDINARY_PROFILE);
    private TerminalContract() { }

    record Input(String envelope, Jv decision, Jv association) { }

    static Input envelope(byte[] bytes, String namespace) {
        Jv p = TerminalJson.read(bytes);
        fields(p, "codec producerNamespace executionId decisionId decisionCodec decisionBytes associationCodec associationBytes initialRevisionId receiverContract route profile");
        equal(p, "codec", "journal-terminal-envelope/1");
        equal(p, "producerNamespace", namespace);
        equal(p, "receiverContract", CONTRACT); equal(p, "route", ROUTE);
        String contract = contract(text(p, "profile"));
        if (!Set.of(PROFILE, LEGACY_CLAUDE_PROFILE, RETAINED_PROFILE, TerminalCodexProfile.LEGACY_PROFILE).contains(contract) && !FRESH_RETAINED_PROFILES.contains(contract) && !CODEX_RETAINED_PROFILES.contains(contract)) throw new IllegalArgumentException("unsupported terminal profile");
        equal(p, "decisionCodec", "client-decision/1"); equal(p, "associationCodec", "journal-association/1");
        Jv d = decoded(p, "decisionBytes"); TerminalDecision.check(d);
        Jv a = decoded(p, "associationBytes"); TerminalAssociation.check(a, d);
        equal(p, "executionId", text(d, "executionId")); equal(p, "decisionId", text(d, "decisionId"));
        equal(p, "initialRevisionId", text(a, "revisionId"));
        if (!a.member("priorRevisionId").isNull()) throw new IllegalArgumentException("initial association has a parent");
        retainedInitial(p, d, a);
        return new Input(new String(bytes, StandardCharsets.UTF_8), d, a);
    }

    /**
     * The contract a terminal profile names, as its versioned profile's wire identifier: each version-neutral profile names
     * its versioned predecessor's contract (the CLI version is recorded, not gated), any other profile its own.
     */
    static String contract(String profile) {
        return CodexExecFamily.contract(ClaudeSdkFamily.contract(profile));
    }

    private static void retainedInitial(Jv p, Jv d, Jv a) {
        String contract = contract(text(p, "profile"));
        if (!RETAINED_PROFILE.equals(contract) && !FRESH_RETAINED_PROFILES.contains(contract) && !CODEX_RETAINED_PROFILES.contains(contract)) return;
        equal(a, "acquisition", "PENDING"); equal(d.member("outcome"), "kind", "SUCCESS");
        if (!array(a, "sources").isEmpty()) throw new IllegalArgumentException("retained initial evidence must be pending");
    }

    static Jv decoded(Jv object, String key) {
        String encoded = string(object, key);
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) throw new IllegalArgumentException("noncanonical base64");
        return TerminalJson.read(bytes);
    }

}
