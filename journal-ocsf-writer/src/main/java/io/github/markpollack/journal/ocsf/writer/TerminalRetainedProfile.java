package io.github.markpollack.journal.ocsf.writer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.ocsf.writer.TerminalValues.*;

/** Producer-side validation of retained projection provenance. The TCK checks it independently. */
final class TerminalRetainedProfile {
    private TerminalRetainedProfile() { }

    static void validate(TerminalContract.Input input, Path manifest) throws IOException {
        Jv acquisition = Jv.parse(Files.readString(manifest));
        boolean retained = TerminalContract.RETAINED_PROFILE.equals(text(Jv.parse(input.envelope()), "profile"));
        Jv generation = acquisition.member("retained_generation");
        if (!retained) { require(generation == null, "retained generation requires its frozen initial profile"); return; }
        require(generation instanceof Jv.Obj, "retained generation declaration absent");
        header(generation, input.decision());
        equal(generation, "nativeSessionId", text(acquisition.member("run"), "session_uid"));
        Path base = manifest.getParent();
        require(Jv.write(generation).equals(Jv.write(Jv.parse(Files.readString(base.resolve("generation/retained-generation.json"))))), "retained original manifest differs");
        Map<String, byte[]> sources = originals(generation, base);
        Set<String> executions = new HashSet<>(), decisions = new HashSet<>(), prompts = new HashSet<>();
        Jv selected = null;
        for (Jv turn : array(generation, "turns")) {
            require(executions.add(text(turn, "executionId")) && decisions.add(text(turn, "decisionId")), "retained turn identity reused");
            require(prompts.add(Jv.write(record(sources, turn.member("prompt")).member("message"))), "retained prompt duplicated");
            prefixes(turn, sources.get("TRANSCRIPT"));
            if (text(turn, "executionId").equals(text(input.decision(), "executionId"))) selected = turn;
        }
        require(selected != null, "retained execution absent"); equal(selected, "decisionId", text(input.decision(), "decisionId"));
        equal(acquisition, "retained_execution_id", text(input.decision(), "executionId"));
        projections(generation, selected, sources, base);
    }

    private static void header(Jv generation, Jv decision) {
        equal(generation, "kind", "journal-retained-generation/1"); equal(generation, "cliVersion", "2.1.316");
        equal(generation, "logicalSessionId", text(decision, "sessionId")); equal(generation, "connectionId", text(decision, "connectionId"));
        equal(decision.member("outcome"), "kind", "SUCCESS");
        for (String key : List.of("sealed", "observedResourcesTerminated", "stdoutNaturalEof", "stderrNaturalEof"))
            require(generation.member(key) instanceof Jv.Bool b && b.value(), "retained generation not sealed/EOF");
        require(generation.member("overflow") instanceof Jv.Bool b && !b.value(), "retained overflow");
        require(array(generation, "turns").size() == 2, "retained profile requires exactly two turns");
    }

    private static Map<String, byte[]> originals(Jv generation, Path base) throws IOException {
        Map<String, byte[]> sources = new HashMap<>();
        for (Jv source : array(generation, "sources")) {
            Path path = base.resolve("generation").resolve(relative(text(source, "path")));
            byte[] bytes = Files.readAllBytes(path); equal(source, "sha256", SchemaPin.sha256(bytes));
            require(number(source, "size") == bytes.length && number(source, "observed") == bytes.length && number(source, "dropped") == 0, "retained original extent/drop mismatch");
            require(sources.put(text(source, "source"), bytes) == null, "retained original repeated");
        }
        require(sources.keySet().equals(Set.of("STDIN", "STDOUT", "STDERR", "TRANSCRIPT")), "retained original population differs");
        return sources;
    }

    private static void prefixes(Jv turn, byte[] nativeBytes) {
        long before = prefix(turn.member("nativePrefixBefore"), nativeBytes), after = prefix(turn.member("nativePrefixAfter"), nativeBytes);
        require(before <= after, "retained prefix regressed");
        for (Jv locator : array(turn, "nativeRecords"))
            require(number(locator, "start") >= before && number(locator, "end") <= after, "native member outside observed prefixes");
    }

    private static long prefix(Jv prefix, byte[] bytes) {
        long count = number(prefix, "bytes"); require(count >= 0 && count <= bytes.length, "retained prefix outside original");
        equal(prefix, "sha256", SchemaPin.sha256(Arrays.copyOf(bytes, (int) count))); return count;
    }

    private static void projections(Jv generation, Jv turn, Map<String, byte[]> sources, Path base) throws IOException {
        for (String source : sources.keySet()) {
            List<Jv> locators = locators(generation, turn, source, sources);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Jv locator : locators) { equal(locator, "source", source); output.writeBytes(extent(sources, locator)); }
            Path path = base.resolve("turn/" + source.toLowerCase(java.util.Locale.ROOT) + ".jsonl");
            require(Arrays.equals(output.toByteArray(), Files.readAllBytes(path)), "retained projection differs from original membership");
        }
    }

    private static List<Jv> locators(Jv generation, Jv turn, String source, Map<String, byte[]> sources) {
        if (source.equals("STDIN")) return List.of(turn.member("prompt"));
        if (source.equals("TRANSCRIPT")) return array(turn, "nativeRecords");
        if (source.equals("STDERR")) return List.of();
        List<Jv> records = new java.util.ArrayList<>(array(turn, "stdoutRecords"));
        long first = records.stream().mapToLong(r -> number(r, "start")).min().orElseThrow();
        Jv init = array(generation, "shared").stream().filter(r -> "STDOUT".equals(text(r, "source")) && number(r, "end") <= first)
                .filter(r -> "system".equals(text(record(sources, r), "type"))).max(Comparator.comparingLong(r -> number(r, "start"))).orElseThrow();
        records.add(init); records.sort(Comparator.comparingLong(r -> number(r, "start"))); return records;
    }

    private static Jv record(Map<String, byte[]> sources, Jv locator) { return Jv.parse(new String(extent(sources, locator), StandardCharsets.UTF_8)); }
    private static byte[] extent(Map<String, byte[]> sources, Jv locator) {
        byte[] bytes = sources.get(text(locator, "source")); require(bytes != null, "unknown retained locator source");
        long start = number(locator, "start"), end = number(locator, "end");
        require(start >= 0 && start < end && end <= bytes.length, "retained locator outside original");
        require((start == 0 || bytes[(int) start - 1] == '\n') && bytes[(int) end - 1] == '\n', "retained locator splits record");
        byte[] selected = Arrays.copyOfRange(bytes, (int) start, (int) end); equal(locator, "sha256", SchemaPin.sha256(selected)); return selected;
    }
    private static void require(boolean pass, String reason) { if (!pass) throw new IllegalArgumentException(reason); }
}
