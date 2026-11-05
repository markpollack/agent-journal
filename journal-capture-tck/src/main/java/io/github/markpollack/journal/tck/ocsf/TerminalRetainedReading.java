package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
import static io.github.markpollack.journal.tck.ocsf.RetainedSource.require;

/** Detached retained identity checks; original-byte qualification runs when sources are available.
 * The frozen and fresh-start profiles select distinct generation kinds; neither reads as the other. */
final class TerminalRetainedReading {
    private TerminalRetainedReading() { }

    private static boolean retained(Json envelope) {
        String profile = string(envelope, "profile");
        return RetainedAcquisition.PROFILE.equals(profile) || FreshRetainedGeneration.PROFILE.equals(profile);
    }

    static void initial(Json envelope, Json decision, Json association) {
        if (!retained(envelope)) return;
        expect(association, "acquisition", "PENDING"); expect(decision.member("outcome"), "kind", "SUCCESS");
        require(array(association, "sources").isEmpty(), "retained initial sources must be pending");
    }

    static void check(DeclaredFile file, Json envelope, Json decision, Json association) {
        if (!"COMPLETED".equals(string(association, "acquisition"))) return;
        Json acquisition = file.run().path("launch_settings", "acquired_execution");
        Json generation = acquisition == null ? null : acquisition.member("retained_generation");
        if (!retained(envelope)) {
            require(generation == null || CodexRetainedGeneration.PROFILES.contains(string(envelope, "profile")), "retained generation under wrong frozen profile"); return;
        }
        boolean fresh = FreshRetainedGeneration.PROFILE.equals(string(envelope, "profile"));
        require(generation instanceof Json.JObject, "retained generation missing");
        expect(generation, "kind", fresh ? FreshRetainedGeneration.KIND : "journal-retained-generation/1");
        if (fresh) expect(acquisition, "retained_profile", FreshRetainedGeneration.PROFILE);
        else require(acquisition.member("retained_profile") == null, "frozen retained profile carries no fresh profile");
        expect(generation, "logicalSessionId", string(decision, "sessionId"));
        expect(generation, "connectionId", string(decision, "connectionId"));
        expect(acquisition.member("run"), "session_uid", string(generation, "nativeSessionId"));
        expect(acquisition, "retained_execution_id", string(decision, "executionId"));
        Json turn = RetainedGeneration.turn(generation, string(decision, "executionId"));
        expect(turn, "decisionId", string(decision, "decisionId"));
    }
}
