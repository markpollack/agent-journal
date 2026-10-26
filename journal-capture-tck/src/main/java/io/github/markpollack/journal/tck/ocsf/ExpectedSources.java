package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The independently frozen expected-source manifest of a run: which native artifacts the selected run consists of,
 * which profile interprets each, its frozen hash and extent (bytes, records), whether it is required, and which
 * split (child) cores the run is expected to declare. The oracle takes its verification denominator from this
 * document, never from the candidate package's own {@code sources[]}: an omitted, duplicated, relabelled or
 * re-scoped candidate declaration cannot reduce what is judged.
 *
 * <p>The manifest is written when the inputs are frozen (from the native files' hashes and the capture's
 * acquisition record), before any package is judged, and is not derived from the candidate. Layout
 * ({@code kind} {@value #KIND}, {@code version} {@value #VERSION}):
 *
 * <pre>
 * {"kind": "agent-journal/expected-sources", "version": "0.1", "run_uid": "...",
 *  "complete": true,
 *  "frozen": {"at": "...", "basis": "..."},
 *  "sources": [{"artifact": "stream.jsonl", "profile": "claude_stream", "sha256": "...", "bytes": 1, "records": 1, "required": true}],
 *  "children": [{"spawn_tool_call_uid": "...", "artifact": "stream.jsonl", "owner_path": "parent_tool_use_id"}]}
 * </pre>
 *
 * {@code complete} states that the frozen set is the whole run (every source acquired to its natural end); a prefix
 * or interrupted capture is described with {@code complete: false}.
 */
public final class ExpectedSources {

    public static final String KIND = "agent-journal/expected-sources";
    public static final String VERSION = "0.1";

    /** One expected artifact. {@code sha256}, {@code bytes} and {@code records} are the frozen extent. */
    public record Source(String artifact, String profile, String sha256, Integer bytes, Integer records, boolean required, String expectedCompatibility) {
        public Source(String artifact, String profile, String sha256, Integer bytes, Integer records, boolean required) {
            this(artifact, profile, sha256, bytes, records, required, null);
        }

        /** Whether the frozen manifest expects this source's CLI version, profile or route to match no compatibility entry (an untested-version scenario). */
        public boolean expectsNoCompatibilityMatch() {
            return "no_match".equals(expectedCompatibility);
        }
    }

    /** One expected split core: the owner value under {@code ownerPath} of {@code artifact} that the child owns. */
    public record Child(String spawnToolCallUid, String artifact, String ownerPath) {
    }

    private final Json json;
    private final List<Source> sources = new ArrayList<>();
    private final List<Child> children = new ArrayList<>();

    private ExpectedSources(Json json) {
        this.json = json;
        for (Json s : json.member("sources") == null ? List.<Json>of() : json.member("sources").elements()) {
            Json req = s.member("required");
            sources.add(new Source(SchemaCatalog.text(s, "artifact"), SchemaCatalog.text(s, "profile"), SchemaCatalog.text(s, "sha256"),
                    Event.integer(s.member("bytes")), Event.integer(s.member("records")), !(req instanceof Json.JBool b) || b.value(), SchemaCatalog.text(s, "compatibility")));
        }
        for (Json c : json.member("children") == null ? List.<Json>of() : json.member("children").elements()) {
            children.add(new Child(SchemaCatalog.text(c, "spawn_tool_call_uid"), SchemaCatalog.text(c, "artifact"), SchemaCatalog.text(c, "owner_path")));
        }
    }

    public static ExpectedSources parse(Json json) {
        if (!KIND.equals(SchemaCatalog.text(json, "kind"))) {
            throw new IllegalArgumentException("not an expected-sources manifest: kind " + SchemaCatalog.text(json, "kind"));
        }
        if (!VERSION.equals(SchemaCatalog.text(json, "version"))) {
            throw new IllegalArgumentException("expected-sources manifest version " + SchemaCatalog.text(json, "version") + "; this oracle implements " + VERSION);
        }
        ExpectedSources e = new ExpectedSources(json);
        e.validateNames();
        return e;
    }

    private void validateNames() {
        List<String> names = new ArrayList<>();
        for (Source s : sources) {
            if (s.artifact() == null || s.profile() == null) {
                throw new IllegalArgumentException("every expected source names an artifact and a profile");
            }
            if (names.contains(s.artifact())) {
                throw new IllegalArgumentException("expected source " + s.artifact() + " is listed twice");
            }
            names.add(s.artifact());
        }
    }

    public static ExpectedSources read(Path file) {
        return parse(NeutralJson.readDocument(file));
    }

    /** The manifest at {@code dir/expected-sources.json}, or {@code null} when none exists. */
    public static ExpectedSources beside(Path dir) {
        Path f = dir.resolve(FILE_NAME);
        return Files.isRegularFile(f) ? read(f) : null;
    }

    public static final String FILE_NAME = "expected-sources.json";

    public Json json() {
        return json;
    }

    /** The independently pinned source meaning binding, when the acquisition manifest supplies one. */
    public Json binding(String artifact) {
        for (Json source : json.member("sources").elements()) {
            if (artifact.equals(SchemaCatalog.text(source, "artifact"))) {
                return source.member("binding");
            }
        }
        return null;
    }

    public String runUid() {
        return SchemaCatalog.text(json, "run_uid");
    }

    /** Whether the frozen set is a complete run (every source to its natural end), the default. */
    public boolean complete() {
        Json c = json.member("complete");
        return !(c instanceof Json.JBool b) || b.value();
    }

    public List<Source> sources() {
        return List.copyOf(sources);
    }

    public Source source(String artifact) {
        for (Source s : sources) {
            if (s.artifact().equals(artifact)) {
                return s;
            }
        }
        return null;
    }

    public List<Child> children() {
        return List.copyOf(children);
    }

    public Child child(String spawnToolCallUid) {
        for (Child c : children) {
            if (c.spawnToolCallUid() != null && c.spawnToolCallUid().equals(spawnToolCallUid)) {
                return c;
            }
        }
        return null;
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Source s : sources) {
            sb.append(sb.length() == 0 ? "" : ", ").append(s.artifact()).append(" (").append(s.profile()).append(")");
        }
        return sb.toString();
    }
}
