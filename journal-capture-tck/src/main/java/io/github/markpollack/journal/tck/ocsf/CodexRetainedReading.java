package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Independent read-only check of one qualified Codex retained turn; no writer, Client or qualifier dependency. */
public final class CodexRetainedReading {
    /** Turn members the qualifier copies from the thread declaration onto the per-turn acquisition. */
    public static final List<String> COPIED = List.of("wire_usage", "native_prefix_after", "native_records", "cumulative_usage",
            "turn_token_usage", "thread_token_usage", "total_token_usage", "identity_source", "resume_thread_id");
    private CodexRetainedReading() { }

    /** Declaration equals the copied thread manifest, the thread qualifies, the projection equals shared plus region,
     * the turn declaration matches the thread and the per-turn originals read as one Codex execution. */
    public static void validate(Json acquisition, Path base) throws IOException {
        Json declared = acquisition.member("retained_generation");
        Json original = NeutralJson.readDocument(base.resolve("generation/codex-thread.json"));
        require(Json.sameValue(declared, original), "retained thread declaration changed");
        String profile = CodexExecFamily.contract(text(acquisition, "retained_profile")); require(CodexRetainedGeneration.PROFILES.contains(profile), "unsupported retained profile");
        CodexRetainedGeneration.RetainedThread thread = CodexRetainedGeneration.validate(original, base.resolve("generation"), profile);
        String execution = text(acquisition, "acquisition_uid"); expect(acquisition, "retained_execution_id", execution);
        CodexRetainedGeneration.Turn turn = thread.turn(execution);
        require(java.util.Arrays.equals(thread.projection(turn), Files.readAllBytes(base.resolve("turn/transcript.jsonl"))), "projection differs from shared plus region");
        for (var e : Map.of("STDIN", "turn/stdin.bin", "STDOUT", "turn/stdout.jsonl", "STDERR", "turn/stderr.bin").entrySet())
            require(java.util.Arrays.equals(turn.originals().get(e.getKey()), Files.readAllBytes(base.resolve(e.getValue()))), "per-turn original copy differs");
        Json t = array(acquisition, "turns").get(0);
        for (String key : COPIED) require(Json.sameValue(t.member(key), turn.declaration().member(key)), "turn declaration differs from thread: " + key);
        CodexAcquisitionReading.read(acquisition, base);
    }

    private static String text(Json v, String key) { return v.member(key).text(); }
    private static List<Json> array(Json v, String key) { return ((Json.JArray) v.member(key)).elements(); }
    private static void expect(Json v, String key, String value) { require(value.equals(text(v, key)), "mismatched " + key); }
    private static void require(boolean pass, String reason) { if (!pass) throw new IllegalArgumentException(reason); }
}
