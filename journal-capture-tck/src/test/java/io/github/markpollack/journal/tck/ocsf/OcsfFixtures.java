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
    static final String CODEX_STREAM = "syn-codex-exec-stream";
    static final String CHILD = "claude-child-2.1.299";
    static final String INTERRUPTED = "claude-child-2.1.299-interrupted";
    static final String SPAWN = "toolu_01BnEuCUmZ2Vu1LpADzksmrJ";
    /** Sanitized real live recordings of ACCEPT-1 (2026-10-21): recorded replay of a live stream, labelled so in each spec and provenance.json. */
    static final String LIVE_FEATURES = "claude-live-2.1.304-features";
    static final String SYN_CHILD_RUNS = "syn-claude-child-runs-2.1.304";
    static final String SYN_AGENT_DEFAULTS = "syn-claude-agent-defaults-2.1.304";
    static final String LIVE_DENIAL = "claude-live-2.1.304-denial";
    static final String LIVE_CANCELLED = "claude-live-2.1.304-cancelled";
    static final String LIVE_CODEX = "codex-live-0.160.1-commands";
    static final String LIVE_CODEX_CANCELLED = "codex-live-0.160.1-cancelled";
    /** Labelled synthetic Codex 0.160.1 exec streams derived from the vendor's exec types and processor tests (FND-1): every item family, and the failed-turn ending. */
    static final String CODEX_FAMILIES = "syn-codex-exec-families-0.160.1";
    static final String CODEX_TURN_FAILED = "syn-codex-exec-turn-failed-0.160.1";

    /** Sanitized live Codex CLI 0.160.1 recordings of the FND-1 live campaign (2026-10-22), replayed through the writer. */
    static final String LIVE_CODEX_FILE_CHANGE = "codex-live-0.160.1-file-change";
    static final String LIVE_CODEX_MCP = "codex-live-0.160.1-mcp";
    static final String LIVE_CODEX_REASONING = "codex-live-0.160.1-reasoning";
    static final String LIVE_CODEX_PLAN = "codex-live-0.160.1-plan";
    static final String LIVE_CODEX_WEB_SEARCH = "codex-live-0.160.1-web-search";
    static final String LIVE_CODEX_COLLAB_WAIT = "codex-live-0.160.1-collab-wait";
    static final String LIVE_CODEX_INTERRUPT_OPEN = "codex-live-0.160.1-interrupt-open";
    static final String LIVE_CODEX_SANDBOX_REFUSAL = "codex-live-0.160.1-sandbox-refusal";
    static final String LIVE_SPAWN = "toolu_01WBvs5eGudfYPm8YvrTYTQK";
    /** Sanitized live Antigravity CLI 1.4.6 recordings of the onboarding campaign (2026-10-23), replayed through the writer. */
    static final String LIVE_AGY_FEATURES = "antigravity-live-1.4.6-features";
    static final String LIVE_AGY_DENIAL = "antigravity-live-1.4.6-denial";
    static final String LIVE_AGY_INTERRUPT_OPEN = "antigravity-live-1.4.6-interrupt-open";
    static final String LIVE_AGY_SUBAGENT = "antigravity-live-1.4.6-subagent";
    static final String LIVE_AGY_MCP_DENIED = "antigravity-live-1.4.6-mcp-denied";
    static final String LIVE_AGY_MCP = "antigravity-live-1.4.6-mcp";
    static final String LIVE_MCP = "claude-live-2.1.304-mcp";
    /** The historical two-file layout, kept unchanged under {@code fixtures-historical/0.2.0-prototype/<name>/package}. */
    static final String HISTORICAL_0_2_0 = "0.2.0-prototype";

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

    /**
     * A working copy of a fixture whose package is the historical two-file layout: the native artifacts of
     * {@code fixtures/<name>/native/} beside the package kept under {@code fixtures-historical/<layout>/<name>/package}.
     */
    static Path copyHistorical(String layout, String name, Path work) {
        Path target = copy(name, work);
        try {
            Path historical = Path.of(OcsfFixtures.class.getResource("/ocsf/fixtures-historical/" + layout + "/" + name + "/spec.json").toURI()).getParent().resolve("package");
            Path current = pkg(target);
            try (Stream<Path> s = Files.walk(current)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
            try (Stream<Path> s = Files.walk(historical)) {
                for (Path p : s.toList()) {
                    Path t = current.resolve(historical.relativize(p).toString());
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

    /** The execution event lines of a file (every line without a top-level {@code declaration} member). */
    static List<Json> eventLines(Path file) {
        List<Json> out = new ArrayList<>();
        for (Json line : lines(file)) {
            if (!DeclaredFile.isDeclaration(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /** The declaration lines of a file, in order. */
    static List<Json> declarationLines(Path file) {
        List<Json> out = new ArrayList<>();
        for (Json line : lines(file)) {
            if (DeclaredFile.isDeclaration(line)) {
                out.add(line);
            }
        }
        return out;
    }

    /**
     * Edits the execution events of a core's {@code events.jsonl} and re-pins the manifest. The edit sees the events
     * only; the file is rewritten with every declaration first (in its original order) and the edited events after,
     * which keeps each declaration before every event that references it whatever the edit moved.
     */
    static void editEvents(Path core, Consumer<List<Json>> edit) {
        Path file = core.resolve("events.jsonl");
        List<Json> declarations = declarationLines(file);
        List<Json> events = eventLines(file);
        edit.accept(events);
        List<Json> all = new ArrayList<>(declarations);
        all.addAll(events);
        writeLines(file, all);
        repin(core);
    }

    /** Edits {@code events.jsonl} without re-pinning the hash (tampering). */
    static void tamperEvents(Path core, Consumer<List<Json>> edit) {
        Path file = core.resolve("events.jsonl");
        List<Json> declarations = declarationLines(file);
        List<Json> events = eventLines(file);
        edit.accept(events);
        List<Json> all = new ArrayList<>(declarations);
        all.addAll(events);
        writeLines(file, all);
    }

    /**
     * Edits the declaration lines of a core's {@code events.jsonl} in place (the events are untouched) and re-pins the
     * manifest. The edit sees the declaration objects of the file itself: altering one alters that line, removing one
     * drops that line, and a declaration added to the list is written before the first event.
     */
    static void editDeclarations(Path core, Consumer<List<Json>> edit) {
        Path file = core.resolve("events.jsonl");
        List<Json> all = lines(file);
        List<Json> declarations = new ArrayList<>();
        for (Json line : all) {
            if (DeclaredFile.isDeclaration(line)) {
                declarations.add(line);
            }
        }
        java.util.Set<Json> original = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        original.addAll(declarations);
        edit.accept(declarations);
        java.util.Set<Json> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        kept.addAll(declarations);
        List<Json> added = new ArrayList<>();
        for (Json d : declarations) {
            if (!original.contains(d)) {
                added.add(d);
            }
        }
        List<Json> out = new ArrayList<>();
        boolean firstEventSeen = false;
        for (Json line : all) {
            if (DeclaredFile.isDeclaration(line)) {
                if (kept.contains(line)) {
                    out.add(line);
                }
                continue;
            }
            if (!firstEventSeen) {
                out.addAll(added);
                firstEventSeen = true;
            }
            out.add(line);
        }
        if (!firstEventSeen) {
            out.addAll(added);
        }
        writeLines(file, out);
        repin(core);
    }

    /** The declaration line with the given uid, or {@code null}. */
    static Json declaration(List<Json> declarations, String uid) {
        for (Json d : declarations) {
            if (uid.equals(SchemaCatalog.text(d.member("declaration"), "uid"))) {
                return d;
            }
        }
        return null;
    }

    /** Re-pins the events hash, count, bytes and persisted prefix after an edit, so the edit is judged as a producer defect rather than tampering. */
    static void repin(Path core) {
        repin(core, true);
    }

    /**
     * Edits the declaration lines like {@link #editDeclarations} but re-pins the manifest without keeping its state and
     * the finalization declaration in step: for provoking exactly that inconsistency.
     */
    static void editDeclarationsWithoutSync(Path core, Consumer<List<Json>> edit) {
        Path file = core.resolve("events.jsonl");
        List<Json> all = lines(file);
        List<Json> declarations = new ArrayList<>();
        for (Json line : all) {
            if (DeclaredFile.isDeclaration(line)) {
                declarations.add(line);
            }
        }
        edit.accept(declarations);
        java.util.Set<Json> kept = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        kept.addAll(declarations);
        List<Json> out = new ArrayList<>();
        for (Json line : all) {
            if (!DeclaredFile.isDeclaration(line) || kept.contains(line)) {
                out.add(line);
            }
        }
        writeLines(file, out);
        repin(core, false);
    }

    /** @param sync whether to keep the finalization declaration in step with the manifest state (see {@link #editManifest}) */
    static void repin(Path core, boolean sync) {
        try {
            byte[] bytes = Files.readAllBytes(core.resolve("events.jsonl"));
            String sha = NativeArtifact.sha256(bytes);
            int count = eventLines(core.resolve("events.jsonl")).size();
            int declarations = declarationLines(core.resolve("events.jsonl")).size();
            Consumer<Json.JObject> pin = run -> {
                Json.JObject pkg = obj(run.get("package"));
                pkg.members().put("events_jsonl_sha256", str(sha));
                pkg.members().put("events_count", num(Integer.toString(count)));
                pkg.members().put("events_bytes", num(Integer.toString(bytes.length)));
                if (pkg.get("declarations_count") != null) {
                    pkg.members().put("declarations_count", num(Integer.toString(declarations)));
                }
                if (pkg.get("persisted_prefix") instanceof Json.JObject prefix) {
                    prefix.members().put("bytes", num(Integer.toString(bytes.length)));
                    prefix.members().put("sha256", str(sha));
                }
            };
            if (sync) {
                editManifest(core, pin);
            }
            else {
                Path file = core.resolve("run.json");
                Json.JObject run = (Json.JObject) NeutralJson.readDocument(file);
                pin.accept(run);
                write(file, NeutralJson.write(run) + "\n");
            }
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
        syncRecordingDeclaration(core, run);
    }

    /**
     * Keeps a standalone file's finalization declaration consistent with the package state a manifest edit produced,
     * as the writer would have left the file: an open or failed writer never wrote a {@code recording} declaration,
     * an interrupted one wrote it with that state. The manifest's hash, byte and prefix pins follow the file.
     */
    private static void syncRecordingDeclaration(Path core, Json.JObject run) {
        Path events = core.resolve("events.jsonl");
        if (!Files.isRegularFile(events)) {
            return;
        }
        List<Json> all = lines(events);
        if (all.isEmpty() || !DeclaredFile.isDeclaration(all.get(0))) {
            return; // historical two-file layout: the manifest alone carries the state
        }
        String state = SchemaCatalog.text(run.member("package"), "state");
        Json rec = declaration(all, "recording:end");
        boolean changed = false;
        if (("RECORDING".equals(state) || "FAILED".equals(state)) && rec != null) {
            all.remove(rec);
            changed = true;
        }
        else if (("INTERRUPTED".equals(state) || "COMPLETED".equals(state)) && rec != null && !state.equals(SchemaCatalog.text(rec.member("recording"), "state"))) {
            obj(rec.member("recording")).members().put("state", str(state));
            changed = true;
        }
        if (!changed) {
            return;
        }
        writeLines(events, all);
        try {
            byte[] bytes = Files.readAllBytes(events);
            Json.JObject pkg = obj(run.get("package"));
            if (pkg.get("events_jsonl_sha256") != null && !pkg.get("events_jsonl_sha256").isNull()) {
                pkg.members().put("events_jsonl_sha256", str(NativeArtifact.sha256(bytes)));
            }
            pkg.members().put("events_bytes", num(Integer.toString(bytes.length)));
            if (pkg.get("declarations_count") != null) {
                pkg.members().put("declarations_count", num(Integer.toString(declarationLines(events).size())));
            }
            if (pkg.get("persisted_prefix") instanceof Json.JObject prefix) {
                prefix.members().put("bytes", num(Integer.toString(bytes.length)));
                prefix.members().put("sha256", str(NativeArtifact.sha256(bytes)));
            }
            write(core.resolve("run.json"), NeutralJson.write(run) + "\n");
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Edits a native artifact in place (a labelled fixture of the test's own making) and re-freezes the expected-source manifest to it. */
    static void editNative(Path fixture, String artifact, Consumer<List<Json>> edit) {
        Path file = natives(fixture).resolve(artifact);
        List<String> raw;
        try {
            raw = Files.readAllLines(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // opaque lines (not one JSON object) are kept verbatim at their position; the edit sees them as null
        List<Json> lines = new ArrayList<>();
        for (String line : raw) {
            Json json;
            try {
                json = NeutralJson.parse(line);
                if (!(json instanceof Json.JObject)) {
                    json = null;
                }
            }
            catch (IllegalArgumentException e) {
                json = null;
            }
            lines.add(json);
        }
        edit.accept(lines);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i) == null ? raw.get(i) : NeutralJson.write(lines.get(i))).append('\n');
        }
        write(file, sb.toString());
        refreeze(fixture);
    }

    /**
     * Re-freezes {@code native/expected-sources.json} to the native artifacts as they are now: the test's own
     * labelled inputs become the frozen denominator. Never used on a candidate package.
     */
    static void refreeze(Path fixture) {
        Path manifest = natives(fixture).resolve(ExpectedSources.FILE_NAME);
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        Json.JObject m = (Json.JObject) NeutralJson.readDocument(manifest);
        for (Json s : m.member("sources").elements()) {
            Path file = natives(fixture).resolve(s.member("artifact").text());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            NativeArtifact art = NativeArtifact.read(file);
            obj(s).members().put("sha256", str(art.sha256()));
            obj(s).members().put("bytes", num(Integer.toString(art.bytes().length)));
            obj(s).members().put("records", num(Integer.toString(art.records().size())));
        }
        write(manifest, NeutralJson.write(m) + "\n");
    }

    /** The frozen manifest of a fixture copy, for tests that edit it. */
    static void editExpected(Path fixture, Consumer<Json.JObject> edit) {
        Path manifest = natives(fixture).resolve(ExpectedSources.FILE_NAME);
        Json.JObject m = (Json.JObject) NeutralJson.readDocument(manifest);
        edit.accept(m);
        write(manifest, NeutralJson.write(m) + "\n");
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
