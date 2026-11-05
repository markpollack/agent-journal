package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.Rejection;
import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.Verdict;
import io.github.markpollack.journal.tck.ocsf.inventory.FreshRetainedClaudeAcquisition;
import io.github.markpollack.journal.tck.ocsf.inventory.RetainedClaudeAcquisition;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.ORDINARY_PROFILE;
import static io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration.PROFILE;
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic fresh-start generations: the two-turn positive under both profiles with the thirteen agreed rejection classes,
 * and the three-turn generation that only the ordinary profile admits, each mutation separately rehashed so the verdict comes from semantics. */
class FreshRetainedGenerationTest {
    static final Path FIXTURE = Path.of("src/test/resources/retained-fresh-generation");
    static final Path THREE = Path.of("src/test/resources/retained-fresh-generation-3turn");
    static final String N = "7b1e0c2a-5d7f-4a3e-9c1b-2f6d8e4a1c3d";

    /** A rehashable working copy: edit originals, observation lines or manifest members, then validate. */
    static final class Copy {
        final Path dir; final Json.JObject m; final List<Json> observations = new ArrayList<>(); final Map<String, byte[]> sources = new java.util.HashMap<>();
        Copy(Path dir) throws Exception { this(dir, FIXTURE); }
        Copy(Path dir, Path fixture) throws Exception {
            this.dir = dir; m = (Json.JObject) NeutralJson.readDocument(fixture.resolve("retained-fresh-generation.json"));
            for (Json s : m.member("sources").elements()) sources.put(s.member("source").text(), Files.readAllBytes(fixture.resolve(s.member("path").text())));
            for (String line : Files.readString(fixture.resolve("observations.jsonl")).lines().toList()) observations.add(NeutralJson.parse(line));
        }
        Json.JObject turn(int i) { return (Json.JObject) m.member("turns").elements().get(i); }
        Json.JObject obs(int i) { return (Json.JObject) observations.get(i); }
        static void put(Json o, String k, Json v) { ((Json.JObject) o).members().put(k, v); }
        static Json str(String s) { return new Json.JString(s); }
        static Json num(long n) { return new Json.JNumber(Long.toString(n)); }
        Json prefix(long n) { return new Json.JObject(new java.util.LinkedHashMap<>(Map.of("bytes", num(n), "sha256", str(NativeArtifact.sha256(Arrays.copyOf(sources.get("TRANSCRIPT"), (int) n)))))); }
        void rehash() throws Exception {
            for (Json s : m.member("sources").elements()) { byte[] b = sources.get(s.member("source").text()); put(s, "sha256", str(NativeArtifact.sha256(b))); put(s, "size", num(b.length)); put(s, "observed", num(b.length)); }
            locators(m);
            StringBuilder journal = new StringBuilder(); for (Json o : observations) journal.append(NeutralJson.write(o)).append('\n');
            byte[] bytes = journal.toString().getBytes(StandardCharsets.UTF_8);
            if (m.member("observations") instanceof Json.JObject declaration) { put(declaration, "sha256", str(NativeArtifact.sha256(bytes))); put(declaration, "size", num(bytes.length)); }
            for (Map.Entry<String, byte[]> e : sources.entrySet()) for (Json s : m.member("sources").elements()) if (s.member("source").text().equals(e.getKey())) Files.write(dir.resolve(s.member("path").text()), e.getValue());
            Files.write(dir.resolve("observations.jsonl"), bytes);
            Files.writeString(dir.resolve("retained-fresh-generation.json"), NeutralJson.write(m));
        }
        private void locators(Json node) {
            if (node instanceof Json.JObject o) {
                if (o.member("source") != null && o.member("start") != null && sources.containsKey(o.member("source").text())) {
                    int start = ((Json.JNumber) o.member("start")).value().intValueExact(), end = ((Json.JNumber) o.member("end")).value().intValueExact();
                    put(o, "sha256", str(NativeArtifact.sha256(Arrays.copyOfRange(sources.get(o.member("source").text()), start, end))));
                }
                for (Json v : o.members().values()) locators(v);
            }
            else if (node instanceof Json.JArray a) for (Json v : a.elements()) locators(v);
        }
        void validate() throws Exception { validate(PROFILE); }
        void validate(String profile) throws Exception { FreshRetainedGeneration.validate(NeutralJson.readDocument(dir.resolve("retained-fresh-generation.json")), dir, profile); }
    }

    /** Qualifies one execution under {@code profile} and reads it back independently; the frozen /1 reader never admits it. */
    static void qualified(Copy copy, String execution, String profile, int nativeLines) throws Exception {
        Path out = copy.dir.resolve("q-" + profile.replace('/', '-') + "-" + execution);
        FreshRetainedClaudeAcquisition.qualify(copy.dir.resolve("retained-fresh-generation.json"), execution, out, profile);
        Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
        assertThat(acquisition.member("retained_profile").text()).isEqualTo(profile);
        assertThat(Files.readString(out.resolve("turn/transcript.jsonl")).lines().count()).isEqualTo(nativeLines);
        assertThat(Files.readString(out.resolve("turn/stdin.jsonl")).lines().count()).isEqualTo(1);
        assertThat(out.resolve("generation/observations.jsonl")).hasSameBinaryContentAs(copy.dir.resolve("observations.jsonl"));
        assertThat(out.resolve("generation/retained-fresh-generation.json")).exists();
        RetainedAcquisition.validate(acquisition, out, true);
        assertThatThrownBy(() -> RetainedAcquisition.validate(acquisition, out)).isInstanceOf(IllegalArgumentException.class); // not readable as the frozen /1 profile
    }

    @Test void positiveFreshStartQualifiesWithTaggedAbsenceAndEmittedIdentityUnderEitherProfile(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir); copy.rehash(); copy.validate(PROFILE); copy.validate(ORDINARY_PROFILE);
        assertThat(NeutralJson.write(copy.m)).isEqualTo(NeutralJson.write(NeutralJson.readDocument(FIXTURE.resolve("retained-fresh-generation.json")))); // rehash is a no-op on the committed fixture
        assertThat(copy.turn(0).member("nativePrefixBefore").member("state").text()).isEqualTo("ABSENT");
        for (String profile : List.of(PROFILE, ORDINARY_PROFILE)) for (String execution : List.of("E1", "E2")) qualified(copy, execution, profile, 2);
        assertThatThrownBy(() -> copy.validate("client-terminal-claude-retained-fresh-2.1.316/9")).isInstanceOf(Rejection.class).hasMessageContaining("unsupported fresh retained profile");
        Path unknown = dir.resolve("unknown");
        assertThatThrownBy(() -> FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), "E1", unknown, "client-terminal-claude-retained-fresh-2.1.316/9")).isInstanceOf(Rejection.class);
        assertThat(unknown).doesNotExist();
    }

    @Test void threeTurnGenerationQualifiesOnlyUnderTheOrdinaryProfileWithContinuityAtEveryTurn(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir, THREE); copy.rehash();
        assertThat(NeutralJson.write(copy.m)).isEqualTo(NeutralJson.write(NeutralJson.readDocument(THREE.resolve("retained-fresh-generation.json"))));
        assertThatThrownBy(() -> copy.validate(PROFILE)).isInstanceOf(Rejection.class).hasMessageContaining("exactly two turns").extracting(e -> ((Rejection) e).verdict()).isEqualTo(Verdict.REFUSED);
        Path frozen = dir.resolve("frozen");
        assertThatThrownBy(() -> FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), "E3", frozen, PROFILE)).isInstanceOf(Rejection.class);
        assertThat(frozen).doesNotExist();
        copy.validate(ORDINARY_PROFILE);
        assertThat(copy.m.member("turns").elements()).hasSize(3);
        for (int i = 1; i < 3; i++) assertThat(Json.sameValue(copy.turn(i).member("nativePrefixBefore"), copy.turn(i - 1).member("nativePrefixAfter"))).as("prefix continuity before turn %d", i + 1).isTrue();
        for (String execution : List.of("E1", "E2", "E3")) qualified(copy, execution, ORDINARY_PROFILE, 2);
    }

    /** Third-turn controls under the ordinary profile: the same classes must bite at a turn the frozen profile never reads. */
    static List<Control> thirdTurnControls() {
        return List.of(
            control("third decision reuses the second", Verdict.REFUSED, c -> Copy.put(c.turn(2), "decisionId", Copy.str("D2"))),
            control("third before-prefix relabelled to the first after-prefix", Verdict.REFUSED, c -> Copy.put(c.turn(2), "nativePrefixBefore", c.turn(0).member("nativePrefixAfter"))),
            control("third native prompt parented to the first response", Verdict.REFUSED, c -> c.sources.put("TRANSCRIPT", replace(c, "\"parentUuid\":\"a2\",\"sessionId\"", "\"parentUuid\":\"a1\",\"sessionId\""))),
            control("third after-prefix never observed", Verdict.INCOMPLETE, c -> c.observations.remove(8)),
            control("third prompt altered after capture", Verdict.REFUSED, c -> c.sources.put("TRANSCRIPT", replace(c, "\"third\",\"role\":\"user\"", "\"THIRD\",\"role\":\"user\""))));
    }

    @Test void thirdTurnMutationsAreRefusedOrIncompleteBySemanticsAfterRehash(@TempDir Path root) throws Exception {
        int index = 0;
        for (Control control : thirdTurnControls()) {
            Path dir = Files.createDirectory(root.resolve("t" + index++)); Copy copy = new Copy(dir, THREE);
            control.mutation().accept(copy); copy.rehash();
            assertThatThrownBy(() -> copy.validate(ORDINARY_PROFILE)).as(control.name()).isInstanceOf(Rejection.class).extracting(e -> ((Rejection) e).verdict()).isEqualTo(control.verdict());
            Path out = dir.resolve("refused");
            assertThatThrownBy(() -> FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), "E3", out, ORDINARY_PROFILE)).as(control.name()).isInstanceOf(Rejection.class);
            assertThat(out).as(control.name()).doesNotExist();
        }
        assertThat(index).isEqualTo(5);
    }

    @Test void frozenProfileStillRefusesTheTaggedAbsentPrefixAndFreshKind(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir); copy.rehash();
        assertThatThrownBy(() -> RetainedGeneration.validate(NeutralJson.readDocument(dir.resolve("retained-fresh-generation.json")), dir)).isInstanceOf(IllegalArgumentException.class).isNotInstanceOf(Rejection.class);
        assertThatThrownBy(() -> RetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), "E1", dir.resolve("old"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(dir.resolve("old")).doesNotExist();
        Json old = NeutralJson.readDocument(RetainedGenerationTest.FIXTURE.resolve("retained-generation.json"));
        Copy.put(old.member("turns").elements().get(0), "nativePrefixBefore", NeutralJson.parse("{\"state\":\"ABSENT\",\"observation\":\"before-first-dispatch\"}"));
        assertThatThrownBy(() -> RetainedGeneration.validate(old, RetainedGenerationTest.FIXTURE)).isInstanceOf(IllegalArgumentException.class);
    }

    record Control(String name, Verdict verdict, Consumer<Copy> mutation) { }
    static Control control(String name, Verdict verdict, Consumer<Copy> mutation) { return new Control(name, verdict, mutation); }

    static List<Control> controls() {
        return List.of(
            control("1 preexisting file for N, including empty", Verdict.REFUSED, c -> Copy.put(c.obs(0), "result", Copy.str("EXISTS"))),
            control("2a resume option in fresh launch", Verdict.REFUSED, c -> ((Json.JArray) c.m.member("freshStart").member("launchArgv")).elements().add(Copy.str("--resume"))),
            control("2b reused process generation", Verdict.REFUSED, c -> Copy.put(c.m.member("freshStart"), "processIdentity", Copy.str("G-prior"))),
            control("2c native parent from a prior session", Verdict.REFUSED, c -> c.sources.put("TRANSCRIPT", replace(c, "\"parentUuid\":null", "\"parentUuid\":\"p0\""))),
            control("3a two matching candidates", Verdict.INCOMPLETE, c -> ((Json.JArray) c.obs(7).member("candidates")).elements().add(Copy.str("/elsewhere/" + N + ".jsonl"))),
            control("3b skipped candidate subtree", Verdict.INCOMPLETE, c -> Copy.put(c.obs(1), "skipped", Copy.num(1))),
            control("4a omitted absence observation", Verdict.INCOMPLETE, c -> c.observations.remove(2)),
            control("4b probe timeout", Verdict.INCOMPLETE, c -> Copy.put(c.obs(2), "result", Copy.str("ETIMEDOUT"))),
            control("4c ENOENT inferred, no journal", Verdict.INCOMPLETE, c -> Copy.put(c.m, "observations", Json.JNull.INSTANCE)),
            control("5 ABSENT relabelled as observed zero prefix", Verdict.REFUSED, c -> Copy.put(c.turn(0), "nativePrefixBefore", c.prefix(0))),
            control("6a changed emitted N", Verdict.REFUSED, c -> Copy.put(c.m.member("observedNativeIdentity"), "reportedSessionId", Copy.str("other"))),
            control("6b changed cwd", Verdict.REFUSED, c -> Copy.put(c.m.member("freshStart"), "cwd", Copy.str("/other"))),
            control("6c changed decision", Verdict.REFUSED, c -> Copy.put(c.turn(1), "decisionId", Copy.str("D1"))),
            control("6d extra native user", Verdict.REFUSED, c -> { byte[] t = c.sources.get("TRANSCRIPT"); String third = "{\"cwd\":\"/work\",\"message\":{\"content\":\"third\",\"role\":\"user\"},\"parentUuid\":\"a2\",\"sessionId\":\"" + N + "\",\"type\":\"user\",\"uuid\":\"u3\",\"version\":\"2.1.316\"}\n"; byte[] b = Arrays.copyOf(t, t.length + third.length()); System.arraycopy(third.getBytes(StandardCharsets.UTF_8), 0, b, t.length, third.length()); c.sources.put("TRANSCRIPT", b); }),
            control("7a fileKey changes", Verdict.REFUSED, c -> Copy.put(c.obs(6), "fileKey", Copy.str("dev=2049,ino=9999"))),
            control("7b prefix shrinks", Verdict.REFUSED, c -> { Copy.put(c.obs(6), "bytes", Copy.num(1)); Copy.put(c.obs(6), "sha256", Copy.str(NativeArtifact.sha256(Arrays.copyOf(c.sources.get("TRANSCRIPT"), 1)))); }),
            control("7c original bytes change", Verdict.REFUSED, c -> c.sources.put("TRANSCRIPT", replace(c, "\"first\",\"type\":\"text\"", "\"FIRST\",\"type\":\"text\""))),
            control("8a readable prefix before dispatch (creation race)", Verdict.INCOMPLETE, c -> { Json early = NeutralJson.parse(NeutralJson.write(c.obs(4))); Copy.put(early, "seq", Copy.num(0)); Copy.put(early, "monotonicNanos", Copy.num(500)); c.observations.add(0, early); }),
            control("8b missing stable file identity", Verdict.INCOMPLETE, c -> Copy.put(c.obs(4), "fileKey", Copy.str(""))),
            control("9a unassigned prior conversational record", Verdict.REFUSED, c -> { c.sources.put("TRANSCRIPT", replace(c, "\"parentUuid\":null", "\"parentUuid\":\"p0\"")); String prior = "{\"cwd\":\"/work\",\"message\":{\"content\":\"earlier\",\"role\":\"user\"},\"parentUuid\":null,\"sessionId\":\"" + N + "\",\"type\":\"user\",\"uuid\":\"p0\",\"version\":\"2.1.316\"}\n"; byte[] t = c.sources.get("TRANSCRIPT"); byte[] b = new byte[t.length + prior.length()]; System.arraycopy(prior.getBytes(StandardCharsets.UTF_8), 0, b, 0, prior.length()); System.arraycopy(t, 0, b, prior.length(), t.length); c.sources.put("TRANSCRIPT", b); }),
            control("9b hidden third prompt", Verdict.REFUSED, c -> { byte[] s = c.sources.get("STDIN"); String third = "{\"message\":{\"content\":\"third\",\"role\":\"user\"},\"type\":\"user\"}\n"; byte[] b = Arrays.copyOf(s, s.length + third.length()); System.arraycopy(third.getBytes(StandardCharsets.UTF_8), 0, b, s.length, third.length()); c.sources.put("STDIN", b); }),
            control("10a premature seal", Verdict.INCOMPLETE, c -> Copy.put(c.m, "sealed", new Json.JBool(false))),
            control("10b forged complete declaration", Verdict.REFUSED, c -> { Copy.put(c.obs(7), "bytes", Copy.num(1)); Copy.put(c.obs(7), "sha256", Copy.str(NativeArtifact.sha256(Arrays.copyOf(c.sources.get("TRANSCRIPT"), 1)))); }),
            control("13 requested UUID but no CLI-emitted identity record", Verdict.INCOMPLETE, c -> Copy.put(c.m, "observedNativeIdentity", Json.JNull.INSTANCE)),
            control("13b identity taken from initialize control success", Verdict.INCOMPLETE, c -> { Json control = c.m.member("shared").elements().get(1); Json identity = c.m.member("observedNativeIdentity"); for (String k : List.of("start", "end", "sha256")) Copy.put(identity, k, control.member(k)); Copy.put(identity, "recordType", Copy.str("control_response")); }));
    }

    static byte[] replace(Copy c, String from, String to) { return new String(c.sources.get("TRANSCRIPT"), StandardCharsets.UTF_8).replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to)).getBytes(StandardCharsets.UTF_8); }

    @Test void eachRejectionClassIsRefusedOrIncompleteBySemanticsAfterRehash(@TempDir Path root) throws Exception {
        int index = 0;
        for (Control control : controls()) {
            Path dir = Files.createDirectory(root.resolve("c" + index++)); Copy copy = new Copy(dir);
            control.mutation().accept(copy); copy.rehash();
            assertThatThrownBy(copy::validate).as(control.name()).isInstanceOf(Rejection.class).extracting(e -> ((Rejection) e).verdict()).isEqualTo(control.verdict());
            Path out = dir.resolve("refused");
            assertThatThrownBy(() -> FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), "E1", out, PROFILE)).as(control.name()).isInstanceOf(Rejection.class);
            assertThat(out).as(control.name()).doesNotExist();
        }
        assertThat(index).isEqualTo(25);
    }

    @Test void contentAlteredAfterQualificationIsDefectiveOnIndependentRead(@TempDir Path dir) throws Exception {
        Copy copy = new Copy(dir); copy.rehash(); Path out = dir.resolve("q");
        FreshRetainedClaudeAcquisition.qualify(dir.resolve("retained-fresh-generation.json"), "E1", out, ORDINARY_PROFILE);
        Json acquisition = NeutralJson.readDocument(out.resolve("acquisition.json"));
        Json.JObject relabelled = (Json.JObject) NeutralJson.parse(NeutralJson.write(acquisition)); Copy.put(relabelled, "retained_profile", Copy.str("client-terminal-claude-retained-fresh-2.1.316/9"));
        assertThatThrownBy(() -> RetainedAcquisition.validate(relabelled, out, true)).as("unknown profile stamped after qualification").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported fresh retained profile");
        Path projected = out.resolve("turn/transcript.jsonl");
        Files.writeString(projected, Files.readString(projected).replace("first", "FIRST"));
        assertThatThrownBy(() -> RetainedAcquisition.validate(acquisition, out, true)).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(projected, Files.readString(projected).replace("FIRST", "first")); RetainedAcquisition.validate(acquisition, out, true);
        Path journal = out.resolve("generation/observations.jsonl");
        Files.writeString(journal, Files.readString(journal).replace("ENOENT", "EXISTS"));
        assertThatThrownBy(() -> RetainedAcquisition.validate(acquisition, out, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
