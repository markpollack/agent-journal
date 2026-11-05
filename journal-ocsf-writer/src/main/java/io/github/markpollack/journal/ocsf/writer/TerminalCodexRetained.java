package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
        long[] region = partition(turns, transcript, index);
        projection(base, transcript, array(turns.get(0).member("native_records"), "shared").get(0), region);
        originals(base, turn);
        prefixes(m, turns, index, transcript, region[1]);
        usage(m, turns, index);
    }

    private static long[] partition(List<Jv> turns, byte[] transcript, int index) {
        Jv shared = array(turns.get(0).member("native_records"), "shared").get(0);
        require(number(shared, "start") == 0, "shared session_meta must start the rollout"); extent(transcript, shared);
        long covered = number(shared, "end"); long[] region = new long[2];
        for (int i = 0; i < turns.size(); i++) {
            if (i == index) region[0] = covered;
            for (Jv locator : array(turns.get(i).member("native_records"), "records")) { require(number(locator, "start") == covered, "rollout partition gap/overlap"); extent(transcript, locator); covered = number(locator, "end"); }
            if (i == index) region[1] = covered;
        }
        require(covered == transcript.length, "rollout partition omits original bytes"); return region;
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

    private static void prefixes(Jv m, List<Jv> turns, int index, byte[] transcript, long end) {
        Jv after = turns.get(index).member("native_prefix_after");
        require(number(after, "bytes") == end && text(after, "sha256").equals(SchemaPin.sha256(Arrays.copyOf(transcript, (int) end))), "Codex after-prefix differs from region end");
        Jv declared = array(m, "turns").get(0); require(Jv.write(declared.member("native_prefix_after")).equals(Jv.write(after)), "Codex turn after-prefix differs from thread");
        Jv before = declared.member("native_prefix_before");
        if (index == 0) { equal(before, "state", "ABSENT"); equal(before, "observation", "before-first-dispatch"); }
        else require(Jv.write(before).equals(Jv.write(turns.get(index - 1).member("native_prefix_after"))), "Codex before-prefix differs from previous after-prefix");
    }

    private static void usage(Jv m, List<Jv> turns, int index) {
        Jv declared = array(m, "turns").get(0), turn = turns.get(index);
        for (String key : List.of("wire_usage", "turn_token_usage", "thread_token_usage", "total_token_usage")) require(Jv.write(declared.member(key)).equals(Jv.write(turn.member(key))), "Codex usage differs from thread: " + key);
        Jv previous = index == 0 ? null : turns.get(index - 1).member("thread_token_usage");
        for (String key : USAGE) require(number(turn.member("thread_token_usage"), key) - (previous == null ? 0 : number(previous, key)) == number(turn.member("turn_token_usage"), key), "Codex cumulative usage arithmetic fails: " + key);
        // codex-cli 0.166.0 emits the thread total on turn.completed (native probe 2026-11-04): a resumed turn's wire usage is the cumulative figure.
        require(previous == null || "cumulative".equals(TerminalCodexNative.scope(turn)), "Codex resumed turn wire usage is not the thread cumulative figure");
    }
}
