package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
import static io.github.markpollack.journal.tck.ocsf.RetainedSource.require;

/** Independent comparison of per-turn projections with their entire retained-generation originals. */
public final class RetainedAcquisition {
    private RetainedAcquisition() { }
    public static final String PROFILE = "client-terminal-claude-retained-2.1.316/1";

    /** Checks a selected acquisition without a writer, Client, receiver or original ACK implementation. */
    public static void validate(Json acquisition, Path base) throws IOException {
        Json declared = acquisition.member("retained_generation");
        Json original = NeutralJson.readDocument(base.resolve("generation/retained-generation.json"));
        require(Json.sameValue(declared, original), "retained generation declaration changed");
        RetainedGeneration.validate(original, base.resolve("generation"));
        String execution = string(acquisition, "acquisition_uid");
        Json turn = RetainedGeneration.turn(original, execution);
        expect(acquisition, "retained_execution_id", execution);
        expect(acquisition.member("run"), "run_uid", execution);
        expect(acquisition.member("run"), "session_uid", string(original, "nativeSessionId"));
        expect(acquisition.member("process"), "connection_uid", string(original, "connectionId"));
        require(acquisition.member("complete") instanceof Json.JBool b && b.value(), "retained acquisition is incomplete");
        require(array(acquisition, "turns").size() == 1, "projection turn population differs");
        expect(array(acquisition, "turns").get(0), "invocation_uid", string(turn, "executionId"));
        compareSources(acquisition, original, base, execution);
    }

    private static void compareSources(Json acquisition, Json generation, Path base, String execution) throws IOException {
        Map<String, Json> inventory = new HashMap<>();
        for (Json source : array(acquisition, "sources")) require(inventory.put(string(source, "path"), source) == null, "projection path repeated");
        Set<String> expected = new HashSet<>();
        for (Json source : array(generation, "sources")) {
            String original = "generation/" + string(source, "path"); expected.add(original);
            match(inventory.get(original), base.resolve(original), null, "generation-" + string(source, "source"), execution);
            String projected = projectionPath(string(source, "source")); expected.add(projected);
            byte[] bytes = RetainedGeneration.projection(generation, execution, string(source, "source"), base.resolve("generation"));
            match(inventory.get(projected), base.resolve(projected), bytes, role(string(source, "source")), execution);
        }
        String manifest = "generation/retained-generation.json"; expected.add(manifest);
        match(inventory.get(manifest), base.resolve(manifest), null, "generation-manifest", execution);
        require(expected.equals(inventory.keySet()), "retained acquisition source population differs");
    }

    private static void match(Json source, Path path, byte[] expected, String role, String execution) throws IOException {
        require(source != null, "retained source missing"); expect(source, "role", role); expect(source, "acquisition", execution);
        expect(source, "state", "acquired"); expect(source, "end", "EOF");
        require(path.toRealPath().equals(path.toAbsolutePath().normalize()), "retained projection symlink");
        byte[] actual = Files.readAllBytes(path);
        expect(source, "sha256", NativeArtifact.sha256(actual));
        require(number(source, "bytes") == actual.length, "retained projection extent differs");
        require(expected == null || java.util.Arrays.equals(expected, actual), "retained projection bytes differ from original locators");
        boolean archive = !Set.of("stream", "transcript").contains(role);
        require(source.member("archive_only") instanceof Json.JBool b && b.value() == archive, "retained source role changed");
    }

    /** Fixed derived paths make a caller unable to substitute a different projection filename. */
    public static String projectionPath(String source) { return "turn/" + source.toLowerCase(java.util.Locale.ROOT) + ".jsonl"; }
    /** Existing assembly role of one finite projection; whole-generation sources remain archive-only. */
    public static String role(String source) { return switch (source) { case "STDIN" -> "stdin"; case "STDOUT" -> "stream"; case "STDERR" -> "stderr"; case "TRANSCRIPT" -> "transcript"; default -> throw new IllegalArgumentException("unknown retained source"); }; }
}
