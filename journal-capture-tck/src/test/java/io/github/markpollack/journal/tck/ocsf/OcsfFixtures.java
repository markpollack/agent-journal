package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Test support for the committed OCSF fixtures under {@code src/test/resources/ocsf/fixtures/<name>/}: a working copy
 * ({@code native/} holds the native artifacts, {@code package/} the self-describing core with its children), and
 * small JSON Lines edits that re-pin the events hash so a mutation is judged as a producer defect, not as tampering
 * (tampering has its own test).
 */
final class OcsfFixtures {

    static final String CODEX = "syn-codex-counterexamples";
    static final String CHILD = "claude-child-2.1.299";
    static final String SPAWN = "toolu_01BnEuCUmZ2Vu1LpADzksmrJ";

    private OcsfFixtures() {
    }

    static Path copy(String name, Path work) {
        try {
            Path source = Path.of(OcsfFixtures.class.getResource("/ocsf/fixtures/" + name + "/spec.json").toURI()).getParent();
            Path target = work.resolve(name);
            try (Stream<Path> s = Files.walk(source)) {
                for (Path p : s.toList()) {
                    Path t = target.resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(t);
                    }
                    else {
                        Files.createDirectories(t.getParent());
                        Files.copy(p, t);
                    }
                }
            }
            return target;
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Path pkg(Path fixture) {
        return fixture.resolve("package");
    }

    static Path natives(Path fixture) {
        return fixture.resolve("native");
    }

    static Path child(Path fixture) {
        return pkg(fixture).resolve("children").resolve(SPAWN);
    }

    static OcsfPackage open(Path fixture) {
        return PackageReader.open(pkg(fixture).resolve("events.jsonl"));
    }

    static OracleReport verify(Path fixture) {
        return OcsfOracle.verify(open(fixture), natives(fixture));
    }

    static List<Json> lines(Path file) {
        List<Json> out = new ArrayList<>();
        for (NeutralJson.Line line : NeutralJson.readLines(file)) {
            out.add(line.json());
        }
        return out;
    }

    static void writeLines(Path file, List<Json> lines) {
        StringBuilder sb = new StringBuilder();
        for (Json line : lines) {
            sb.append(NeutralJson.write(line)).append('\n');
        }
        write(file, sb.toString());
    }

    static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Edits {@code events.jsonl} of a core and re-pins {@code package.events_jsonl_sha256} in its manifest. */
    static void editEvents(Path core, Consumer<List<Json>> edit) {
        Path file = core.resolve("events.jsonl");
        List<Json> events = lines(file);
        edit.accept(events);
        writeLines(file, events);
        repin(core);
    }

    /** Edits {@code events.jsonl} without re-pinning the hash (tampering). */
    static void tamperEvents(Path core, Consumer<List<Json>> edit) {
        Path file = core.resolve("events.jsonl");
        List<Json> events = lines(file);
        edit.accept(events);
        writeLines(file, events);
    }

    static void repin(Path core) {
        try {
            String sha = NativeArtifact.sha256(Files.readAllBytes(core.resolve("events.jsonl")));
            editManifest(core, run -> obj(run.get("package")).members().put("events_jsonl_sha256", str(sha)));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void editManifest(Path core, Consumer<Json.JObject> edit) {
        Path file = core.resolve("run.json");
        Json.JObject run = (Json.JObject) NeutralJson.readDocument(file);
        edit.accept(run);
        write(file, NeutralJson.write(run) + "\n");
    }

    /** Edits a native artifact in place (a labelled fixture of the test's own making). */
    static void editNative(Path fixture, String artifact, Consumer<List<Json>> edit) {
        Path file = natives(fixture).resolve(artifact);
        List<Json> lines = lines(file);
        edit.accept(lines);
        writeLines(file, lines);
    }

    static Json.JObject obj(Json json) {
        return (Json.JObject) json;
    }

    static Json str(String value) {
        return new Json.JString(value);
    }

    static Json num(String text) {
        return new Json.JNumber(text);
    }

    static Json copy(Json json) {
        return NeutralJson.parse(NeutralJson.write(json));
    }

    static boolean isEvent(Json e, String className, int activityId) {
        Json uid = e.member("class_uid");
        String name = switch (uid == null ? "" : uid.scalarText()) {
            case "90111001" -> "prompt_turn";
            case "90111002" -> "model_response";
            case "90111003" -> "tool_call";
            case "90111004" -> "source_record";
            case "90111005" -> "session_context";
            default -> "";
        };
        return className.equals(name) && e.member("activity_id") != null && Integer.toString(activityId).equals(e.member("activity_id").scalarText());
    }

    static int recordIndex(Json e) {
        return Integer.parseInt(e.path("source_ref", "record_index").scalarText());
    }

    static Json first(List<Json> events, Predicate<Json> which) {
        for (Json e : events) {
            if (which.test(e)) {
                return e;
            }
        }
        throw new IllegalArgumentException("no such event");
    }

    static List<Json> all(List<Json> events, Predicate<Json> which) {
        List<Json> out = new ArrayList<>();
        for (Json e : events) {
            if (which.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /** The event of a class/activity at a native record index (first artifact wins; use the artifact form for a multi-source core). */
    static Json at(List<Json> events, String className, int activityId, int recordIndex) {
        return first(events, e -> isEvent(e, className, activityId) && recordIndex(e) == recordIndex);
    }

    /** The event of a class/activity at a native record index of one artifact. */
    static Json at(List<Json> events, String artifact, String className, int activityId, int recordIndex) {
        return first(events, e -> isEvent(e, className, activityId) && recordIndex(e) == recordIndex && artifact.equals(e.path("source_ref", "artifact").text()));
    }

    /** Every difference where the expected value is not matched by the actual one (expected is a subset pattern). */
    static List<String> subsetDifferences(Json expected, Json actual, String path) {
        List<String> out = new ArrayList<>();
        if (expected instanceof Json.JObject eo) {
            if (!(actual instanceof Json.JObject ao)) {
                out.add(path + ": expected object, actual " + Json.kindOf(actual));
                return out;
            }
            for (var m : eo.members().entrySet()) {
                if (m.getKey().startsWith("_")) {
                    continue; // authoring notes in the expected file
                }
                if (!ao.members().containsKey(m.getKey())) {
                    out.add(path + "." + m.getKey() + ": missing");
                }
                else {
                    out.addAll(subsetDifferences(m.getValue(), ao.get(m.getKey()), path + "." + m.getKey()));
                }
            }
        }
        else if (expected instanceof Json.JArray ea) {
            if (!(actual instanceof Json.JArray aa) || aa.elements().size() != ea.elements().size()) {
                out.add(path + ": expected " + ea.elements().size() + " element(s), actual " + (actual instanceof Json.JArray a ? a.elements().size() : Json.kindOf(actual)));
                return out;
            }
            for (int i = 0; i < ea.elements().size(); i++) {
                out.addAll(subsetDifferences(ea.elements().get(i), aa.elements().get(i), path + "[" + i + "]"));
            }
        }
        else if (!Json.sameValue(expected, actual)) {
            out.add(path + ": expected " + NeutralJson.write(expected) + ", actual " + (actual == null ? "missing" : NeutralJson.write(actual)));
        }
        return out;
    }
}
