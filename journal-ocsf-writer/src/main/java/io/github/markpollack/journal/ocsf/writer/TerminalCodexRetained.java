package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;
import static io.github.markpollack.journal.ocsf.writer.TerminalCodexProfile.require;

/** Producer-side consistency of one qualified turn with its copied thread declaration; the TCK checks the thread
 * independently. The projection must be the shared session_meta line plus exactly this turn's contiguous region,
 * this turn's originals must be the thread's per-process originals, prefixes must continue append-only and
 * cumulative usage must be the previous cumulative plus this turn, never summed again. */
final class TerminalCodexRetained {
    static final String KIND = "journal-codex-retained-generation/1";
    private static final List<String> USAGE = List.of("input_tokens", "cached_input_tokens", "cache_write_input_tokens", "output_tokens", "reasoning_output_tokens");
    private TerminalCodexRetained() { }

    static void validate(Path base, Jv m, String profile) throws IOException {
        equal(m, "retained_profile", profile);
        Jv thread = m.member("retained_generation"); require(thread instanceof Jv.Obj, "Codex thread declaration absent");
        require(Jv.write(thread).equals(Jv.write(Jv.parse(Files.readString(base.resolve("generation/codex-thread.json"))))), "Codex thread declaration differs from copy");
        equal(thread, "kind", KIND); String session = text(m.member("run"), "session_uid"); equal(thread, "native_session_id", session);
        byte[] transcript = Files.readAllBytes(base.resolve("generation/transcript.bin"));
        equal(thread.member("transcript"), "sha256", SchemaPin.sha256(transcript));
        List<Jv> turns = array(thread, "turns"); require(!turns.isEmpty(), "Codex retained thread without turns");
        require(!TerminalContract.CODEX_RETAINED_PROFILE.equals(profile) || turns.size() == 2, "frozen Codex retained profile requires exactly two turns");
        int index = (int) number(m.member("run"), "invocation_index"); require(index >= 0 && index < turns.size(), "Codex turn index outside thread");
        Jv turn = turns.get(index); equal(turn, "execution_id", text(m, "acquisition_uid")); equal(m, "retained_execution_id", text(m, "acquisition_uid"));
        equal(turn, "native_turn_uid", text(array(m, "turns").get(0), "native_turn_uid"));
        long[][] regions = partition(turns, transcript);
        projection(base, transcript, array(turns.get(0).member("native_records"), "shared").get(0), regions[index]);
        originals(base, turn);
        thread(turns, transcript, regions, session, TerminalContract.CODEX_RETAINED_ORDINARY_PROFILE.equals(profile));
        prefixes(m, turns, index);
        usage(m, turns, index);
    }

    /** Every turn's contiguous region: the partition must cover the whole rollout after the shared session_meta line. */
    private static long[][] partition(List<Jv> turns, byte[] transcript) {
        Jv shared = array(turns.get(0).member("native_records"), "shared").get(0);
        require(number(shared, "start") == 0, "shared session_meta must start the rollout"); extent(transcript, shared);
        long covered = number(shared, "end"); long[][] regions = new long[turns.size()][2];
        for (int i = 0; i < turns.size(); i++) {
            regions[i][0] = covered;
            for (Jv locator : array(turns.get(i).member("native_records"), "records")) { require(number(locator, "start") == covered, "rollout partition gap/overlap"); extent(transcript, locator); covered = number(locator, "end"); }
            regions[i][1] = covered;
        }
        require(covered == transcript.length, "rollout partition omits original bytes"); return regions;
    }

    /** Every turn of the thread, not only the selected one (independent review finding 1, 2026-11-05): distinct identities, the
     * resume rule, an after-prefix at its own region end and cumulative usage arithmetic; an invalid unselected turn refuses the archive. */
    private static void thread(List<Jv> turns, byte[] transcript, long[][] regions, String session, boolean ordinary) {
        Set<String> identities = new HashSet<>(); Jv previous = null;
        for (int i = 0; i < turns.size(); i++) {
            Jv t = turns.get(i);
            require(number(t, "invocation_index") == i, "Codex turn order differs from invocation index");
            for (String key : List.of("execution_id", "connection_id", "native_turn_uid")) require(identities.add(key + "=" + text(t, key)), "Codex turn identity reused");
            if (i == 0) require(t.member("resume_thread_id") == null || t.member("resume_thread_id").isNull(), "Codex first turn declares a resumed thread");
            else equal(t, "resume_thread_id", session);
            closure(t);
            Jv after = t.member("native_prefix_after");
            require(number(after, "bytes") == regions[i][1] && text(after, "sha256").equals(SchemaPin.sha256(Arrays.copyOf(transcript, (int) regions[i][1]))), "Codex after-prefix differs from region end");
            arithmetic(t, previous, ordinary); previous = t.member("thread_token_usage");
        }
    }

    /** Every turn's own closure (follow-up review finding 1, 2026-11-05), not only the selected acquisition's process. */
    private static void closure(Jv t) {
        for (String key : List.of("complete", "stdin_closed", "stdout_natural_eof", "stderr_natural_eof", "observed_resources_terminated"))
            require(t.member(key) instanceof Jv.Bool b && b.value(), "Codex turn closure not observed: " + key);
        require(number(t, "exit_code") == 0 && t.member("pids") instanceof Jv.Arr, "Codex turn process failed");
    }

    /** The frozen /1 profile admits either stdout figure (recorded as the scope observation), exactly as before; the ordinary /2 profile
     * requires the native-observed thread total on a resumed turn (codex-cli 0.166.0 probe, 2026-11-04). */
    private static void arithmetic(Jv turn, Jv previous, boolean ordinary) {
        for (String key : USAGE) require(number(turn.member("thread_token_usage"), key) - (previous == null ? 0 : number(previous, key)) == number(turn.member("turn_token_usage"), key), "Codex cumulative usage arithmetic fails: " + key);
        require(previous == null || !ordinary || "cumulative".equals(TerminalCodexNative.scope(turn)), "Codex resumed turn wire usage is not the thread cumulative figure");
    }

    private static void extent(byte[] bytes, Jv locator) {
        long start = number(locator, "start"), end = number(locator, "end");
        require(start >= 0 && start < end && end <= bytes.length && (start == 0 || bytes[(int) start - 1] == '\n') && bytes[(int) end - 1] == '\n', "locator is not a whole rollout line");
        equal(locator, "sha256", SchemaPin.sha256(Arrays.copyOfRange(bytes, (int) start, (int) end)));
    }

    private static void projection(Path base, byte[] transcript, Jv shared, long[] region) throws IOException {
        byte[] expected = new byte[(int) (number(shared, "end") + region[1] - region[0])];
        System.arraycopy(transcript, 0, expected, 0, (int) number(shared, "end"));
        System.arraycopy(transcript, (int) region[0], expected, (int) number(shared, "end"), (int) (region[1] - region[0]));
        require(Arrays.equals(expected, Files.readAllBytes(base.resolve("turn/transcript.jsonl"))), "Codex projection differs from shared plus region");
    }

    private static void originals(Path base, Jv turn) throws IOException {
        Map<String, String> copies = Map.of("STDIN", "turn/stdin.bin", "STDOUT", "turn/stdout.jsonl", "STDERR", "turn/stderr.bin");
        for (Jv s : array(turn, "sources")) {
            String copy = copies.get(text(s, "role")); require(copy != null, "unsupported per-turn source role");
            equal(s, "sha256", SchemaPin.sha256(Files.readAllBytes(base.resolve(copy))));
        }
    }

    private static void prefixes(Jv m, List<Jv> turns, int index) {
        Jv after = turns.get(index).member("native_prefix_after");
        Jv declared = array(m, "turns").get(0); require(Jv.write(declared.member("native_prefix_after")).equals(Jv.write(after)), "Codex turn after-prefix differs from thread");
        Jv before = declared.member("native_prefix_before");
        if (index == 0) { equal(before, "state", "ABSENT"); equal(before, "observation", "before-first-dispatch"); }
        else require(Jv.write(before).equals(Jv.write(turns.get(index - 1).member("native_prefix_after"))), "Codex before-prefix differs from previous after-prefix");
    }

    private static void usage(Jv m, List<Jv> turns, int index) {
        Jv declared = array(m, "turns").get(0), turn = turns.get(index);
        for (String key : List.of("wire_usage", "turn_token_usage", "thread_token_usage", "total_token_usage")) require(Jv.write(declared.member(key)).equals(Jv.write(turn.member(key))), "Codex usage differs from thread: " + key);
    }
}
