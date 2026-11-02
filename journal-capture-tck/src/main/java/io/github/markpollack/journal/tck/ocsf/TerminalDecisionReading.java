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
/** Independent accepted-decision shape and semantics checks. */
final class TerminalDecisionReading {
    private TerminalDecisionReading() { }
    static void check(Json d) {
        fields(d, "codec decisionId executionId sessionId connectionId acceptedAt outcome provider correlation"); expect(d, "codec", "client-decision/1");
        for (String key : List.of("decisionId", "executionId", "sessionId", "acceptedAt")) string(d, key);
        instant(string(d, "acceptedAt")); nullable(d, "connectionId"); provider(d.member("provider"));
        Json c = d.member("correlation"); fields(c, "experimentId experimentRunId experimentInvocationId workflowRunId workflowInvocationId workflowAttemptId");
        for (String key : ((Json.JObject) c).members().keySet()) nullable(c, key);
        outcome(d.member("outcome"));
    }
    private static void outcome(Json o) {
        fields(o, "kind failure reason");
        String kind = oneOf(o, "kind", "SUCCESS STOP CANCELLED FAILED");
        if (kind.equals("FAILED")) failure(o.member("failure"));
        else if (!o.member("failure").isNull()) throw new IllegalArgumentException("unexpected failure");
        reason(o, kind);
    }
    private static void failure(Json f) {
            fields(f, "code exceptionClass message causes");
            oneOf(f, "code", "TIMEOUT SETUP_TIMEOUT SCHEDULING SETUP_FAILED PROVIDER_TRANSPORT PROVIDER_PROTOCOL ADVISOR RESULT_COPY CLIENT_PROCESSING PERSISTENCE_FAILED RECORDING_FAILED RECORDING_TIMEOUT RECORDING_CONFLICT");
            rawString(f, "exceptionClass"); rawString(f, "message");
            for (Json cause : array(f, "causes")) if (!(cause instanceof Json.JString)) throw new IllegalArgumentException("invalid cause");
    }
    private static void reason(Json o, String kind) {
        if (kind.equals("STOP") || kind.equals("CANCELLED")) {
            Json reason = o.member("reason"); fields(reason, "code message actor time"); string(reason, "code"); rawString(reason, "message"); rawString(reason, "actor"); instant(string(reason, "time"));
        }
        else if (!o.member("reason").isNull()) throw new IllegalArgumentException("unexpected reason");
    }
}
