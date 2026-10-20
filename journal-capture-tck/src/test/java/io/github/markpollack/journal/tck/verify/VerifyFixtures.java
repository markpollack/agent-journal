package io.github.markpollack.journal.tck.verify;

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

/** Test support: a working copy of a committed fixture (oracle plus candidate) and small JSON Lines edits on it. */
final class VerifyFixtures {

    private VerifyFixtures() {
    }

    /** A copy of {@code src/test/resources/verify/<name>} under {@code work}. */
    static Path copy(String name, Path work) {
        try {
            Path source = Path.of(VerifyFixtures.class.getResource("/verify/" + name + "/stream.jsonl").toURI()).getParent();
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

    static Path stream(Path fixture) {
        return fixture.resolve("stream.jsonl");
    }

    static Path journal(Path fixture) {
        return fixture.resolve("journal");
    }

    static Path rootRun(Path fixture) {
        return CandidatePackage.findRootRun(journal(fixture));
    }

    /** The run directory of the sub-agent spawned by {@code spawnToolUseId}. */
    static Path childRun(Path fixture, String spawnToolUseId) {
        try (Stream<Path> s = Files.walk(journal(fixture))) {
            for (Path runJson : s.filter(p -> p.getFileName().toString().equals("run.json")).toList()) {
                Json run = NeutralJson.readDocument(runJson);
                Json spawn = run.path("config", ClaudeStreamContract.CONFIG_SPAWN_TOOL_USE_ID);
                if (spawn != null && spawnToolUseId.equals(spawn.text())) {
                    return runJson.getParent();
                }
            }
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("no child run for " + spawnToolUseId);
    }

    static VerificationReport verify(Path fixture) {
        return ClaudeStreamVerifier.verify(ClaudeStreamOracle.read(stream(fixture)), CandidatePackage.read(rootRun(fixture)));
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
        try {
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Edits {@code events.jsonl} of a run in place. */
    static void editEvents(Path runDir, Consumer<List<Json>> edit) {
        Path file = runDir.resolve("events.jsonl");
        List<Json> events = lines(file);
        edit.accept(events);
        writeLines(file, events);
    }

    /** Edits {@code run.json} of a run in place. */
    static void editRun(Path runDir, Consumer<Json.JObject> edit) {
        Path file = runDir.resolve("run.json");
        Json.JObject run = (Json.JObject) NeutralJson.readDocument(file);
        edit.accept(run);
        try {
            Files.writeString(file, NeutralJson.write(run), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Edits the oracle stream in place (for a labelled fixture of the test's own making). */
    static void editStream(Path fixture, Consumer<List<Json>> edit) {
        List<Json> lines = lines(stream(fixture));
        edit.accept(lines);
        writeLines(stream(fixture), lines);
    }

    static Json.JObject obj(Json json) {
        return (Json.JObject) json;
    }

    static Json.JObject attributes(Json event) {
        return obj(obj(event).get("attributes"));
    }

    static boolean isEvent(Json e, String type) {
        return type.equals(ClaudeStreamOracle.textOf(e, "@type"));
    }

    static boolean isCustom(Json e, String name) {
        return isEvent(e, "custom") && name.equals(ClaudeStreamOracle.textOf(e, "name"));
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

    static Json toolCall(List<Json> events, String id) {
        return first(events, e -> isEvent(e, "tool_call") && id.equals(ClaudeStreamOracle.textOf(e, "id")));
    }

    static Json str(String value) {
        return new Json.JString(value);
    }

    static Json num(String text) {
        return new Json.JNumber(text);
    }

    /** A deep copy through text, so an edit to the copy leaves the original alone. */
    static Json copy(Json json) {
        return NeutralJson.parse(NeutralJson.write(json));
    }
}
