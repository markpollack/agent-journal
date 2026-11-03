package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Labelled synthetic observations, independently checked against their original byte population. */
class RetainedGenerationTest {
    static final Path FIXTURE = Path.of("src/test/resources/retained-text-generation");

    static Json manifest() throws Exception { return NeutralJson.readDocument(FIXTURE.resolve("retained-generation.json")); }
    static Json.JObject object(Json j) { return (Json.JObject) j; }
    static void put(Json j, String key, Json value) { object(j).members().put(key, value); }

    @Test void exactTwoTurnMembershipAndPrefixesProduceOnlyThatTurnsNativeRecords() throws Exception {
        Json m = manifest(); RetainedGeneration.validate(m, FIXTURE);
        for (String execution : List.of("E1", "E2")) {
            byte[] nativeBytes = RetainedGeneration.projection(m, execution, "TRANSCRIPT", FIXTURE);
            String text = new String(nativeBytes, StandardCharsets.UTF_8);
            assertThat(text).contains(execution.equals("E1") ? "first" : "second").doesNotContain(execution.equals("E1") ? "second" : "first");
            assertThat(text.lines().count()).isEqualTo(2);
            String stream = new String(RetainedGeneration.projection(m, execution, "STDOUT", FIXTURE), StandardCharsets.UTF_8);
            assertThat(stream.lines().count()).isEqualTo(2);
            assertThat(stream).contains("2.1.316", "result");
            assertThat(RetainedGeneration.projection(m, execution, "STDERR", FIXTURE)).isEmpty();
        }
        assertThatThrownBy(() -> RetainedGeneration.turn(m, "wrong")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void ownershipIdentityPrefixAndCompletenessCorruptionsCannotQualify() throws Exception {
        for (String fault : List.of("sealed", "stdoutNaturalEof", "stderrNaturalEof", "observedResourcesTerminated", "overflow",
                "version", "session", "prefix-hash", "prefix-extent", "swapped-prompt", "swapped-result", "reused-native",
                "missing-result", "wrong-execution", "wrong-decision", "missing-source", "extra-source", "wrong-locator-hash",
                "partial-record", "window", "source-hash", "dropped", "unassigned-execution")) {
            Json m = manifest(); Json turn = m.member("turns").elements().get(0), second = m.member("turns").elements().get(1);
            corrupt(m, turn, second, fault);
            assertThatThrownBy(() -> RetainedGeneration.validate(m, FIXTURE)).as(fault).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void nonTextPromptCannotQualifyEvenWithMatchingNativeMessageAndValidHashes(@TempDir Path directory) throws Exception {
        Json m = manifest(); Map<String, byte[]> sources = new HashMap<>();
        for (Json source : m.member("sources").elements()) {
            String name = source.member("source").text(), path = source.member("path").text();
            byte[] original = Files.readAllBytes(FIXTURE.resolve(path));
            String text = new String(original, StandardCharsets.UTF_8);
            if (name.equals("STDIN") || name.equals("TRANSCRIPT")) text = text.replaceFirst("\"first\"", "1234567");
            byte[] changed = text.getBytes(StandardCharsets.UTF_8);
            assertThat(changed).hasSameSizeAs(original); // Extents remain exact; only source content changes.
            Files.write(directory.resolve(path), changed); sources.put(name, changed);
            put(source, "sha256", new Json.JString(NativeArtifact.sha256(changed)));
        }
        refreshLocators(m, sources);
        for (Json turn : m.member("turns").elements()) for (String name : List.of("nativePrefixBefore", "nativePrefixAfter")) {
            Json prefix = turn.member(name); int size = ((Json.JNumber) prefix.member("bytes")).value().intValueExact();
            put(prefix, "sha256", new Json.JString(NativeArtifact.sha256(Arrays.copyOf(sources.get("TRANSCRIPT"), size))));
        }
        assertThatThrownBy(() -> RetainedGeneration.validate(m, directory)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("content");
    }

    private static void refreshLocators(Json node, Map<String, byte[]> sources) throws Exception {
        if (node instanceof Json.JObject object) {
            if (object.member("source") != null && object.member("start") != null) {
                int start = ((Json.JNumber) object.member("start")).value().intValueExact();
                int end = ((Json.JNumber) object.member("end")).value().intValueExact();
                put(object, "sha256", new Json.JString(NativeArtifact.sha256(Arrays.copyOfRange(sources.get(object.member("source").text()), start, end))));
            }
            for (Json value : object.members().values()) refreshLocators(value, sources);
        }
        else if (node instanceof Json.JArray array) for (Json value : array.elements()) refreshLocators(value, sources);
    }

    private static void corrupt(Json m, Json turn, Json second, String fault) {
        switch (fault) {
            case "sealed", "stdoutNaturalEof", "stderrNaturalEof", "observedResourcesTerminated" -> put(m, fault, new Json.JBool(false));
            case "overflow" -> put(m, fault, new Json.JBool(true));
            case "version" -> put(m, "cliVersion", new Json.JString("2.1.317"));
            case "session" -> put(m, "nativeSessionId", new Json.JString("wrong"));
            case "prefix-hash" -> put(turn.member("nativePrefixAfter"), "sha256", new Json.JString("0".repeat(64)));
            case "prefix-extent" -> put(turn.member("nativePrefixAfter"), "bytes", new Json.JNumber("99999"));
            case "swapped-prompt" -> put(turn, "prompt", second.member("prompt"));
            case "swapped-result" -> put(turn, "result", second.member("result"));
            case "reused-native" -> put(second, "nativeRecords", turn.member("nativeRecords"));
            case "missing-result" -> put(turn, "stdoutRecords", new Json.JArray(List.of()));
            case "wrong-execution" -> put(second, "executionId", turn.member("executionId"));
            case "wrong-decision" -> put(second, "decisionId", turn.member("decisionId"));
            case "missing-source" -> m.member("sources").elements().remove(0);
            case "extra-source" -> m.member("sources").elements().add(m.member("sources").elements().get(0));
            case "wrong-locator-hash" -> put(turn.member("prompt"), "sha256", new Json.JString("0".repeat(64)));
            case "partial-record" -> put(turn.member("prompt"), "start", new Json.JNumber("1"));
            case "window" -> put(turn.member("endOffsets"), "STDOUT", new Json.JNumber("0"));
            case "source-hash" -> put(m.member("sources").elements().get(0), "sha256", new Json.JString("0".repeat(64)));
            case "dropped" -> put(m.member("sources").elements().get(0), "dropped", new Json.JNumber("1"));
            case "unassigned-execution" -> m.member("unassigned").elements().add(turn.member("prompt"));
            default -> throw new AssertionError(fault);
        }
    }
}
