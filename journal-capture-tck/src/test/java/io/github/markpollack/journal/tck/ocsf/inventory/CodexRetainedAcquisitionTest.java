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
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic Codex retained thread: both turns qualify, the one-shot qualifier refuses the follow-up turn,
 * and rehashed semantic mutations are refused through the ten-area rejection classes. */
class CodexRetainedAcquisitionTest {
    static final Path FIXTURE = Path.of("src/test/resources/ocsf/syn-codex-retained-0.166.0");
    static final String N = "a94e0250-c7a5-4bc3-adef-fcbef96582ac";

    /** Working copy whose thread manifest, per-turn manifests and originals can be edited and rehashed. */
    static final class Copy {
        final Path dir; final Json.JObject thread; final Json.JObject[] perTurn = new Json.JObject[2]; byte[] transcript; private final byte[] originalTranscript;
        Copy(Path dir) throws Exception {
            this.dir = dir;
            try (Stream<Path> files = Files.walk(FIXTURE)) { for (Path p : files.toList()) { Path target = dir.resolve(FIXTURE.relativize(p)); if (Files.isDirectory(p)) Files.createDirectories(target); else Files.copy(p, target); } }
            thread = (Json.JObject) NeutralJson.readDocument(dir.resolve("codex-thread.json")); transcript = Files.readAllBytes(dir.resolve("transcript.bin")); originalTranscript = transcript.clone();
            for (int i = 0; i < 2; i++) perTurn[i] = (Json.JObject) NeutralJson.readDocument(dir.resolve("turn-" + i + "/codex-acquisition.json"));
        }
        Json.JObject turn(int i) { return (Json.JObject) thread.member("turns").elements().get(i); }
        Json.JObject perTurnTurn(int i) { return (Json.JObject) perTurn[i].member("turns").elements().get(0); }
        static void put(Json o, String k, Json v) { ((Json.JObject) o).members().put(k, v); }
        static Json str(String s) { return new Json.JString(s); }
        static Json num(long n) { return new Json.JNumber(Long.toString(n)); }
        Json usage(long in, long cached, long write, long out, long reasoning) { return NeutralJson.parse("{\"input_tokens\":" + in + ",\"cached_input_tokens\":" + cached + ",\"cache_write_input_tokens\":" + write + ",\"output_tokens\":" + out + ",\"reasoning_output_tokens\":" + reasoning + ",\"total_tokens\":" + (in + out) + "}"); }
        /** Rewrites the transcript and restates every hash/extent that an honest producer would restate. */
        void rehash() throws Exception {
            Files.write(dir.resolve("transcript.bin"), transcript);
            Json t = thread.member("transcript"); put(t, "bytes", num(transcript.length)); put(t, "sha256", str(NativeArtifact.sha256(transcript)));
            put(t.member("last_sealed_prefix"), "bytes", num(transcript.length)); put(t.member("last_sealed_prefix"), "sha256", str(NativeArtifact.sha256(transcript)));
            boolean changed = !Arrays.equals(transcript, originalTranscript);
            for (int i = 0; i < 2; i++) {
                for (Json locator : locators(turn(i))) { int s = (int) n(locator, "start"), e = (int) Math.min(n(locator, "end"), transcript.length); if (s < e) put(locator, "sha256", str(NativeArtifact.sha256(Arrays.copyOfRange(transcript, s, e)))); }
                if (changed) { // an honest producer restates its sealed prefixes and snapshots after the bytes it observed changed
                    List<Json> records = turn(i).member("native_records").member("records").elements();
                    int end = (int) Math.min(n(records.get(records.size() - 1), "end"), transcript.length); byte[] prefix = Arrays.copyOf(transcript, end);
                    Json after = NeutralJson.parse("{\"bytes\":" + end + ",\"sha256\":\"" + NativeArtifact.sha256(prefix) + "\"}");
                    put(turn(i), "native_prefix_after", after); put(perTurnTurn(i), "native_prefix_after", after);
                    if (i == 1) put(perTurnTurn(1), "native_prefix_before", turn(0).member("native_prefix_after"));
                    Files.write(dir.resolve("turn-" + i + "/native.bin"), prefix);
                    for (Json s : perTurn[i].member("sources").elements()) if ("transcript".equals(s.member("role").text())) { put(s, "bytes", num(prefix.length)); put(s, "observed", num(prefix.length)); put(s, "sha256", str(NativeArtifact.sha256(prefix))); }
                }
                for (Json s : turn(i).member("sources").elements()) { byte[] b = Files.readAllBytes(dir.resolve(s.member("path").text())); put(s, "bytes", num(b.length)); put(s, "observed", num(b.length)); put(s, "sha256", str(NativeArtifact.sha256(b))); }
                Files.writeString(dir.resolve("turn-" + i + "/codex-acquisition.json"), NeutralJson.write(perTurn[i]));
            }
            Files.writeString(dir.resolve("codex-thread.json"), NeutralJson.write(thread));
        }
        private List<Json> locators(Json turn) { Json r = turn.member("native_records"); return Stream.concat(r.member("shared").elements().stream(), r.member("records").elements().stream()).toList(); }
        static long n(Json o, String k) { return ((Json.JNumber) o.member(k)).value().longValueExact(); }
        CodexRetainedGeneration.RetainedThread validate() throws Exception { return CodexRetainedGeneration.validate(NeutralJson.readDocument(dir.resolve("codex-thread.json")), dir); }
    }

    @Test void bothTurnsQualifyWithProjectionsOriginalsAndPreservedCumulativeUsage(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir); CodexRetainedGeneration.RetainedThread thread = copy.validate();
        assertThat(thread.turns()).hasSize(2); assertThat(thread.session()).isEqualTo(N);
        for (int i = 0; i < 2; i++) {
            String execution = thread.turns().get(i).executionId(); Path out = dir.resolve("q" + i);
            CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), execution, out);
            Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
            assertThat(acquisition.member("retained_profile").text()).isEqualTo(CodexRetainedGeneration.PROFILE);
            assertThat(acquisition.member("wire_usage_scope").text()).isEqualTo("per-turn");
            String projection = Files.readString(out.resolve("turn/transcript.jsonl"));
            assertThat(projection.lines().count()).isEqualTo(i == 0 ? 8 : 9);
            assertThat(projection.lines().filter(l -> l.contains("session_meta")).count()).isEqualTo(1);
            assertThat(projection).contains(thread.turns().get(i).nativeTurnId()).doesNotContain(thread.turns().get(1 - i).nativeTurnId());
            if (i == 0) assertThat(Files.readAllBytes(out.resolve("turn/transcript.jsonl"))).isEqualTo(Files.readAllBytes(dir.resolve("turn-0/native.bin")));
            Json turn = acquisition.member("turns").elements().get(0);
            assertThat(turn.member("thread_token_usage").member("input_tokens").text()).isEqualTo(i == 0 ? "11" : "22");
            assertThat(turn.member("turn_token_usage").member("input_tokens").text()).isEqualTo("11");
            CodexRetainedAcquisition.validate(acquisition, out); CodexAcquisitionReading.validate(out.resolve("acquisition.json"));
            assertThat(out.resolve("generation/codex-thread.original.json")).hasSameBinaryContentAs(dir.resolve("codex-thread.json"));
            assertThat(Json.sameValue(NeutralJson.readDocument(out.resolve("generation/codex-thread.json")), NeutralJson.readDocument(dir.resolve("codex-thread.json")))).isTrue(); // already relative
            assertThat(out.resolve("generation/turn-" + i + "/native.bin")).hasSameBinaryContentAs(dir.resolve("turn-" + i + "/native.bin"));
        }
        // The one-shot qualifier refuses the resumed turn and never creates output; the fresh first turn is a legitimate one-shot.
        Path oneShot = dir.resolve("one-shot-turn-1");
        assertThatThrownBy(() -> CodexAcquisition.qualify(dir.resolve("turn-1/codex-acquisition.json"), oneShot)).isInstanceOf(IllegalArgumentException.class);
        assertThat(oneShot).doesNotExist();
        assertThatThrownBy(() -> thread.turn("missing")).isInstanceOf(Rejection.class);
    }

    record Control(String name, Verdict verdict, Consumer<Copy> mutation) { }
    static Control c(String name, Verdict verdict, Consumer<Copy> mutation) { return new Control(name, verdict, mutation); }

    static List<Control> controls() {
        return List.of(
            c("foreign turn: region task_started carries the other turn's id", Verdict.REFUSED, k -> k.transcript = swap(k.transcript, "\"turn_id\": \"393b1549-a4cc-4cd7-aee8-1fc6d96cedcc\"", "\"turn_id\": \"b8d56e6a-5cfa-499c-a6e2-a33f7ebd378d\"")),
            c("foreign thread: follow-up resumes another thread", Verdict.REFUSED, k -> { Copy.put(k.turn(1), "resume_thread_id", Copy.str("00000000-0000-4000-8000-000000000000")); }),
            c("cross-association: turn 1 originals replaced by turn 0's process", Verdict.REFUSED, k -> { for (Json s : k.turn(1).member("sources").elements()) Copy.put(s, "path", Copy.str(s.member("path").text().replace("turn-1/", "turn-0/"))); }),
            c("preexisting/ambiguous fresh source: first turn claims a resumed thread", Verdict.REFUSED, k -> Copy.put(k.turn(0), "resume_thread_id", Copy.str(N))),
            c("missing emitted identity on the first turn", Verdict.INCOMPLETE, k -> Copy.put(k.turn(0), "identity_source", Copy.str("resume-argv"))),
            c("changed original: prompt text altered after capture", Verdict.REFUSED, k -> k.transcript = swap(k.transcript, "\"text\": \"second\"", "\"text\": \"SECOND\"")),
            c("truncated original: final transcript shorter than its last record", Verdict.REFUSED, k -> k.transcript = Arrays.copyOf(k.transcript, k.transcript.length - 40)),
            c("invalid association: per-turn manifest from another connection", Verdict.REFUSED, k -> Copy.put(k.perTurn[1].member("process"), "connection_uid", Copy.str("other-connection"))),
            c("double counting: thread usage adds the turn twice", Verdict.REFUSED, k -> { Copy.put(k.turn(1), "thread_token_usage", k.usage(33, 9, 0, 12, 0)); Copy.put(k.turn(1), "total_token_usage", k.usage(33, 9, 0, 12, 0)); }),
            c("prefix continuity: follow-up before-prefix differs from first after-prefix", Verdict.REFUSED, k -> Copy.put(k.perTurnTurn(1).member("native_prefix_before"), "bytes", Copy.num(100))),
            c("partition gap: a region record dropped", Verdict.REFUSED, k -> k.turn(1).member("native_records").member("records").elements().remove(2)),
            c("session_meta inside a region / shared differs", Verdict.REFUSED, k -> Copy.put(k.turn(1).member("native_records").member("shared").elements().get(0), "end", Copy.num(0))),
            c("wire usage neither per-turn nor cumulative", Verdict.REFUSED, k -> { Json w = k.turn(1).member("wire_usage"); Copy.put(w, "input_tokens", Copy.num(99)); Copy.put(k.perTurnTurn(1).member("wire_usage"), "input_tokens", Copy.num(99)); }),
            c("unsealed follow-up turn", Verdict.INCOMPLETE, k -> Copy.put(k.turn(1), "state", Copy.str("UNSEALED"))),
            c("final transcript not equal to last sealed prefix", Verdict.INCOMPLETE, k -> Copy.put(k.thread.member("transcript"), "final_equals_last_sealed_prefix", new Json.JBool(false))),
            c("closure not observed: stdout EOF missing", Verdict.INCOMPLETE, k -> Copy.put(k.turn(1), "stdout_natural_eof", new Json.JBool(false))),
            c("per-turn manifest missing", Verdict.INCOMPLETE, k -> Copy.put(k.turn(1), "per_turn_manifest", Json.JNull.INSTANCE)),
            c("forbidden history option", Verdict.REFUSED, k -> ((Json.JArray) k.turn(1).member("argv")).elements().add(1, Copy.str("--last"))));
    }

    static byte[] swap(byte[] bytes, String from, String to) { String s = new String(bytes, StandardCharsets.UTF_8); assertThat(s).contains(from); return s.replace(from, to).getBytes(StandardCharsets.UTF_8); }

    @Test void rehashedSemanticMutationsAreRefusedOrIncompleteBeforeOutput(@TempDir Path root) throws Exception {
        int index = 0;
        for (Control control : controls()) {
            Path dir = Files.createDirectory(root.resolve("c" + index++)); Copy copy = new Copy(dir);
            control.mutation().accept(copy); copy.rehash();
            assertThatThrownBy(copy::validate).as(control.name()).isInstanceOf(Rejection.class).extracting(e -> ((Rejection) e).verdict()).isEqualTo(control.verdict());
            Path out = dir.resolve("refused"); String execution = copy.turn(1).member("execution_id").text();
            assertThatThrownBy(() -> CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), execution, out)).as(control.name()).isInstanceOf(Rejection.class);
            assertThat(out).as(control.name()).doesNotExist();
        }
        assertThat(index).isEqualTo(18);
    }

    @Test void qualifiedOutputAlteredAfterQualificationIsRejectedOnIndependentRead(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir); String execution = copy.turn(1).member("execution_id").text(); Path out = dir.resolve("q");
        CodexRetainedAcquisition.qualify(dir.resolve("codex-thread.json"), execution, out);
        Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
        Path projection = out.resolve("turn/transcript.jsonl"); String original = Files.readString(projection);
        Files.writeString(projection, original.replace("\"text\": \"second\"", "\"text\": \"SECOND\""));
        assertThatThrownBy(() -> CodexRetainedAcquisition.validate(acquisition, out)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(projection, original); CodexRetainedAcquisition.validate(acquisition, out);
        Json.JObject forged = (Json.JObject) NeutralJson.parse(NeutralJson.write(acquisition)); Copy.put(forged, "wire_usage_scope", Copy.str("cumulative"));
        assertThatThrownBy(() -> CodexRetainedAcquisition.validate(forged, out)).isInstanceOf(IllegalArgumentException.class);
    }
}
