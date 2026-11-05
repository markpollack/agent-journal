package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.CodexAcquisitionReading;
import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration;
import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration.Rejection;
import io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration.Verdict;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration.ORDINARY_PROFILE;
import static io.github.markpollack.journal.tck.ocsf.CodexRetainedGeneration.PROFILE;
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic Codex retained threads: every turn qualifies under its profile, the frozen two-turn profile refuses a third turn,
 * the one-shot qualifier refuses resumed turns, and rehashed semantic mutations are refused through the ten-area rejection classes. */
class CodexRetainedAcquisitionTest {
    static final Path FIXTURE = Path.of("src/test/resources/ocsf/syn-codex-retained-0.166.0");
    static final Path THREE = Path.of("src/test/resources/ocsf/syn-codex-retained-3turn-0.166.0");
    /** The pre-probe emulated shape (af88650 fixture bytes): per-turn resumed stdout usage and a bare thread_settings_applied; frozen /1 evidence. */
    static final Path EMULATED = Path.of("src/test/resources/ocsf/syn-codex-retained-emulated-0.166.0");
    static final String N = "a94e0250-c7a5-4bc3-adef-fcbef96582ac";
    static final String TURN_0 = "b8d56e6a-5cfa-499c-a6e2-a33f7ebd378d", TURN_1 = "393b1549-a4cc-4cd7-aee8-1fc6d96cedcc";

    /** Working copy whose thread manifest, per-turn manifests and originals can be edited and rehashed. */
    static final class Copy {
        final Path dir; final Json.JObject thread; final Json.JObject[] perTurn; byte[] transcript; private final byte[] originalTranscript; final int count;
        Copy(Path dir, Path fixture) throws Exception {
            this.dir = dir;
            try (Stream<Path> files = Files.walk(fixture)) { for (Path p : files.toList()) { Path target = dir.resolve(fixture.relativize(p)); if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target); } }
            thread = (Json.JObject) NeutralJson.readDocument(dir.resolve("codex-thread.json")); transcript = Files.readAllBytes(dir.resolve("transcript.bin")); originalTranscript = transcript.clone();
            count = thread.member("turns").elements().size(); perTurn = new Json.JObject[count];
            for (int i = 0; i < count; i++) perTurn[i] = (Json.JObject) NeutralJson.readDocument(dir.resolve("turn-" + i + "/codex-acquisition.json"));
        }
        Json.JObject turn(int i) { return (Json.JObject) thread.member("turns").elements().get(i); }
        Json.JObject perTurnTurn(int i) { return (Json.JObject) perTurn[i].member("turns").elements().get(0); }
        String execution(int i) { return turn(i).member("execution_id").text(); }
        static void put(Json o, String k, Json v) { ((Json.JObject) o).members().put(k, v); }
        static Json str(String s) { return new Json.JString(s); }
        static Json num(long n) { return new Json.JNumber(Long.toString(n)); }
        Json usage(long in, long cached, long write, long out, long reasoning) { return NeutralJson.parse("{\"input_tokens\":" + in + ",\"cached_input_tokens\":" + cached + ",\"cache_write_input_tokens\":" + write + ",\"output_tokens\":" + out + ",\"reasoning_output_tokens\":" + reasoning + ",\"total_tokens\":" + (in + out) + "}"); }
        /** Sets a turn's stdout usage to the given figure in the stdout original AND the thread/per-turn declarations, so the native
         * witness agrees with the declaration and only the rule under test can refuse (independent review finding 4, 2026-11-05). */
        void wire(int i, Json usage) throws Exception {
            Json w = NeutralJson.parse("{}"); for (String k : CodexRetainedGeneration.USAGE) put(w, k, usage.member(k)); put(turn(i), "wire_usage", w); put(perTurnTurn(i), "wire_usage", w);
            Path stdout = dir.resolve("turn-" + i + "/stdout.bin"); String text = Files.readString(stdout);
            String replacement = "\"usage\": {" + String.join(", ", CodexRetainedGeneration.USAGE.stream().map(k -> "\"" + k + "\": " + usage.member(k).text()).toList()) + "}";
            Files.writeString(stdout, text.replaceAll("\"usage\": \\{[^}]*\\}", java.util.regex.Matcher.quoteReplacement(replacement)));
        }
        /** Restates a turn's native thread total in the rollout (token_usage_record and token_count), its stdout and every declaration, so the
         * native witnesses stay consistent and only the cumulative arithmetic can refuse. */
        void threadTotal(int i, Json from, Json to) throws Exception {
            String old = python(from), neu = python(to);
            transcript = swapAll(transcript, old, neu);
            put(turn(i), "thread_token_usage", to); put(turn(i), "total_token_usage", to); put(turn(i), "cumulative_usage", to);
            put(perTurnTurn(i), "thread_token_usage", to); put(perTurnTurn(i), "total_token_usage", to); put(perTurnTurn(i), "cumulative_usage", to);
            wire(i, to);
        }
        /** The rollout lines are Python json.dumps(sort_keys=True) text: keys sorted, ", " and ": " separators. */
        static String python(Json usage) {
            java.util.TreeMap<String, Json> sorted = new java.util.TreeMap<>(((Json.JObject) usage).members());
            return "{" + String.join(", ", sorted.entrySet().stream().map(e -> "\"" + e.getKey() + "\": " + e.getValue().text()).toList()) + "}";
        }
        /** Rewrites the transcript and restates every hash/extent that an honest producer would restate. */
        void rehash() throws Exception {
            Files.write(dir.resolve("transcript.bin"), transcript);
            Json t = thread.member("transcript"); put(t, "bytes", num(transcript.length)); put(t, "sha256", str(NativeArtifact.sha256(transcript)));
            put(t.member("last_sealed_prefix"), "bytes", num(transcript.length)); put(t.member("last_sealed_prefix"), "sha256", str(NativeArtifact.sha256(transcript)));
            boolean changed = !Arrays.equals(transcript, originalTranscript);
            for (int i = 0; i < count; i++) {
                for (Json locator : locators(turn(i))) { int s = (int) n(locator, "start"), e = (int) Math.min(n(locator, "end"), transcript.length); if (s < e) put(locator, "sha256", str(NativeArtifact.sha256(Arrays.copyOfRange(transcript, s, e)))); }
                if (changed) { // an honest producer restates its sealed prefixes and snapshots after the bytes it observed changed
                    List<Json> records = turn(i).member("native_records").member("records").elements();
                    int end = (int) Math.min(n(records.get(records.size() - 1), "end"), transcript.length); byte[] prefix = Arrays.copyOf(transcript, end);
                    Json after = NeutralJson.parse("{\"bytes\":" + end + ",\"sha256\":\"" + NativeArtifact.sha256(prefix) + "\"}");
                    put(turn(i), "native_prefix_after", after); put(perTurnTurn(i), "native_prefix_after", after);
                    if (i > 0) put(perTurnTurn(i), "native_prefix_before", turn(i - 1).member("native_prefix_after"));
                    Files.write(dir.resolve("turn-" + i + "/native.bin"), prefix);
                    for (Json s : perTurn[i].member("sources").elements()) if ("transcript".equals(s.member("role").text())) { put(s, "bytes", num(prefix.length)); put(s, "observed", num(prefix.length)); put(s, "sha256", str(NativeArtifact.sha256(prefix))); }
                }
                for (Json s : turn(i).member("sources").elements()) { byte[] b = Files.readAllBytes(dir.resolve(s.member("path").text())); put(s, "bytes", num(b.length)); put(s, "observed", num(b.length)); put(s, "sha256", str(NativeArtifact.sha256(b))); }
                for (Json s : perTurn[i].member("sources").elements()) { String role = s.member("role").text(); if ("transcript".equals(role)) continue; // restated above when the rollout changed
                    byte[] b = Files.readAllBytes(dir.resolve("turn-" + i + "/" + s.member("path").text())); put(s, "bytes", num(b.length)); put(s, "observed", num(b.length)); put(s, "sha256", str(NativeArtifact.sha256(b)));
                    if ("stream".equals(role)) { long end = b.length; put(perTurnTurn(i).member("end_offsets"), "STDOUT", num(end)); } }
                Files.writeString(dir.resolve("turn-" + i + "/codex-acquisition.json"), NeutralJson.write(perTurn[i]));
            }
            Files.writeString(dir.resolve("codex-thread.json"), NeutralJson.write(thread));
        }
        private List<Json> locators(Json turn) { Json r = turn.member("native_records"); return Stream.concat(r.member("shared").elements().stream(), r.member("records").elements().stream()).toList(); }
        static long n(Json o, String k) { return ((Json.JNumber) o.member(k)).value().longValueExact(); }
        CodexRetainedGeneration.RetainedThread validate(String profile) throws Exception { return CodexRetainedGeneration.validate(NeutralJson.readDocument(dir.resolve("codex-thread.json")), dir, profile); }
    }

    /** Qualifies turn {@code i} under {@code profile} and checks projection, preserved cumulative usage, originals and independent re-reads. */
    static void qualified(Copy copy, CodexRetainedGeneration.RetainedThread thread, int i, String profile, long turnInput, long threadInput) throws Exception {
        String execution = thread.turns().get(i).executionId(); Path out = copy.dir.resolve("q-" + profile.replace('/', '-') + "-" + i);
        CodexRetainedAcquisition.qualify(copy.dir.resolve("codex-thread.json"), execution, out, profile);
        Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
        assertThat(acquisition.member("retained_profile").text()).isEqualTo(profile);
        assertThat(acquisition.member("wire_usage_scope").text()).as("turn %d stdout usage scope", i).isEqualTo(i == 0 ? "per-turn" : "cumulative");
        String projection = Files.readString(out.resolve("turn/transcript.jsonl"));
        assertThat(projection.lines().count()).isEqualTo(i == 0 ? 8 : 9);
        assertThat(projection.lines().filter(l -> l.contains("session_meta")).count()).isEqualTo(1);
        assertThat(projection).contains(thread.turns().get(i).nativeTurnId());
        for (CodexRetainedGeneration.Turn other : thread.turns()) if (other.index() != i) assertThat(projection).doesNotContain(other.nativeTurnId());
        if (i == 0) assertThat(Files.readAllBytes(out.resolve("turn/transcript.jsonl"))).isEqualTo(Files.readAllBytes(copy.dir.resolve("turn-0/native.bin")));
        Json turn = acquisition.member("turns").elements().get(0);
        assertThat(turn.member("thread_token_usage").member("input_tokens").text()).isEqualTo(Long.toString(threadInput));
        assertThat(turn.member("turn_token_usage").member("input_tokens").text()).isEqualTo(Long.toString(turnInput));
        CodexRetainedAcquisition.validate(acquisition, out); CodexAcquisitionReading.validate(out.resolve("acquisition.json"));
        assertThat(out.resolve("generation/codex-thread.original.json")).hasSameBinaryContentAs(copy.dir.resolve("codex-thread.json"));
        assertThat(Json.sameValue(NeutralJson.readDocument(out.resolve("generation/codex-thread.json")), NeutralJson.readDocument(copy.dir.resolve("codex-thread.json")))).isTrue(); // already relative
        assertThat(out.resolve("generation/turn-" + i + "/native.bin")).hasSameBinaryContentAs(copy.dir.resolve("turn-" + i + "/native.bin"));
    }

    @Test void bothTurnsQualifyUnderEitherProfileWithProjectionsOriginalsAndPreservedCumulativeUsage(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir, FIXTURE);
        for (String profile : List.of(PROFILE, ORDINARY_PROFILE)) {
            CodexRetainedGeneration.RetainedThread thread = copy.validate(profile);
            assertThat(thread.turns()).hasSize(2); assertThat(thread.session()).isEqualTo(N);
            qualified(copy, thread, 0, profile, 11, 11); qualified(copy, thread, 1, profile, 11, 22);
            assertThatThrownBy(() -> thread.turn("missing")).isInstanceOf(Rejection.class);
        }
        // The one-shot qualifier refuses the resumed turn and never creates output; the fresh first turn is a legitimate one-shot.
        Path oneShot = dir.resolve("one-shot-turn-1");
        assertThatThrownBy(() -> CodexAcquisition.qualify(dir.resolve("turn-1/codex-acquisition.json"), oneShot)).isInstanceOf(IllegalArgumentException.class);
        assertThat(oneShot).doesNotExist();
        assertThatThrownBy(() -> copy.validate("client-terminal-codex-retained-0.166.0/9")).isInstanceOf(Rejection.class).hasMessageContaining("unsupported retained profile");
    }

    /** Owner continuation C2 (2026-11-05): the frozen /1 profile accepts the evidence it accepted before the native probe, exactly as before;
     * the cumulative resumed-stdout requirement belongs to the ordinary /2 profile only. */
    @Test void frozenProfileStillAcceptsThePreProbeEmulatedThreadAndTheOrdinaryProfileRefusesIt(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir, EMULATED);
        CodexRetainedGeneration.RetainedThread thread = copy.validate(PROFILE); assertThat(thread.turns()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            String execution = thread.turns().get(i).executionId(); Path out = dir.resolve("frozen-" + i);
            CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), execution, out); // the original signature: frozen /1 by default
            Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
            assertThat(acquisition.member("retained_profile").text()).isEqualTo(PROFILE);
            assertThat(acquisition.member("wire_usage_scope").text()).as("the emulated resumed turn reports a per-turn stdout figure").isEqualTo("per-turn");
            CodexRetainedAcquisition.validate(acquisition, out); CodexAcquisitionReading.validate(out.resolve("acquisition.json"));
        }
        assertThatThrownBy(() -> copy.validate(ORDINARY_PROFILE)).isInstanceOf(Rejection.class).hasMessageContaining("resumed turn wire usage is not the thread cumulative figure");
        Path ordinary = dir.resolve("ordinary"); String resumed = copy.execution(1);
        assertThatThrownBy(() -> CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), resumed, ordinary, ORDINARY_PROFILE)).isInstanceOf(Rejection.class);
        assertThat(ordinary).doesNotExist();
    }

    @Test void threeTurnThreadQualifiesOnlyUnderTheOrdinaryProfileWithContinuityAndArithmeticAtEveryTurn(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir, THREE);
        assertThatThrownBy(() -> copy.validate(PROFILE)).isInstanceOf(Rejection.class).hasMessageContaining("exactly two turns")
                .extracting(e -> ((Rejection) e).verdict()).isEqualTo(Verdict.REFUSED);
        Path frozen = dir.resolve("frozen"); String last = copy.execution(2);
        assertThatThrownBy(() -> CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), last, frozen, PROFILE)).isInstanceOf(Rejection.class);
        assertThat(frozen).doesNotExist();
        CodexRetainedGeneration.RetainedThread thread = copy.validate(ORDINARY_PROFILE);
        assertThat(thread.turns()).hasSize(3);
        assertThat(thread.turns().stream().map(CodexRetainedGeneration.Turn::nativeTurnId).distinct()).hasSize(3);
        qualified(copy, thread, 0, ORDINARY_PROFILE, 11, 11); qualified(copy, thread, 1, ORDINARY_PROFILE, 11, 22); qualified(copy, thread, 2, ORDINARY_PROFILE, 13, 35);
        // Prefix continuity: each turn's region starts where the previous after-prefix ended; the last after-prefix is the final transcript.
        for (int i = 1; i < 3; i++) assertThat(Copy.n(copy.perTurnTurn(i).member("native_prefix_before"), "bytes")).isEqualTo(thread.turns().get(i).regionStart()).isEqualTo(thread.turns().get(i - 1).regionEnd());
        assertThat(thread.turns().get(2).regionEnd()).isEqualTo(thread.transcript().length);
        for (int i = 1; i < 3; i++) { Path oneShot = dir.resolve("one-shot-" + i); int turn = i;
            assertThatThrownBy(() -> CodexAcquisition.qualify(dir.resolve("turn-" + turn + "/codex-acquisition.json"), oneShot)).isInstanceOf(IllegalArgumentException.class); assertThat(oneShot).doesNotExist(); }
    }

    record Control(String name, Verdict verdict, Consumer<Copy> mutation) { }
    static Control c(String name, Verdict verdict, Consumer<Copy> mutation) { return new Control(name, verdict, mutation); }

    static List<Control> controls() {
        return List.of(
            c("foreign turn: region task_started carries the other turn's id", Verdict.REFUSED, k -> k.transcript = swap(k.transcript, "\"turn_id\": \"" + TURN_1 + "\"", "\"turn_id\": \"" + TURN_0 + "\"")),
            c("foreign thread: follow-up resumes another thread", Verdict.REFUSED, k -> { Copy.put(k.turn(1), "resume_thread_id", Copy.str("00000000-0000-4000-8000-000000000000")); }),
            c("cross-association: turn 1 originals replaced by turn 0's process", Verdict.REFUSED, k -> { for (Json s : k.turn(1).member("sources").elements()) Copy.put(s, "path", Copy.str(s.member("path").text().replace("turn-1/", "turn-0/"))); }),
            c("preexisting/ambiguous fresh source: first turn claims a resumed thread", Verdict.REFUSED, k -> Copy.put(k.turn(0), "resume_thread_id", Copy.str(N))),
            c("missing emitted identity on the first turn", Verdict.INCOMPLETE, k -> Copy.put(k.turn(0), "identity_source", Copy.str("resume-argv"))),
            c("changed original: prompt text altered after capture", Verdict.REFUSED, k -> k.transcript = swap(k.transcript, "\"text\": \"second\"", "\"text\": \"SECOND\"")),
            c("truncated original: final transcript shorter than its last record", Verdict.REFUSED, k -> k.transcript = Arrays.copyOf(k.transcript, k.transcript.length - 40)),
            c("invalid association: per-turn manifest from another connection", Verdict.REFUSED, k -> Copy.put(k.perTurn[1].member("process"), "connection_uid", Copy.str("other-connection"))),
            c("double counting: native thread total adds the turn twice (witnesses consistent; arithmetic refuses)", Verdict.REFUSED, io(k -> k.threadTotal(1, k.usage(22, 6, 0, 8, 0), k.usage(33, 9, 0, 12, 0)))),
            c("double counting declared only: declaration contradicts the native record", Verdict.REFUSED, k -> { Copy.put(k.turn(1), "thread_token_usage", k.usage(33, 9, 0, 12, 0)); Copy.put(k.turn(1), "total_token_usage", k.usage(33, 9, 0, 12, 0)); }),
            c("prefix continuity: follow-up before-prefix differs from first after-prefix", Verdict.REFUSED, k -> Copy.put(k.perTurnTurn(1).member("native_prefix_before"), "bytes", Copy.num(100))),
            c("partition gap: a region record dropped", Verdict.REFUSED, k -> k.turn(1).member("native_records").member("records").elements().remove(2)),
            c("session_meta inside a region / shared differs", Verdict.REFUSED, k -> Copy.put(k.turn(1).member("native_records").member("shared").elements().get(0), "end", Copy.num(0))),
            c("wire usage neither per-turn nor cumulative (stdout and declaration agree)", Verdict.REFUSED, io(k -> k.wire(1, k.usage(99, 3, 0, 4, 0)))),
            c("declared wire differs from stdout on the resumed turn", Verdict.REFUSED, k -> { Json w = NeutralJson.parse("{}"); for (String key : CodexRetainedGeneration.USAGE) Copy.put(w, key, k.usage(11, 3, 0, 4, 0).member(key)); Copy.put(k.turn(1), "wire_usage", w); Copy.put(k.perTurnTurn(1), "wire_usage", w); }),
            c("unsealed follow-up turn", Verdict.INCOMPLETE, k -> Copy.put(k.turn(1), "state", Copy.str("UNSEALED"))),
            c("final transcript not equal to last sealed prefix", Verdict.INCOMPLETE, k -> Copy.put(k.thread.member("transcript"), "final_equals_last_sealed_prefix", new Json.JBool(false))),
            c("closure not observed: stdout EOF missing", Verdict.INCOMPLETE, k -> Copy.put(k.turn(1), "stdout_natural_eof", new Json.JBool(false))),
            c("per-turn manifest missing", Verdict.INCOMPLETE, k -> Copy.put(k.turn(1), "per_turn_manifest", Json.JNull.INSTANCE)),
            c("forbidden history option", Verdict.REFUSED, k -> ((Json.JArray) k.turn(1).member("argv")).elements().add(1, Copy.str("--last"))));
    }

    /** Third-turn controls: the same classes must bite at a turn the frozen profile never reads. */
    static List<Control> thirdTurnControls() {
        return List.of(
            c("foreign turn: third region carries the second turn's id", Verdict.REFUSED, k -> k.transcript = swap(k.transcript, "\"turn_id\": \"" + k.turn(2).member("native_turn_uid").text() + "\"", "\"turn_id\": \"" + TURN_1 + "\"")),
            c("double counting at the third turn: native thread total adds the turn twice (witnesses consistent; arithmetic refuses)", Verdict.REFUSED, io(k -> k.threadTotal(2, k.usage(35, 11, 0, 14, 0), k.usage(48, 16, 0, 20, 0)))),
            c("prefix continuity broken before the third turn", Verdict.REFUSED, k -> Copy.put(k.perTurnTurn(2).member("native_prefix_before"), "bytes", Copy.num(3482))),
            c("partition gap inside the third region", Verdict.REFUSED, k -> k.turn(2).member("native_records").member("records").elements().remove(4)),
            c("cross-association: third turn originals replaced by the second turn's process", Verdict.REFUSED, k -> { for (Json s : k.turn(2).member("sources").elements()) Copy.put(s, "path", Copy.str(s.member("path").text().replace("turn-2/", "turn-1/"))); }),
            c("third turn resumes another thread", Verdict.REFUSED, k -> Copy.put(k.turn(2), "resume_thread_id", Copy.str("00000000-0000-4000-8000-000000000000"))),
            c("third turn wire usage per-turn in stdout and declaration (ordinary profile requires the thread total)", Verdict.REFUSED, io(k -> k.wire(2, k.usage(13, 5, 0, 6, 0)))),
            c("third turn unsealed", Verdict.INCOMPLETE, k -> Copy.put(k.turn(2), "state", Copy.str("UNSEALED"))));
    }

    static byte[] swap(byte[] bytes, String from, String to) { String s = new String(bytes, StandardCharsets.UTF_8); assertThat(s).contains(from); return s.replace(from, to).getBytes(StandardCharsets.UTF_8); }
    static byte[] swapAll(byte[] bytes, String from, String to) { return swap(bytes, from, to); }
    static Consumer<Copy> io(ThrowingMutation m) { return c -> { try { m.accept(c); } catch (Exception e) { throw new RuntimeException(e); } }; }
    interface ThrowingMutation { void accept(Copy c) throws Exception; }

    static void refused(Path root, Path fixture, String profile, int turn, List<Control> controls) throws Exception {
        int index = 0;
        for (Control control : controls) {
            Path dir = Files.createDirectories(root.resolve("c" + index++)); Copy copy = new Copy(dir, fixture);
            control.mutation().accept(copy); copy.rehash();
            assertThatThrownBy(() -> copy.validate(profile)).as(control.name()).isInstanceOf(Rejection.class).extracting(e -> ((Rejection) e).verdict()).isEqualTo(control.verdict());
            Path out = dir.resolve("refused"); String execution = copy.execution(turn);
            assertThatThrownBy(() -> CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), execution, out, profile)).as(control.name()).isInstanceOf(Rejection.class);
            assertThat(out).as(control.name()).doesNotExist();
        }
        assertThat(index).isEqualTo(controls.size());
    }

    @Test void rehashedSemanticMutationsAreRefusedOrIncompleteBeforeOutput(@TempDir Path root) throws Exception {
        refused(root.resolve("two"), FIXTURE, PROFILE, 1, controls()); assertThat(controls()).hasSize(20);
        refused(root.resolve("three"), THREE, ORDINARY_PROFILE, 2, thirdTurnControls()); assertThat(thirdTurnControls()).hasSize(8);
    }

    @Test void qualifiedOutputAlteredAfterQualificationIsRejectedOnIndependentRead(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir, FIXTURE); String execution = copy.execution(1); Path out = dir.resolve("q");
        CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), execution, out, ORDINARY_PROFILE);
        Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
        Path projection = out.resolve("turn/transcript.jsonl"); String original = Files.readString(projection);
        Files.writeString(projection, original.replace("\"text\": \"second\"", "\"text\": \"SECOND\""));
        assertThatThrownBy(() -> CodexRetainedAcquisition.validate(acquisition, out)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(projection, original); CodexRetainedAcquisition.validate(acquisition, out);
        Json.JObject forged = (Json.JObject) NeutralJson.parse(NeutralJson.write(acquisition)); Copy.put(forged, "wire_usage_scope", Copy.str("per-turn"));
        assertThatThrownBy(() -> CodexRetainedAcquisition.validate(forged, out)).isInstanceOf(IllegalArgumentException.class);
        Json.JObject relabelled = (Json.JObject) NeutralJson.parse(NeutralJson.write(acquisition)); Copy.put(relabelled, "retained_profile", Copy.str("client-terminal-codex-retained-0.166.0/9"));
        assertThatThrownBy(() -> CodexRetainedAcquisition.validate(relabelled, out)).as("unknown profile stamped after qualification").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported retained profile");
    }

    /** The Client lays per-turn directories beside the thread directory with absolute paths; files outside that capture root are refused. */
    @Test void absolutePerTurnPathsAreAdmittedBesideTheThreadDirectoryAndRefusedOutsideTheCaptureRoot(@TempDir Path root) throws Exception {
        Path captures = Files.createDirectory(root.resolve("captures")); Path threadDir = Files.createDirectory(captures.resolve("codex-thread-1"));
        Copy copy = new Copy(Files.createDirectory(root.resolve("staging")), FIXTURE);
        for (int i = 0; i < 2; i++) { Path target = captures.resolve("codex-turn-" + i); Files.createDirectory(target);
            for (String name : List.of("codex-acquisition.json", "stdin.bin", "stdout.bin", "stderr.bin", "native.bin")) Files.copy(copy.dir.resolve("turn-" + i + "/" + name), target.resolve(name));
            Json t = copy.turn(i); Copy.put(t, "per_turn_directory", Copy.str(target.toString())); Copy.put(t, "per_turn_manifest", Copy.str(target.resolve("codex-acquisition.json").toString()));
            for (Json s : t.member("sources").elements()) Copy.put(s, "path", Copy.str(target.resolve(Path.of(s.member("path").text()).getFileName()).toString())); }
        Files.copy(copy.dir.resolve("transcript.bin"), threadDir.resolve("transcript.bin"));
        Files.writeString(threadDir.resolve("codex-thread.json"), NeutralJson.write(copy.thread));
        CodexRetainedGeneration.RetainedThread thread = CodexRetainedGeneration.validate(NeutralJson.readDocument(threadDir.resolve("codex-thread.json")), threadDir, ORDINARY_PROFILE);
        Path out = root.resolve("qualified"); CodexRetainedAcquisition.qualify(threadDir.resolve("codex-thread.json"), thread.turns().get(1).executionId(), out, ORDINARY_PROFILE);
        CodexRetainedAcquisition.validate(NeutralJson.readDocument(out.resolve("acquisition.json")), out);
        assertThat(Files.readString(out.resolve("generation/codex-thread.json"))).contains("turn-1/codex-acquisition.json").doesNotContain(captures.toString());
        Path outside = Files.createDirectory(root.resolve("outside")); Files.copy(copy.dir.resolve("turn-1/stdin.bin"), outside.resolve("stdin.bin"));
        // Independent review finding 2 (2026-11-05): a per-turn manifest source naming an outside file with identical bytes must be refused too.
        Path perTurn = captures.resolve("codex-turn-1/codex-acquisition.json"); Json.JObject perTurnManifest = (Json.JObject) NeutralJson.readDocument(perTurn);
        for (Json s : perTurnManifest.member("sources").elements()) if ("stdin".equals(s.member("role").text())) Copy.put(s, "path", Copy.str(outside.resolve("stdin.bin").toString()));
        Files.writeString(perTurn, NeutralJson.write(perTurnManifest));
        assertThatThrownBy(() -> CodexRetainedGeneration.validate(NeutralJson.readDocument(threadDir.resolve("codex-thread.json")), threadDir, ORDINARY_PROFILE))
                .as("per-turn manifest source outside the capture root").isInstanceOf(Rejection.class).hasMessageContaining("escapes");
        Files.copy(copy.dir.resolve("turn-1/codex-acquisition.json"), perTurn, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (Json s : copy.turn(1).member("sources").elements()) if ("STDIN".equals(s.member("role").text())) Copy.put(s, "path", Copy.str(outside.resolve("stdin.bin").toString()));
        Files.writeString(threadDir.resolve("codex-thread.json"), NeutralJson.write(copy.thread));
        assertThatThrownBy(() -> CodexRetainedGeneration.validate(NeutralJson.readDocument(threadDir.resolve("codex-thread.json")), threadDir, ORDINARY_PROFILE))
                .isInstanceOf(Rejection.class).hasMessageContaining("escapes the capture root");
    }
}
