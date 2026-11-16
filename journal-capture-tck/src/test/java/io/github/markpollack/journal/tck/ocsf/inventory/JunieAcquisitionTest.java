package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.JunieAcpFamily;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Junie ACP one-shot qualifier (docs/junie-acquisition.md) on the real sanitized Junie 3813.7 recording junie-2, whose
 * sealed manifest is a labelled stand-in (ocsf/junie-acp-3813.7-session-files/README.md). Every refusal works on a temporary
 * copy with one mutation, and the new directory is never created; a mutation of a native file is a mutation of the real
 * recording, rehashed so that only the named check can refuse it. No process is launched and no model is called.
 */
class JunieAcquisitionTest {
    static final Path FIXTURE = Path.of("src/test/resources/ocsf/junie-acp-3813.7-session-files");
    static final String PROFILE = "junie-3813.7-acp-session-files";
    static final String SESSION = "session-000000-000002-0001";
    @TempDir Path work;

    private Path sealed() throws IOException {
        Path dir = Files.createDirectories(work.resolve("sealed"));
        for (String name : List.of("acquisition.json", "summary.json", "events.jsonl")) Files.copy(FIXTURE.resolve(name), dir.resolve(name));
        return dir.resolve("acquisition.json");
    }

    /** One edit of the parsed sealed manifest, written back. */
    private static void manifest(Path manifest, Consumer<Json.JObject> edit) throws IOException {
        Json.JObject m = (Json.JObject) NeutralJson.readDocument(manifest);
        edit.accept(m);
        Files.writeString(manifest, NeutralJson.write(m));
    }

    /** A mutation of the real native file {@code name}, with its declared extent and hash restated unless {@code rehash} is false. */
    private static void mutateNative(Path manifest, String name, String from, String to, boolean rehash) throws IOException {
        Path file = manifest.resolveSibling(name);
        String text = Files.readString(file);
        assertThat(text).contains(from);
        Files.writeString(file, text.replace(from, to));
        if (rehash) {
            byte[] bytes = Files.readAllBytes(file);
            manifest(manifest, m -> {
                Json.JObject source = source(m, name);
                source.members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
                for (String key : List.of("bytes", "observed")) source.members().put(key, new Json.JNumber(Integer.toString(bytes.length)));
            });
        }
    }

    private static Json.JObject source(Json m, String path) {
        return (Json.JObject) m.member("sources").elements().stream().filter(s -> path.equals(s.member("path").text())).findFirst().orElseThrow();
    }

    private static Json.JObject object(Json m, String... path) {
        return (Json.JObject) m.path(path);
    }

    private static void put(Json.JObject o, String key, String json) {
        o.members().put(key, NeutralJson.parse(json));
    }

    @Test void qualifiesTheRealRecordingCopyingOriginalsAndBindingBothSourcesAtTheVersion() throws IOException {
        Path input = sealed();
        byte[] before = Files.readAllBytes(input);
        AcquisitionQualifier.Qualified q = JunieAcquisition.qualify(input, work.resolve("qualified"));
        assertThat(Files.readAllBytes(input)).isEqualTo(before);
        assertThat(Files.readAllBytes(work.resolve("qualified/client-acquisition.json"))).isEqualTo(before);
        for (String name : List.of("summary.json", "events.jsonl")) {
            assertThat(Files.readAllBytes(work.resolve("qualified").resolve(name))).isEqualTo(Files.readAllBytes(FIXTURE.resolve(name)));
        }
        Json m = NeutralJson.readDocument(q.manifest());
        Json original = NeutralJson.readDocument(input);
        for (String key : List.of("kind", "version", "profile", "profile_id", "acquisition_uid", "complete", "capture_errors", "run", "process", "turns")) {
            assertThat(Json.sameValue(original.member(key), m.member(key))).as(key).isTrue();
        }
        assertThat(m.path("qualification", "original_manifest_sha256").text()).isEqualTo(NativeArtifact.sha256(before));
        for (Json s : m.member("sources").elements()) {
            assertThat(s.path("binding", "version").text()).isEqualTo("3813.7");
            assertThat(s.path("binding", "profile").text()).isEqualTo(s.member("profile").text());
        }
        Json expectation = NeutralJson.readDocument(q.assemblyExpectation());
        assertThat(expectation.member("profile_id").text()).isEqualTo(JunieAcpFamily.profileId("3813.7"));
        assertThat(expectation.member("sources").elements()).extracting(s -> s.member("role").text()).containsExactly("summary", "session");
        JunieAcquisition.validate(q.manifest());
        assertThatThrownBy(() -> JunieAcquisition.qualify(input, work.resolve("qualified"))).isInstanceOf(IOException.class);
    }

    @Test void theRegisteredQualifierWritesTheSameBytesAsTheStaticEntryPoint() throws IOException {
        Path input = sealed();
        AcquisitionQualifier qualifier = AcquisitionQualifier.forProfile(PROFILE).orElseThrow();
        assertThat(List.of(qualifier.acquisitionProfile(), qualifier.terminalProfile())).containsExactly(PROFILE, JunieAcpFamily.TERMINAL_PROFILE);
        qualifier.qualify(input, work.resolve("via-qualifier"));
        JunieAcquisition.qualify(input, work.resolve("via-static"));
        assertThat(AcquisitionQualifierTest.tree(work.resolve("via-qualifier"))).isNotEmpty().isEqualTo(AcquisitionQualifierTest.tree(work.resolve("via-static")));
    }

    /** Any well-formed version selects the family's qualifier; a version without bindings for both sources is refused, not mapped. */
    @Test void anUnknownVersionSelectsAQualifierThatRefusesIt() throws IOException {
        Path input = sealed();
        manifest(input, m -> {
            put(m, "profile", "\"junie-3813.8-acp-session-files\"");
            put(m, "profile_id", "\"junie/3813.8/acp+summary+session-events\"");
            put(object(m, "run", "cli"), "version", "\"3813.8\"");
        });
        AcquisitionQualifier qualifier = AcquisitionQualifier.forProfile("junie-3813.8-acp-session-files").orElseThrow();
        assertThat(qualifier.terminalProfile()).isEqualTo(JunieAcpFamily.TERMINAL_PROFILE);
        assertThatThrownBy(() -> qualifier.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown version").hasMessageContaining("3813.8");
        assertThat(work.resolve("rejected")).doesNotExist();
        for (String profile : List.of("junie-3813.7-session-primary", "junie-acp-session-files", "junie-latest-acp-session-files", "junie/3813.7/acp+summary+session-events")) {
            assertThat(AcquisitionQualifier.forProfile(profile)).as(profile).isEmpty();
        }
    }

    @Test void theQualifierRefusesAManifestOfAnotherProfile() throws IOException {
        Path input = sealed();
        manifest(input, m -> put(m, "profile", "\"codex-cli-0.166.0-exec-stdio-rollout\""));
        assertThatThrownBy(() -> AcquisitionQualifier.forProfile(PROFILE).orElseThrow().qualify(input, work.resolve("rejected")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unqualified profile");
        assertThat(work.resolve("rejected")).doesNotExist();
    }

    /** One mutation per case: the refusal's message names its reason, and nothing is written. */
    static Stream<Arguments> refusals() {
        return Stream.of(
                // 1. missing summary or events
                manifestCase("summary undeclared", m -> ((Json.JArray) m.member("sources")).elements().remove(0), "missing summary: the source is not declared"),
                manifestCase("events not acquired", m -> put(source(m, "events.jsonl"), "state", "\"missing\""), "missing session: state is missing"),
                fileCase("summary file absent", "summary.json", "missing summary: summary.json is absent"),
                fileCase("events file absent", "events.jsonl", "missing session: events.jsonl is absent"),
                // 2. more than one candidate native session
                manifestCase("two new sessions", m -> put(object(m, "process"), "native_sessions_new", "[\"" + SESSION + "\",\"session-000000-000003-0001\"]"),
                        "more than one candidate native session"),
                manifestCase("another new session", m -> put(object(m, "process"), "native_sessions_new", "[\"session-000000-000003-0001\"]"),
                        "more than one candidate native session"),
                // 3. identity mismatch with the ACP session
                nativeCase("summary sessionId", "summary.json", "\"sessionId\": \"" + SESSION + "\"", "\"sessionId\": \"session-000000-000003-0001\"", true,
                        "identity mismatch: summary.json sessionId"),
                manifestCase("run session", m -> put(object(m, "run"), "session_uid", "\"session-000000-000003-0001\""), "identity mismatch: run.session_uid"),
                manifestCase("owner session", m -> put(source(m, "events.jsonl"), "owner_session_uid", "\"session-000000-000003-0001\""),
                        "identity mismatch: session owner_session_uid"),
                manifestCase("origin outside", m -> put(source(m, "summary.json"), "origin", "\"/home/user/.junie/sessions/session-000000-000003-0001/summary.json\""),
                        "identity mismatch: summary origin"),
                manifestCase("origin of another file", m -> put(source(m, "events.jsonl"), "origin", "\"/home/user/.junie/sessions/" + SESSION + "/summary.json\""),
                        "identity mismatch: session origin"),
                manifestCase("project directory", m -> {
                    put(object(m, "process"), "cwd", "\"/elsewhere\"");
                    put(object(m, "run", "launch_settings"), "working_directory", "\"/elsewhere\"");
                }, "identity mismatch: summary.json projectDir"),
                manifestCase("working directory", m -> put(object(m, "process"), "cwd", "\"/elsewhere\""), "identity mismatch: launch_settings.working_directory"),
                // 4. incomplete events
                nativeCase("unterminated last line", "events.jsonl", "\"timestampMs\":1792817903043}\n", "\"timestampMs\":1792817903043}", true, "not newline-terminated"),
                manifestCase("record count", m -> put(source(m, "events.jsonl"), "records", "64"), "holds 65 records, declared 64"),
                nativeCase("no task start", "events.jsonl", "{\"kind\":\"TaskStartedEvent\"", "{\"kind\":\"TaskRenamedEvent\"", true, "0 TaskStartedEvent records"),
                manifestCase("task id", m -> put((Json.JObject) m.member("turns").elements().get(0), "native_task_uid", "\"task-000000-000003-0002\""),
                        "taskId differs from native_task_uid"),
                nativeCase("cancelled result", "events.jsonl", "\"cancelled\":false", "\"cancelled\":true", true, "no main-agent ResultBlockUpdatedEvent"),
                nativeCase("lifecycle", "summary.json", "\"lifecycle\": \"COMPLETED\"", "\"lifecycle\": \"RUNNING\"", true, "lifecycle is RUNNING, not COMPLETED"),
                manifestCase("stop reason", m -> put(object(m, "process"), "acp_stop_reason", "\"cancelled\""), "acp_stop_reason is cancelled, not end_turn"),
                manifestCase("not fresh", m -> put(object(m, "process"), "fresh_session", "false"), "process.fresh_session is not true"),
                manifestCase("not sealed", m -> put(object(m, "process"), "sealed", "false"), "process.sealed is not true"),
                manifestCase("resources", m -> put(object(m, "process"), "observed_resources_terminated", "false"), "process.observed_resources_terminated is not true"),
                manifestCase("incomplete", m -> put(m, "complete", "false"), "the acquisition is not complete"),
                manifestCase("capture errors", m -> put(m, "capture_errors", "[\"overflow\"]"), "capture errors present"),
                manifestCase("dropped", m -> put(source(m, "events.jsonl"), "dropped", "1"), "session was dropped or did not end at EOF"),
                manifestCase("interrupted", m -> put(source(m, "summary.json"), "end", "\"INTERRUPTED\""), "summary was dropped or did not end at EOF"),
                manifestCase("summary framing", m -> put(source(m, "summary.json"), "framing", "\"lines\""), "summary framing lines differs from its role"),
                manifestCase("session framing", m -> put(source(m, "events.jsonl"), "framing", "\"document\""), "session framing document differs from its role"),
                manifestCase("summary records", m -> put(source(m, "summary.json"), "records", "2"), "summary records 2 differs from its role"),
                // 5. changed bytes
                manifestCase("declared length", m -> put(source(m, "summary.json"), "bytes", "1095"), "changed bytes: summary.json is 1094 bytes"),
                manifestCase("observed length", m -> put(source(m, "events.jsonl"), "observed", "19700"), "changed bytes: events.jsonl is 19701 bytes"),
                manifestCase("declared hash", m -> put(source(m, "events.jsonl"), "sha256", "\"" + "0".repeat(64) + "\""), "changed bytes: events.jsonl SHA-256 differs"),
                nativeCase("file changed", "summary.json", "\"lifecycle\": \"COMPLETED\"", "\"lifecycle\": \"COMPLETEX\"", false, "changed bytes: summary.json SHA-256 differs"),
                nativeCase("file grown", "summary.json", "\"lifecycle\": \"COMPLETED\"", "\"lifecycle\":  \"COMPLETED\"", false, "changed bytes: summary.json is 1095 bytes"),
                // 6. credential-shaped environment: the names are given, never a value
                nativeCase("credential name", "events.jsonl", PATH_ENTRY, "{\"key\":\"GITHUB_TOKEN\",\"value\":\"" + PLACEHOLDER + "\"}," + PATH_ENTRY, true,
                        "credential-shaped environment: events.jsonl names the variables [GITHUB_TOKEN] (values not shown)"),
                nativeCase("credential names in any case", "events.jsonl", PATH_ENTRY, "{\"key\":\"my_session_id\",\"value\":\"" + PLACEHOLDER + "\"},"
                        + "{\"key\":\"Api_Key\",\"value\":\"" + PLACEHOLDER + "\"}," + PATH_ENTRY, true, "names the variables [Api_Key, my_session_id]"),
                nativeCase("environment not a list", "events.jsonl", "\"env\":[]", "\"env\":{}", true, "an EnvironmentVariablesUpdatedEvent env is not a list"),
                nativeCase("unnamed variable", "events.jsonl", PATH_ENTRY, "{\"value\":\"" + PLACEHOLDER + "\"}," + PATH_ENTRY, true,
                        "an EnvironmentVariablesUpdatedEvent entry names no variable"),
                // the profile, profile id, kind and version must be exact
                manifestCase("profile", m -> put(m, "profile", "\"junie-3813.7-acp-session\""), "profile junie-3813.7-acp-session is not junie-3813.7-acp-session-files"),
                manifestCase("profile id", m -> put(m, "profile_id", "\"junie/3813.7/acp+summary\""), "profile_id junie/3813.7/acp+summary is not"),
                manifestCase("kind", m -> put(m, "kind", "\"agent-journal/acquired-thread\""), "unsupported acquisition kind or version"),
                manifestCase("cli version", m -> put(object(m, "run", "cli"), "version", "\"3813.8\""), "unknown version"),
                manifestCase("malformed version", m -> put(object(m, "run", "cli"), "version", "\"latest\""), "unknown version"),
                manifestCase("route", m -> put(object(m, "run", "launch_settings"), "route", "\"exec_stdio\""), "unsupported route exec_stdio"),
                manifestCase("role", m -> put(source(m, "events.jsonl"), "role", "\"stdout\""), "unsupported source role stdout"),
                manifestCase("source profile", m -> put(source(m, "events.jsonl"), "profile", "\"junie_stdout\""), "session profile or route differs"),
                manifestCase("archive only", m -> put(source(m, "summary.json"), "archive_only", "true"), "summary must be required and not archive-only"),
                manifestCase("escaping path", m -> put(source(m, "summary.json"), "path", "\"../summary.json\""), "source path escapes the acquisition"),
                manifestCase("reserved path", m -> put(source(m, "summary.json"), "path", "\"acquisition.json\""), "reserved source path"),
                manifestCase("two turns", m -> ((Json.JArray) m.member("turns")).elements().add(m.member("turns").elements().get(0)), "exactly one turn required"),
                // usage: as reported, never synthesised; malformed counts and costs refused
                nativeCase("negative tokens", "summary.json", "\"outputTokens\": 1589", "\"outputTokens\": -1", true, "usage: gemini-3.7-flash outputTokens is not a non-negative integer"),
                nativeCase("fractional tokens", "summary.json", "\"outputTokens\": 1589", "\"outputTokens\": 1589.5", true, "usage: gemini-3.7-flash outputTokens is not a non-negative integer"),
                nativeCase("textual tokens", "summary.json", "\"outputTokens\": 1589", "\"outputTokens\": \"1589\"", true, "usage: gemini-3.7-flash outputTokens is not a non-negative integer"),
                nativeCase("negative cost", "summary.json", "\"cost\": 0.00156,", "\"cost\": -0.00156,", true, "usage: gpt-4.1-2025-04-14 cost is not a non-negative number"),
                nativeCase("textual cost", "summary.json", "\"cost\": 0.00156,", "\"cost\": \"0.00156\",", true, "usage: gpt-4.1-2025-04-14 cost is not a non-negative number"),
                nativeCase("unnamed model", "summary.json", "\"model\": \"gpt-5.4-nano\",", "", true, "usage: a mainAgent.modelUsage entry names no model"));
    }

    private interface Mutation {
        void apply(Path manifest) throws IOException;
    }

    private static Arguments manifestCase(String name, Consumer<Json.JObject> edit, String reason) {
        return Arguments.of(name, (Mutation) manifest -> manifest(manifest, edit), reason);
    }

    private static Arguments fileCase(String name, String file, String reason) {
        return Arguments.of(name, (Mutation) manifest -> Files.delete(manifest.resolveSibling(file)), reason);
    }

    private static Arguments nativeCase(String name, String file, String from, String to, boolean rehash, String reason) {
        return Arguments.of(name, (Mutation) manifest -> mutateNative(manifest, file, from, to, rehash), reason);
    }

    /** The kept launch entry every environment mutation inserts before; the inserted values are placeholders, never a credential. */
    static final String PATH_ENTRY = "{\"key\":\"PATH\",\"value\":";
    static final String PLACEHOLDER = "placeholder-not-a-secret";

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void eachRefusalNamesItsReasonAndCreatesNothing(String name, Mutation mutation, String reason) throws IOException {
        Path input = sealed();
        mutation.apply(input);
        assertThatThrownBy(() -> JunieAcquisition.qualify(input, work.resolve("rejected"))).as(name).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(reason)
                .hasMessageNotContaining(PLACEHOLDER);
        assertThatThrownBy(() -> AcquisitionQualifier.forProfile(PROFILE).orElseThrow().qualify(input, work.resolve("rejected"))).as(name)
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(work.resolve("rejected")).doesNotExist();
    }

    /** A mutation of the real recording: SSH_AUTH_SOCK and XAUTHORITY name sockets and files, not credentials, and are accepted. */
    @Test void sshAuthSockAndXauthorityAreNotCredentialShaped() throws IOException {
        Path input = sealed();
        mutateNative(input, "events.jsonl", PATH_ENTRY, "{\"key\":\"SSH_AUTH_SOCK\",\"value\":\"" + PLACEHOLDER + "\"},{\"key\":\"XAUTHORITY\",\"value\":\""
                + PLACEHOLDER + "\"}," + PATH_ENTRY, true);
        JunieAcquisition.qualify(input, work.resolve("qualified"));
        assertThat(Files.readString(work.resolve("qualified/events.jsonl"))).contains("\"key\":\"SSH_AUTH_SOCK\"").contains("\"key\":\"XAUTHORITY\"");
    }

    /** The unreduced real junie-2 session events (read, never changed) name credential-shaped variables and are refused. */
    @Test void theUnreducedRealEnvironmentIsRefused() throws IOException {
        Path input = sealed();
        Path original = Path.of("src/test/resources/ocsf/fixtures/junie-live-3813.7-terminal/native/session-events.jsonl");
        Files.copy(original, input.resolveSibling("events.jsonl"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        byte[] bytes = Files.readAllBytes(original);
        manifest(input, m -> {
            Json.JObject source = source(m, "events.jsonl");
            source.members().put("sha256", new Json.JString(NativeArtifact.sha256(bytes)));
            for (String key : List.of("bytes", "observed")) source.members().put(key, new Json.JNumber(Integer.toString(bytes.length)));
        });
        assertThatThrownBy(() -> JunieAcquisition.qualify(input, work.resolve("rejected"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("credential-shaped environment: events.jsonl names the variables [").hasMessageEndingWith("] (values not shown)");
        assertThat(work.resolve("rejected")).doesNotExist();
    }

    /** The real junie-2 environment listed credential names; the fixture keeps only Client's launch allowlist, and it qualifies. */
    @Test void theFixtureEnvironmentHoldsOnlyAllowlistedNames() throws IOException {
        List<String> allowlist = List.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "LANG", "LC_ALL", "LC_CTYPE", "TERM", "TMPDIR", "TZ", "JAVA_HOME", "XDG_CONFIG_HOME",
                "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR", "DBUS_SESSION_BUS_ADDRESS");
        List<String> names = Files.readAllLines(FIXTURE.resolve("events.jsonl")).stream().map(NeutralJson::parse)
                .filter(r -> "EnvironmentVariablesUpdatedEvent".equals(r.path("event", "agentEvent", "kind") == null ? null : r.path("event", "agentEvent", "kind").text()))
                .flatMap(r -> r.path("event", "agentEvent", "env").elements().stream()).map(e -> e.member("key").text()).toList();
        assertThat(names).isNotEmpty().allMatch(allowlist::contains);
    }

    /** A mutation of the real recording: the first model's cost set to 0 (and 0.0) is a valid observation, copied unchanged. */
    @Test void aZeroCostIsAcceptedAndCarriedThroughUnchanged() throws IOException {
        for (String zero : List.of("0", "0.0")) {
            Path dir = work.resolve("zero-" + zero);
            Files.createDirectories(dir);
            for (String name : List.of("acquisition.json", "summary.json", "events.jsonl")) Files.copy(FIXTURE.resolve(name), dir.resolve(name));
            mutateNative(dir.resolve("acquisition.json"), "summary.json", "\"cost\": 0.013920400000000003,", "\"cost\": " + zero + ",", true);
            AcquisitionQualifier.Qualified q = JunieAcquisition.qualify(dir.resolve("acquisition.json"), dir.resolve("qualified"));
            byte[] copied = Files.readAllBytes(q.manifest().resolveSibling("summary.json"));
            assertThat(copied).isEqualTo(Files.readAllBytes(dir.resolve("summary.json")));
            assertThat(new String(copied, StandardCharsets.UTF_8)).contains("\"cost\": " + zero + ",");
        }
    }

    /** A mutation of the real recording: a token count the entry does not report stays absent, never zero. */
    @Test void anAbsentTokenCountStaysAbsent() throws IOException {
        Path input = sealed();
        mutateNative(input, "summary.json", "\"cacheCreateTokens\": 0,\n        \"outputTokens\": 1589", "\"outputTokens\": 1589", true);
        AcquisitionQualifier.Qualified q = JunieAcquisition.qualify(input, work.resolve("qualified"));
        Json entry = NeutralJson.readDocument(q.manifest().resolveSibling("summary.json")).path("mainAgent", "modelUsage").elements().get(0);
        assertThat(entry.member("model").text()).isEqualTo("gemini-3.7-flash");
        assertThat(entry.member("cacheCreateTokens")).isNull();
        assertThat(NeutralJson.readDocument(q.manifest()).toString()).doesNotContain("cacheCreateTokens");
    }

    /** A summary that reports no usage at all is qualified as it is: nothing is added. */
    @Test void aSummaryWithoutUsageIsQualifiedWithNoneAdded() throws IOException {
        Path input = sealed();
        String summary = Files.readString(FIXTURE.resolve("summary.json"));
        String withoutUsage = summary.substring(0, summary.indexOf(",\n  \"mainAgent\"")) + "\n}\n";
        mutateNative(input, "summary.json", summary, withoutUsage, true);
        AcquisitionQualifier.Qualified q = JunieAcquisition.qualify(input, work.resolve("qualified"));
        assertThat(Files.readString(q.manifest().resolveSibling("summary.json"))).isEqualTo(withoutUsage).doesNotContain("modelUsage");
    }

    @Test void aQualifiedManifestWithoutItsExactBindingIsRefused() throws IOException {
        AcquisitionQualifier.Qualified q = JunieAcquisition.qualify(sealed(), work.resolve("qualified"));
        manifest(q.manifest(), m -> put(source(m, "summary.json"), "binding", "{\"profile\":\"junie_summary\"}"));
        assertThatThrownBy(() -> JunieAcquisition.validate(q.manifest())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary does not carry its exact meaning binding");
    }

    @Test void theCommandQualifiesWithTwoArgumentsOnly() throws IOException {
        Path input = sealed();
        JunieAcquisition.main(new String[]{input.toString(), work.resolve("by-command").toString()});
        assertThat(work.resolve("by-command/acquisition.json")).isRegularFile();
        assertThatThrownBy(() -> JunieAcquisition.main(new String[]{input.toString()})).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage");
    }

    @Test void theFamilyNamesCarryTheVersion() {
        assertThat(JunieAcpFamily.versionOfProfile(PROFILE)).isEqualTo("3813.7");
        assertThat(JunieAcpFamily.versionOfProfileId("junie/3813.7/acp+summary+session-events")).isEqualTo("3813.7");
        assertThat(JunieAcpFamily.admits("junie/3813.7/acp+summary+session-events")).isTrue();
        assertThat(JunieAcpFamily.admits("junie/3813.7/session-summary+session-events+stdout+stderr")).isFalse();
        assertThat(JunieAcpFamily.versionOfProfile(null)).isNull();
    }
}
