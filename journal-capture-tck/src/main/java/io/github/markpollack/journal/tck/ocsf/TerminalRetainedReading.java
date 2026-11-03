package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
import static io.github.markpollack.journal.tck.ocsf.RetainedSource.require;

/** Detached retained identity checks; original-byte qualification runs when sources are available. */
final class TerminalRetainedReading {
    private TerminalRetainedReading() { }

    static void initial(Json envelope, Json decision, Json association) {
        if (!RetainedAcquisition.PROFILE.equals(string(envelope, "profile"))) return;
        expect(association, "acquisition", "PENDING"); expect(decision.member("outcome"), "kind", "SUCCESS");
        require(array(association, "sources").isEmpty(), "retained initial sources must be pending");
    }

    static void check(DeclaredFile file, Json envelope, Json decision, Json association) {
        if (!"COMPLETED".equals(string(association, "acquisition"))) return;
        Json acquisition = file.run().path("launch_settings", "acquired_execution");
        Json generation = acquisition == null ? null : acquisition.member("retained_generation");
        if (!RetainedAcquisition.PROFILE.equals(string(envelope, "profile"))) {
            require(generation == null, "retained generation under wrong frozen profile"); return;
        }
        require(generation instanceof Json.JObject, "retained generation missing");
        expect(generation, "kind", "journal-retained-generation/1");
        expect(generation, "logicalSessionId", string(decision, "sessionId"));
        expect(generation, "connectionId", string(decision, "connectionId"));
        expect(acquisition.member("run"), "session_uid", string(generation, "nativeSessionId"));
        expect(acquisition, "retained_execution_id", string(decision, "executionId"));
        Json turn = RetainedGeneration.turn(generation, string(decision, "executionId"));
        expect(turn, "decisionId", string(decision, "decisionId"));
    }
}
