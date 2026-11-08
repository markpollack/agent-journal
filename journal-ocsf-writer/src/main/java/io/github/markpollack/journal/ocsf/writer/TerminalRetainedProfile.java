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

/** Producer-side validation of retained projection provenance. The TCK checks it independently.
 * The fresh-start profile is selected only by its own envelope profile: it admits the tagged ABSENT first
 * before-prefix and requires the copied observation journal and fresh-start/identity members; the frozen
 * retained profile refuses that form, so neither kind can be read as the other.
 */
final class TerminalRetainedProfile {
    private static final String FRESH_KIND = "journal-retained-fresh-generation/1";
    private TerminalRetainedProfile() { }

    static void validate(TerminalContract.Input input, Path manifest) throws IOException {
        Jv acquisition = Jv.parse(Files.readString(manifest));
        String wire = text(Jv.parse(input.envelope()), "profile"), profile = ClaudeSdkFamily.contract(wire);
        boolean fresh = TerminalContract.FRESH_RETAINED_PROFILES.contains(profile), retained = fresh || TerminalContract.RETAINED_PROFILE.equals(profile);
        Jv generation = acquisition.member("retained_generation");
        if (!retained) { require(generation == null, "retained generation requires its frozen initial profile"); return; }
        require(generation instanceof Jv.Obj, "retained generation declaration absent");
        header(generation, input.decision(), fresh, !fresh || TerminalContract.FRESH_RETAINED_PROFILE.equals(profile), wire);
        equal(generation, "nativeSessionId", text(acquisition.member("run"), "session_uid"));
        Path base = manifest.getParent();
        String name = fresh ? "generation/retained-fresh-generation.json" : "generation/retained-generation.json";
        require(Jv.write(generation).equals(Jv.write(Jv.parse(Files.readString(base.resolve(name))))), "retained original manifest differs");
        if (fresh) freshness(acquisition, generation, base, wire); else require(acquisition.member("retained_profile") == null, "frozen retained profile carries no fresh profile");
        Map<String, byte[]> sources = originals(generation, base);
        Jv selected = turns(generation, sources, input.decision(), fresh);
        equal(acquisition, "retained_execution_id", text(input.decision(), "executionId"));
        projections(generation, selected, sources, base);
    }

    private static Jv turns(Jv generation, Map<String, byte[]> sources, Jv decision, boolean fresh) {
        Set<String> executions = new HashSet<>(), decisions = new HashSet<>(), prompts = new HashSet<>();
        Jv selected = null; boolean first = true;
        for (Jv turn : array(generation, "turns")) {
            require(executions.add(text(turn, "executionId")) && decisions.add(text(turn, "decisionId")), "retained turn identity reused");
            require(prompts.add(Jv.write(record(sources, turn.member("prompt")).member("message"))), "retained prompt duplicated");
            prefixes(turn, sources.get("TRANSCRIPT"), fresh && first); first = false;
            if (text(turn, "executionId").equals(text(decision, "executionId"))) selected = turn;
        }
        require(selected != null, "retained execution absent"); equal(selected, "decisionId", text(decision, "decisionId"));
        sequential(array(generation, "turns"));
        return selected;
    }

    /** Adjacent turns never overlap in stream windows or native boundaries (independent review finding 1, 2026-11-05: every pair, not only the selected turn). */
    private static void sequential(List<Jv> turns) {
        for (int i = 1; i < turns.size(); i++) {
            Jv first = turns.get(i - 1), second = turns.get(i);
            for (String stream : List.of("STDIN", "STDOUT", "STDERR"))
                require(number(first.member("endOffsets"), stream) <= number(second.member("startOffsets"), stream), "retained observation windows overlap");
            require(number(first.member("nativePrefixAfter"), "bytes") <= number(second.member("nativePrefixBefore"), "bytes"), "retained native boundaries overlap");
        }
    }

    private static void header(Jv generation, Jv decision, boolean fresh, boolean bounded, String terminalProfile) {
        equal(generation, "kind", fresh ? FRESH_KIND : "journal-retained-generation/1");
        String version = text(generation, "cliVersion");
        require(ClaudeSdkFamily.admittedUnder(terminalProfile, version), "Claude Code CLI version " + version + " is not admitted under terminal profile " + terminalProfile);
        equal(generation, "logicalSessionId", text(decision, "sessionId")); equal(generation, "connectionId", text(decision, "connectionId"));
        equal(decision.member("outcome"), "kind", "SUCCESS");
        for (String key : List.of("sealed", "observedResourcesTerminated", "stdoutNaturalEof", "stderrNaturalEof"))
            require(generation.member(key) instanceof Jv.Bool b && b.value(), "retained generation not sealed/EOF");
        require(generation.member("overflow") instanceof Jv.Bool b && !b.value(), "retained overflow");
        require(!array(generation, "turns").isEmpty(), "retained generation without turns");
        require(!bounded || array(generation, "turns").size() == 2, "frozen retained profile requires exactly two turns");
    }

    private static void freshness(Jv acquisition, Jv generation, Path base, String profile) throws IOException {
        equal(acquisition, "retained_profile", profile);
        require(generation.member("freshStart") instanceof Jv.Obj && generation.member("observedNativeIdentity") instanceof Jv.Obj, "fresh-start evidence absent");
        equal(generation.member("freshStart"), "requestedSessionId", text(generation, "nativeSessionId"));
        equal(generation.member("observedNativeIdentity"), "reportedSessionId", text(generation, "nativeSessionId"));
        Jv journal = generation.member("observations"); require(journal instanceof Jv.Obj, "observation journal absent");
        byte[] bytes = Files.readAllBytes(base.resolve("generation").resolve(relative(text(journal, "path"))));
        equal(journal, "sha256", SchemaPin.sha256(bytes)); require(number(journal, "size") == bytes.length, "observation journal extent differs");
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

    private static void prefixes(Jv turn, byte[] nativeBytes, boolean absentBefore) {
        long before = absentBefore ? absent(turn.member("nativePrefixBefore")) : prefix(turn.member("nativePrefixBefore"), nativeBytes);
        long after = prefix(turn.member("nativePrefixAfter"), nativeBytes);
        require(before <= after, "retained prefix regressed");
        for (Jv locator : array(turn, "nativeRecords"))
            require(number(locator, "start") >= before && number(locator, "end") <= after, "native member outside observed prefixes");
    }

    private static long absent(Jv observation) {
        require(observation instanceof Jv.Obj o && o.members().size() == 2, "fresh first prefix must be the tagged absence");
        equal(observation, "state", "ABSENT"); equal(observation, "observation", "before-first-dispatch"); return 0;
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
