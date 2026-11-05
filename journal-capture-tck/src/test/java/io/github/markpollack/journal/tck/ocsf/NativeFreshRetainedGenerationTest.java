package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.Rejection;
import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.Verdict;
import io.github.markpollack.journal.tck.ocsf.FreshRetainedGenerationTest.Copy;
import io.github.markpollack.journal.tck.ocsf.inventory.FreshRetainedClaudeAcquisition;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.NATIVE_PROFILE;
import static io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.ORDINARY_PROFILE;
import static io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.PROFILE;
import static org.assertj.core.api.Assertions.*;

/** Native text-turn model of the fresh profile {@code /3}: the DERIVED-REDACTED native-shaped generation (22 and 15 native records per turn,
 * attachment chains on both sides of each turn, in-flight stream messages, a plain transcript tail) is the positive; every control is a
 * mutated copy, relaid out and rehashed so the verdict comes from the mutated semantics alone. With {@code -Djournal.fresh3.specimen=<dir>}
 * the same controls run on copies of the frozen native specimen itself (its depth-limited scan count zeroed on the copies, so each control
 * isolates its own mutation; the untouched specimen's own verdict is reported separately by the readback). */
class NativeFreshRetainedGenerationTest {
    static final Path FIXTURE = Path.of("src/test/resources/retained-fresh-generation-native");
    static Path root() { String specimen = System.getProperty("journal.fresh3.specimen"); return specimen == null ? FIXTURE : Path.of(specimen); }
    static boolean specimen() { return System.getProperty("journal.fresh3.specimen") != null; }

    /** Rows of one JSONL original, mutable; {@link #relayout} rewrites the bytes and remaps every offset, prefix and observation that named them. */
    static final class Rows {
        final Copy copy; final String source; final List<Json> rows = new ArrayList<>(); final long[] old;
        Rows(Copy copy, String source) {
            this.copy = copy; this.source = source; byte[] bytes = copy.sources.get(source);
            for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) rows.add(NeutralJson.parse(line));
            old = boundaries(bytes);
        }
        static long[] boundaries(byte[] bytes) {
            List<Long> ends = new ArrayList<>(); ends.add(0L);
            for (int i = 0; i < bytes.length; i++) if (bytes[i] == '\n') ends.add(i + 1L);
            return ends.stream().mapToLong(Long::longValue).toArray();
        }
        Json.JObject row(int index) { return (Json.JObject) rows.get(index); }
        /** The row a manifest locator names, by its original offsets. */
        Json.JObject at(Json locator) { return row(index(((Json.JNumber) locator.member("start")).value().longValueExact())); }
        int index(long start) { for (int i = 0; i < old.length; i++) if (old[i] == start) return i; throw new IllegalArgumentException("not a record start: " + start); }
        List<Json.JObject> ofType(String type) { List<Json.JObject> out = new ArrayList<>(); for (Json r : rows) if (type.equals(r.member("type").text())) out.add((Json.JObject) r); return out; }
        Json.JObject byUuid(String uuid) { for (Json r : rows) if (r.member("uuid") != null && uuid.equals(r.member("uuid").text())) return (Json.JObject) r; throw new IllegalArgumentException(uuid); }
        void relayout() {
            StringBuilder sb = new StringBuilder(); for (Json r : rows) sb.append(NeutralJson.write(r)).append('\n');
            byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8); long[] fresh = boundaries(bytes);
            Map<Long, Long> map = new HashMap<>(); for (int i = 0; i < old.length; i++) map.put(old[i], fresh[i]);
            copy.sources.put(source, bytes);
            remap(copy.m, map, bytes);
            for (Json o : copy.observations) {
                String kind = o.member("kind").text();
                if (source.equals("TRANSCRIPT") && (kind.equals("prefix") || kind.equals("final"))) {
                    long n = map.get(((Json.JNumber) o.member("bytes")).value().longValueExact());
                    Copy.put(o, "bytes", Copy.num(n)); Copy.put(o, "sha256", Copy.str(NativeArtifact.sha256(Arrays.copyOf(bytes, (int) n))));
                }
                if (kind.equals("dispatch")) {
                    String key = source.equals("STDIN") ? "stdinOffset" : source.equals("STDOUT") ? "stdoutOffset" : null;
                    if (key != null) Copy.put(o, key, Copy.num(map.get(((Json.JNumber) o.member(key)).value().longValueExact())));
                }
            }
        }
        private void remap(Json node, Map<Long, Long> map, byte[] bytes) {
            if (node instanceof Json.JObject o) {
                if (o.member("source") != null && source.equals(o.member("source").text()) && o.member("start") != null) {
                    Copy.put(o, "start", Copy.num(map.get(((Json.JNumber) o.member("start")).value().longValueExact())));
                    Copy.put(o, "end", Copy.num(map.get(((Json.JNumber) o.member("end")).value().longValueExact())));
                }
                else if (source.equals("TRANSCRIPT") && o.member("bytes") != null && o.member("sha256") != null && o.members().size() == 2) {
                    long n = map.get(((Json.JNumber) o.member("bytes")).value().longValueExact());
                    Copy.put(o, "bytes", Copy.num(n)); Copy.put(o, "sha256", Copy.str(NativeArtifact.sha256(Arrays.copyOf(bytes, (int) n))));
                }
                else if ((o.member("STDIN") != null && o.member("STDOUT") != null) && !source.equals("TRANSCRIPT")) {
                    Copy.put(o, source, Copy.num(map.get(((Json.JNumber) o.member(source)).value().longValueExact())));
                }
                for (Json v : new ArrayList<>(o.members().values())) remap(v, map, bytes);
            }
            else if (node instanceof Json.JArray a) for (Json v : a.elements()) remap(v, map, bytes);
        }
    }

    /** A rehashable copy of the native-shaped generation; on the specimen the depth-limited scan count is zeroed first (labelled copy). */
    static Copy copy(Path dir) throws Exception {
        Copy c = new Copy(dir, root());
        if (specimen()) for (Json o : c.observations) if (o.member("skipped") != null) Copy.put(o, "skipped", Copy.num(0));
        return c;
    }
    static Json.JObject turn(Copy c, int i) { return c.turn(i); }
    static Json.JObject nativeRecord(Rows t, Copy c, int turn, int index) { return t.at(turn(c, turn).member("nativeRecords").elements().get(index)); }
    static Json.JObject user(Rows t, Copy c, int turn) { for (Json l : turn(c, turn).member("nativeRecords").elements()) if ("user".equals(t.at(l).member("type").text())) return t.at(l); throw new AssertionError(); }
    static Json.JObject assistant(Rows t, Copy c, int turn) { for (Json l : turn(c, turn).member("nativeRecords").elements()) if ("assistant".equals(t.at(l).member("type").text())) return t.at(l); throw new AssertionError(); }
    /** Attachments of a turn in offset order; the last one is the trailing attachment parented to the assistant. */
    static List<Json.JObject> attachments(Rows t, Copy c, int turn) { List<Json.JObject> out = new ArrayList<>(); for (Json l : turn(c, turn).member("nativeRecords").elements()) if ("attachment".equals(t.at(l).member("type").text())) out.add(t.at(l)); return out; }
    static Json.JObject plain(Rows t, Copy c, int turn, String type) { for (Json l : turn(c, turn).member("nativeRecords").elements()) if (type.equals(t.at(l).member("type").text())) return t.at(l); throw new AssertionError(type); }
    static String uuid(Json o) { return o.member("uuid").text(); }
    static Json.JObject lastObservation(Copy c, String kind) { Json.JObject found = null; for (Json o : c.observations) if (kind.equals(o.member("kind").text())) found = (Json.JObject) o; return found; }

    record Control(String name, String reason, Consumer<Copy> mutation) { }
    static Control control(String name, String reason, Consumer<Copy> mutation) { return new Control(name, reason, mutation); }

    /** Each control names the rule it exercises; the expected verdict is REFUSED unless the name says otherwise. */
    static List<Control> controls() {
        return List.of(
            control("O1 unknown native record class", "unknown native record class", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(plain(t, c, 0, "queue-operation"), "type", Copy.str("queue-op")); t.relayout(); }),
            control("O2 assistant chain skips its user (first attachment orphaned)", "native response does not descend from the native prompt", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(attachments(t, c, 0).get(0), "parentUuid", Json.JNull.INSTANCE); t.relayout(); }),
            control("O3 parent link into another turn", "native parent outside the turn window", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(attachments(t, c, 1).get(0), "parentUuid", Copy.str(uuid(assistant(t, c, 0)))); t.relayout(); }),
            control("O4 ancestry cycle", "native ancestry cycle", c -> { Rows t = new Rows(c, "TRANSCRIPT"); List<Json.JObject> a = attachments(t, c, 0); Copy.put(a.get(0), "parentUuid", Copy.str(uuid(a.get(1)))); t.relayout(); }),
            control("O5a second user in one turn", "text profile admits one native prompt per turn", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(attachments(t, c, 0).get(2), "type", Copy.str("user")); t.relayout(); }),
            control("O5b second assistant in one turn", "text profile admits one native response per turn", c -> { Rows t = new Rows(c, "TRANSCRIPT"); List<Json.JObject> a = attachments(t, c, 0); Copy.put(a.get(a.size() - 1), "type", Copy.str("assistant")); t.relayout(); }),
            control("O6 intermediate record outside the window (moved to the next turn's membership)", "native records do not tile the prefix window", c -> { List<Json> first = turn(c, 0).member("nativeRecords").elements(); Json moved = first.remove(3); turn(c, 1).member("nativeRecords").elements().add(0, moved); }),
            control("O7 turn-2 user not descending from turn 1", "native prompt does not descend from the previous response", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(user(t, c, 1), "parentUuid", Copy.str(uuid(user(t, c, 0)))); t.relayout(); }),
            control("O8 prompt differs from STDIN", "native prompt differs", c -> { Rows s = new Rows(c, "STDIN"); Copy.put(s.at(turn(c, 0).member("prompt")).member("message"), "content", Copy.str("Reply with exactly the single word BETA and nothing else.")); s.relayout(); }),
            control("S1a identity row without uuid", "expected string: uuid", c -> { Rows t = new Rows(c, "TRANSCRIPT"); attachments(t, c, 0).get(1).members().remove("uuid"); t.relayout(); }),
            control("S1b identity row with a duplicate uuid", "native UUID reused", c -> { Rows t = new Rows(c, "TRANSCRIPT"); List<Json.JObject> a = attachments(t, c, 0); Copy.put(a.get(1), "uuid", Copy.str(uuid(a.get(0)))); t.relayout(); }),
            control("S2 identity row with a foreign cwd", "native cwd differs from fresh launch", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(attachments(t, c, 0).get(0), "cwd", Copy.str("/elsewhere")); t.relayout(); }),
            control("S3 identity-class record in the transcript tail", "unassigned native tail holds a non-plain record", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(t.at(c.m.member("unassigned").elements().get(1)), "type", Copy.str("attachment")); t.relayout(); }),
            control("S4 stdout init session_id differs from the requested one", "mismatched session_id", c -> { Rows s = new Rows(c, "STDOUT"); Copy.put(s.at(c.m.member("shared").elements().get(0)), "session_id", Copy.str("11111111-2222-4333-8444-555555555555")); s.relayout(); }),
            control("S5 expected path existed before dispatch", "fresh profile cannot use existing path/history", c -> Copy.put(c.obs(0), "result", Copy.str("EXISTS"))),
            control("S6 first user with a non-null parent", "fresh native prompt has a parent", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(user(t, c, 0), "parentUuid", Copy.str("00000000-0000-4000-8000-000000000000")); t.relayout(); }),
            control("S7a complete argv carrying --resume", "history reuse option", c -> argv(c, List.of("", "--resume", "--session-id", c.m.member("nativeSessionId").text()))),
            control("S7b truncated argv carrying --resume (presence is still refused)", "history reuse option", c -> ((Json.JArray) c.m.member("freshStart").member("launchArgv")).elements().add(0, Copy.str("--resume"))),
            control("S7c complete argv that does not name the session", "launch argv does not request the session", c -> argv(c, List.of("", "--permission-mode", "default"))),
            control("S8 sidecar file beside the transcript reported as a second candidate", "sidecar file beside the native transcript", c -> { Json.JObject last = lastObservation(c, "final"); String expected = c.m.member("freshStart").member("expectedNativePath").text(); ((Json.JArray) last.member("candidates")).elements().add(Copy.str(expected.substring(0, expected.lastIndexOf('/') + 1) + "memory/notes.jsonl")); }),
            control("J1 unknown attachment subtype", "unknown native attachment class", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(attachments(t, c, 0).get(0).member("attachment"), "type", Copy.str("weather")); t.relayout(); }),
            control("J2 unknown stream record class", "unknown stream record class", c -> { Rows s = new Rows(c, "STDOUT"); Copy.put(s.ofType("rate_limit_event").get(0), "type", Copy.str("rate_limit")); s.relayout(); }),
            control("J3 streamed response content differs from the native response", "stream/native response differs: content", c -> { Rows s = new Rows(c, "STDOUT"); Copy.put(s.ofType("assistant").get(0).member("message").member("content").elements().get(0), "text", Copy.str("BETA")); s.relayout(); }),
            control("J4 trailing attachment not descending from the response", "trailing native record does not descend from the response", c -> { Rows t = new Rows(c, "TRANSCRIPT"); List<Json.JObject> a = attachments(t, c, 0); Copy.put(a.get(a.size() - 1), "parentUuid", Copy.str(uuid(user(t, c, 0)))); t.relayout(); }),
            control("J5 plain record with a foreign session", "mismatched sessionId", c -> { Rows t = new Rows(c, "TRANSCRIPT"); Copy.put(plain(t, c, 1, "ai-title"), "sessionId", Copy.str("11111111-2222-4333-8444-555555555555")); t.relayout(); }));
    }

    static void argv(Copy c, List<String> tail) { Json.JArray a = (Json.JArray) c.m.member("freshStart").member("launchArgv"); for (String s : tail) a.elements().add(Copy.str(s)); }

    @Test void nativeShapedGenerationQualifiesOnlyUnderTheNativeProfile(@TempDir Path dir) throws Exception {
        Copy copy = copy(dir); copy.rehash();
        if (!specimen()) assertThat(NeutralJson.write(copy.m)).isEqualTo(NeutralJson.write(NeutralJson.readDocument(FIXTURE.resolve("retained-fresh-generation.json")))); // rehash is a no-op on the committed fixture
        copy.validate(NATIVE_PROFILE);
        for (String frozen : List.of(PROFILE, ORDINARY_PROFILE))
            assertThatThrownBy(() -> copy.validate(frozen)).as(frozen).isInstanceOf(Rejection.class).hasMessageContaining("text profile requires one native prompt and response");
        List<Json> turns = copy.m.member("turns").elements(); assertThat(turns).hasSize(2);
        assertThat(turns.get(0).member("nativeRecords").elements()).hasSize(22); assertThat(turns.get(1).member("nativeRecords").elements()).hasSize(15);
        for (int i = 0; i < 2; i++) {
            String execution = turns.get(i).member("executionId").text(); Path out = dir.resolve("q" + i);
            FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), execution, out, NATIVE_PROFILE);
            Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
            assertThat(acquisition.member("retained_profile").text()).isEqualTo(NATIVE_PROFILE);
            assertThat(acquisition.member("retained_launch_argv").text()).isEqualTo("INCOMPLETE: truncated at --tools");
            assertThat(Files.readString(out.resolve("turn/transcript.jsonl")).lines().count()).isEqualTo(i == 0 ? 22 : 15);
            RetainedAcquisition.validate(acquisition, out, true);
            assertThatThrownBy(() -> RetainedAcquisition.validate(acquisition, out)).isInstanceOf(IllegalArgumentException.class);
            Path frozen = dir.resolve("frozen" + i);
            assertThatThrownBy(() -> FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), execution, frozen, PROFILE)).isInstanceOf(Rejection.class);
            assertThat(frozen).doesNotExist();
        }
    }

    @Test void completeArgvNamingTheSessionIsRecordedCompleteAndKeepsTheFrozenRule(@TempDir Path dir) throws Exception {
        Copy copy = copy(dir); argv(copy, List.of("", "--permission-mode", "default", "--session-id", copy.m.member("nativeSessionId").text())); copy.rehash();
        copy.validate(NATIVE_PROFILE);
        assertThat(FreshRetainedGeneration.launchArgvObservation(NeutralJson.readDocument(dir.resolve("retained-fresh-generation.json")))).isEqualTo("COMPLETE");
    }

    @Test void eachControlIsRefusedBySemanticsAfterRelayoutAndRehash(@TempDir Path root) throws Exception {
        int index = 0;
        for (Control control : controls()) {
            Path dir = Files.createDirectory(root.resolve("c" + index++)); Copy copy = copy(dir);
            control.mutation().accept(copy); copy.rehash();
            assertThatThrownBy(() -> copy.validate(NATIVE_PROFILE)).as(control.name()).isInstanceOf(Rejection.class)
                .hasMessageContaining(control.reason()).extracting(e -> ((Rejection) e).verdict()).isEqualTo(Verdict.REFUSED);
            Path out = dir.resolve("refused"); String execution = copy.turn(0).member("executionId").text();
            assertThatThrownBy(() -> FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), execution, out, NATIVE_PROFILE)).as(control.name()).isInstanceOf(Rejection.class);
            assertThat(out).as(control.name()).doesNotExist();
        }
        assertThat(index).isEqualTo(25);
    }

    /** The synthetic two-record generation of /1 is also admitted by the native model (a user directly parented to the previous assistant is a chain with no intermediates). */
    @Test void syntheticTwoRecordTurnsAreAdmittedByTheNativeModelToo(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir, FreshRetainedGenerationTest.FIXTURE); copy.rehash(); copy.validate(NATIVE_PROFILE);
        assertThat(FreshRetainedGeneration.launchArgvObservation(copy.m)).isEqualTo("COMPLETE");
    }
}
