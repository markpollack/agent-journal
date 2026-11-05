package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;
import static io.github.markpollack.journal.tck.ocsf.RetainedSource.require;

/** Independent original-byte validation for successful retained Claude text turns (the frozen kinds and profiles
 * bound a generation to exactly two turns; the ordinary fresh profile admits any sealed turn count).
 * A caller's observed boundary is checked for consistency, never recreated as an observation.
 * This finite experimental profile does not admit tools, children, compaction or reconnect.
 */
public final class RetainedGeneration {
    private RetainedGeneration() { }

    /** Validates every generation member, source byte, prefix and explicit ownership partition. */
    public static void validate(Json manifest, Path directory) throws IOException { validate(manifest, directory, false, true); }

    /** The fresh-start kind shares every byte/membership check; only the first before-prefix may be tagged ABSENT.
     * {@code bounded} is the frozen two-turn rule; the ordinary fresh profile reads any sealed turn count sequentially. */
    static void validate(Json manifest, Path directory, boolean fresh, boolean bounded) throws IOException {
        header(manifest, fresh);
        Map<String, RetainedSource> sources = sources(manifest, directory);
        List<Json> turns = array(manifest, "turns"); require(!turns.isEmpty(), "retained generation without turns");
        require(!bounded || turns.size() == 2, "bounded retained profile requires exactly two turns");
        Set<String> executions = new HashSet<>(), decisions = new HashSet<>(), prompts = new HashSet<>(), uuids = new HashSet<>();
        List<Json> membership = new ArrayList<>(); String previous = null;
        for (Json turn : turns) {
            fields(turn, "executionId decisionId prompt result stdoutRecords nativeRecords nativePrefixBefore nativePrefixAfter startOffsets endOffsets");
            require(executions.add(string(turn, "executionId")) && decisions.add(string(turn, "decisionId")), "duplicate retained execution/decision");
            previous = RetainedTurn.validate(manifest, turn, sources, previous, fresh && previous == null);
            String prompt = rawString(sources.get("STDIN").record(turn.member("prompt")).member("message"), "content");
            require(prompts.add(prompt), "duplicate retained prompt is ambiguous");
            uniqueNative(turn, sources, uuids);
            membership.add(turn.member("prompt")); membership.addAll(array(turn, "stdoutRecords")); membership.addAll(array(turn, "nativeRecords"));
        }
        shared(manifest, sources); membership.addAll(array(manifest, "shared"));
        unassigned(manifest, sources); membership.addAll(array(manifest, "unassigned"));
        partition(sources, membership);
        sequential(turns);
    }

    private static final String MEMBERS = "kind logicalSessionId connectionId nativeSessionId cliVersion captureStart sealed observedResourcesTerminated stdoutNaturalEof stderrNaturalEof overflow sources turns shared unassigned";

    private static void header(Json m, boolean fresh) {
        fields(m, fresh ? MEMBERS + " freshStart observedNativeIdentity observations" : MEMBERS);
        expect(m, "kind", fresh ? FreshRetainedGeneration.KIND : "journal-retained-generation/1"); expect(m, "cliVersion", "2.1.316");
        for (String key : List.of("logicalSessionId", "connectionId", "nativeSessionId", "captureStart")) string(m, key);
        java.time.Instant.parse(string(m, "captureStart"));
        for (String key : List.of("sealed", "observedResourcesTerminated", "stdoutNaturalEof", "stderrNaturalEof"))
            require(m.member(key) instanceof Json.JBool b && b.value(), "retained generation is not sealed/EOF: " + key);
        require(m.member("overflow") instanceof Json.JBool b && !b.value(), "retained generation overflow");
    }

    static Map<String, RetainedSource> sources(Json m, Path base) throws IOException {
        Map<String, RetainedSource> sources = new HashMap<>(); Set<String> paths = new HashSet<>();
        for (Json source : array(m, "sources")) {
            fields(source, "source path origin sha256 size observed dropped");
            require(paths.add(string(source, "path")), "retained path repeated");
            require(sources.put(string(source, "source"), new RetainedSource(source, base)) == null, "retained source repeated");
        }
        require(sources.keySet().equals(Set.of("STDIN", "STDOUT", "STDERR", "TRANSCRIPT")), "unsupported retained source population");
        return sources;
    }

    private static void uniqueNative(Json turn, Map<String, RetainedSource> sources, Set<String> ids) {
        for (Json locator : array(turn, "nativeRecords"))
            require(ids.add(string(sources.get("TRANSCRIPT").record(locator), "uuid")), "native UUID occurs more than once");
    }

    private static void shared(Json m, Map<String, RetainedSource> sources) {
        int init = 0;
        for (Json locator : array(m, "shared")) {
            String source = string(locator, "source");
            require(Set.of("STDIN", "STDOUT").contains(source), "unsupported shared source");
            Json row = sources.get(source).record(locator); String type = string(row, "type");
            if (source.equals("STDOUT") && type.equals("system")) {
                expect(row, "subtype", "init"); expect(row, "session_id", string(m, "nativeSessionId"));
                expect(row, "claude_code_version", "2.1.316"); init++;
            }
            else require(Set.of("control_request", "control_response").contains(type), "execution record cannot be generation shared");
        }
        require(init > 0, "retained generation has no init/version witness");
    }

    private static void unassigned(Json m, Map<String, RetainedSource> sources) {
        for (Json locator : array(m, "unassigned")) {
            expect(locator, "source", "STDERR"); sources.get("STDERR").extent(locator);
        }
    }

    private static void partition(Map<String, RetainedSource> sources, List<Json> locators) {
        for (Map.Entry<String, RetainedSource> source : sources.entrySet()) {
            List<Json> spans = locators.stream().filter(l -> source.getKey().equals(string(l, "source")))
                    .sorted(Comparator.comparingLong(l -> number(l, "start"))).toList();
            long end = 0;
            for (Json locator : spans) {
                source.getValue().extent(locator);
                require(number(locator, "start") == end, "retained membership overlap/gap"); end = number(locator, "end");
            }
            require(end == source.getValue().size(), "retained membership omits original bytes");
        }
    }

    private static void sequential(List<Json> turns) {
        for (int i = 1; i < turns.size(); i++) {
            Json first = turns.get(i - 1), second = turns.get(i);
            for (String stream : List.of("STDIN", "STDOUT", "STDERR"))
                require(number(first.member("endOffsets"), stream) <= number(second.member("startOffsets"), stream), "retained observation windows overlap");
            require(number(first.member("nativePrefixAfter"), "bytes") <= number(second.member("nativePrefixBefore"), "bytes"), "retained native boundaries overlap");
        }
    }

    /** Returns the selected turn by explicit execution identity; array order never substitutes for it. */
    public static Json turn(Json manifest, String executionId) {
        List<Json> matches = array(manifest, "turns").stream().filter(t -> executionId.equals(string(t, "executionId"))).toList();
        require(matches.size() == 1, "retained execution absent/ambiguous"); return matches.get(0);
    }

    /** Produces an exact concatenation of declared record extents, after complete generation validation. */
    public static byte[] projection(Json manifest, String executionId, String source, Path base) throws IOException { return projection(manifest, executionId, source, base, null); }

    /** The original signature: {@code fresh} selects the fresh-start kind under its frozen profile {@value FreshRetainedGeneration#PROFILE}. */
    public static byte[] projection(Json manifest, String executionId, String source, Path base, boolean fresh) throws IOException {
        return projection(manifest, executionId, source, base, fresh ? FreshRetainedGeneration.PROFILE : null);
    }

    /** {@code freshProfile} selects the fresh-start kind under that profile; null reads the frozen retained kind. */
    public static byte[] projection(Json manifest, String executionId, String source, Path base, String freshProfile) throws IOException {
        if (freshProfile != null) FreshRetainedGeneration.validate(manifest, base, freshProfile); else validate(manifest, base);
        Map<String, RetainedSource> originals = sources(manifest, base);
        Json selected = turn(manifest, executionId); List<Json> locators = projectionLocators(manifest, selected, source, originals.get("STDOUT"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Json locator : locators) bytes.writeBytes(originals.get(source).extent(locator));
        return bytes.toByteArray();
    }

    private static List<Json> projectionLocators(Json manifest, Json turn, String source, RetainedSource stdout) {
        if (source.equals("STDIN")) return List.of(turn.member("prompt"));
        if (source.equals("TRANSCRIPT")) return array(turn, "nativeRecords");
        if (source.equals("STDERR")) return List.of();
        require(source.equals("STDOUT"), "unknown projection source");
        List<Json> records = new ArrayList<>(array(turn, "stdoutRecords"));
        long first = records.stream().mapToLong(l -> number(l, "start")).min().orElseThrow();
        Json init = array(manifest, "shared").stream().filter(l -> "STDOUT".equals(string(l, "source")) && number(l, "end") <= first)
                .filter(l -> "system".equals(string(stdout.record(l), "type")))
                .max(Comparator.comparingLong(l -> number(l, "start"))).orElseThrow(() -> new IllegalArgumentException("no prior shared init"));
        records.add(init); records.sort(Comparator.comparingLong(l -> number(l, "start"))); return records;
    }
}
