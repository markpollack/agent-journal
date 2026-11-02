package io.github.markpollack.journal.ocsf.writer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;
/** Frozen evidence ownership, identity and extent checks. */
final class TerminalAssociation {
    private TerminalAssociation() { }
    static void check(Jv a, Jv d) {
        fields(a, "codec executionId decisionId revisionId priorRevisionId clientSessionId connectionIds providerObservation acquisition continuation sources missing");
        equal(a, "codec", "journal-association/1"); equal(a, "executionId", text(d, "executionId"));
        equal(a, "decisionId", text(d, "decisionId")); equal(a, "clientSessionId", text(d, "sessionId"));
        text(a, "revisionId"); nullable(a, "priorRevisionId"); provider(a.member("providerObservation"));
        if (!Set.of("NOT_DISPATCHED", "PENDING", "COMPLETED", "INTERRUPTED", "INCOMPLETE", "FAILED", "UNAVAILABLE").contains(text(a, "acquisition")))
            throw new IllegalArgumentException("unsupported acquisition");
        Set<String> connections = connections(a);
        if (!d.member("connectionId").isNull() && !connections.contains(text(d, "connectionId"))) throw new IllegalArgumentException("decision connection absent");
        continuation(a.member("continuation"));
        for (Jv source : array(a, "sources")) source(source, connections);
        for (Jv missing : array(a, "missing")) { fields(missing, "origin reason"); text(missing, "origin"); text(missing, "reason"); }
    }

    private static Set<String> connections(Jv a) {
        Set<String> connections = new HashSet<>();
        for (Jv connection : array(a, "connectionIds")) {
            if (!(connection instanceof Jv.Str s) || s.value().isEmpty() || !connections.add(s.value())) throw new IllegalArgumentException("invalid/duplicate connection");
        }
        return connections;
    }

    private static void continuation(Jv c) {
        if (c.isNull()) return;
        fields(c, "priorExecutionId priorConnectionId relation"); text(c, "priorExecutionId"); text(c, "priorConnectionId");
        if (!Set.of("EXACT_HISTORY_RECONNECT", "APPLICATION_RETRY").contains(text(c, "relation"))) throw new IllegalArgumentException("unsupported continuation");
    }

    private static void source(Jv s, Set<String> connections) {
        fields(s, "sourceId origin relativePath sha256 size start end stream nativeSessionId nativeTurnId nativeConnectionId clientConnectionId");
        text(s, "sourceId"); text(s, "origin"); relative(text(s, "relativePath"));
        if (!text(s, "sha256").matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid source hash");
        extent(s);
        for (String key : List.of("stream", "nativeSessionId", "nativeTurnId", "nativeConnectionId")) nullable(s, key);
        if (!s.member("stream").isNull() && !Set.of("STDIN", "STDOUT", "STDERR").contains(text(s, "stream"))) throw new IllegalArgumentException("invalid stream");
        if (!connections.contains(text(s, "clientConnectionId"))) throw new IllegalArgumentException("source connection absent");
    }
    private static void extent(Jv s) {
        long size = number(s, "size"), start = number(s, "start"), end = number(s, "end");
        if (start < 0 || start > end || end > size) throw new IllegalArgumentException("invalid source extent");
    }

}
