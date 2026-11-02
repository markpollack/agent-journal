package io.github.markpollack.journal.ocsf.writer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;
/** Accepted decision validation without Client implementation types. */
final class TerminalDecision {
    private TerminalDecision() { }
    static final Set<String> FAILURE_CODES = Set.of("TIMEOUT", "SETUP_TIMEOUT", "SCHEDULING", "SETUP_FAILED",
            "PROVIDER_TRANSPORT", "PROVIDER_PROTOCOL", "ADVISOR", "RESULT_COPY", "CLIENT_PROCESSING",
            "PERSISTENCE_FAILED", "RECORDING_FAILED", "RECORDING_TIMEOUT", "RECORDING_CONFLICT");
    static void check(Jv d) {
        fields(d, "codec decisionId executionId sessionId connectionId acceptedAt outcome provider correlation");
        equal(d, "codec", "client-decision/1");
        for (String key : List.of("decisionId", "executionId", "sessionId")) text(d, key);
        nullable(d, "connectionId"); instant(d, "acceptedAt"); provider(d.member("provider"));
        Jv correlation = d.member("correlation");
        fields(correlation, "experimentId experimentRunId experimentInvocationId workflowRunId workflowInvocationId workflowAttemptId");
        for (String key : ((Jv.Obj) correlation).members().keySet()) nullable(correlation, key);
        outcome(d.member("outcome"));
    }

    private static void outcome(Jv o) {
        fields(o, "kind reason failure"); String kind = text(o, "kind");
        if (!Set.of("SUCCESS", "STOP", "CANCELLED", "FAILED").contains(kind)) throw new IllegalArgumentException("unknown outcome");
        Jv reason = o.member("reason"), failure = o.member("failure");
        reason(reason, kind);
        if (kind.equals("FAILED")) failure(failure);
        else if (!failure.isNull()) throw new IllegalArgumentException("unexpected failure");
    }

    private static void reason(Jv reason, String kind) {
        if (kind.equals("STOP") || kind.equals("CANCELLED")) {
            fields(reason, "code message actor time"); text(reason, "code"); string(reason, "message"); string(reason, "actor"); instant(reason, "time");
        }
        else if (!reason.isNull()) throw new IllegalArgumentException("unexpected reason");
    }

    private static void failure(Jv failure) {
            fields(failure, "code exceptionClass message causes");
            if (!FAILURE_CODES.contains(text(failure, "code"))) throw new IllegalArgumentException("unsupported failure code");
            string(failure, "exceptionClass"); string(failure, "message");
            for (Jv cause : array(failure, "causes")) if (!(cause instanceof Jv.Str)) throw new IllegalArgumentException("cause is not a string");
    }
}
