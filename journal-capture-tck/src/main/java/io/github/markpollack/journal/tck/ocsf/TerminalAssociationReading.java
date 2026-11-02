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
/** Independent association and extent checks. */
final class TerminalAssociationReading {
    private TerminalAssociationReading() { }
    static void check(Json a, Json d) {
        fields(a, "codec executionId decisionId revisionId priorRevisionId clientSessionId connectionIds providerObservation acquisition continuation sources missing");
        expect(a, "codec", "journal-association/1"); expect(a, "executionId", string(d, "executionId")); expect(a, "decisionId", string(d, "decisionId")); expect(a, "clientSessionId", string(d, "sessionId"));
        string(a, "revisionId"); nullable(a, "priorRevisionId"); provider(a.member("providerObservation"));
        oneOf(a, "acquisition", "NOT_DISPATCHED PENDING COMPLETED INTERRUPTED INCOMPLETE FAILED UNAVAILABLE");
        List<String> connections = connections(a);
        if (!d.member("connectionId").isNull() && !connections.contains(string(d, "connectionId"))) throw new IllegalArgumentException("missing decision connection");
        for (Json source : array(a, "sources")) source(source, connections);
        for (Json missing : array(a, "missing")) { fields(missing, "origin reason"); string(missing, "origin"); string(missing, "reason"); }
        Json continuation = a.member("continuation");
        if (!continuation.isNull()) {
            fields(continuation, "priorExecutionId priorConnectionId relation"); string(continuation, "priorExecutionId"); string(continuation, "priorConnectionId"); oneOf(continuation, "relation", "EXACT_HISTORY_RECONNECT APPLICATION_RETRY");
        }
    }

    private static List<String> connections(Json a) {
        List<String> connections = new ArrayList<>();
        for (Json value : array(a, "connectionIds")) {
            if (!(value instanceof Json.JString s) || s.value().isEmpty() || connections.contains(s.value())) throw new IllegalArgumentException("invalid connection list");
            connections.add(s.value());
        }
        return connections;
    }

    private static void source(Json s, List<String> connections) {
        fields(s, "sourceId origin relativePath sha256 size start end stream nativeSessionId nativeTurnId nativeConnectionId clientConnectionId");
        string(s, "sourceId"); string(s, "origin");
        path(string(s, "relativePath"));
        if (!string(s, "sha256").matches("[0-9a-f]{64}")) throw new IllegalArgumentException("bad source hash");
        extent(s);
        for (String key : List.of("nativeSessionId", "nativeTurnId", "nativeConnectionId")) nullable(s, key);
        if (!s.member("stream").isNull()) oneOf(s, "stream", "STDIN STDOUT STDERR");
        if (!connections.contains(string(s, "clientConnectionId"))) throw new IllegalArgumentException("wrong source connection");
    }
    private static void path(String path) {
        java.nio.file.Path p = java.nio.file.Path.of(path);
        if (p.isAbsolute() || path.equals(".") || path.contains("\\") || p.startsWith("..") || !p.normalize().toString().equals(path)) throw new IllegalArgumentException("unsafe source path");
    }

    private static void extent(Json s) {
        long start = number(s, "start"), end = number(s, "end"), size = number(s, "size");
        if (start < 0 || start > end || end > size) throw new IllegalArgumentException("bad source extent");
    }

}
