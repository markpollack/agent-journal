package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One final assembly: the profile, which acquired artifact fills which of its source roles (with the origin it was
 * acquired from, the source's import identity together with its bytes), and the lineage of original packages the
 * acquisition also produced (a provisional live package, kept unchanged).
 *
 * @param profile the reviewed source profile
 * @param bindings each declared artifact with its role and acquisition origin
 * @param lineage the original packages (path, events.jsonl sha256, state), or an empty array
 */
public record AssemblyPlan(AssemblyProfile profile, List<Binding> bindings, Jv lineage) {

    /**
     * @param artifact the artifact name the writer declares
     * @param role the profile's source role it fills
     * @param origin where it was acquired (a locator such as the native session directory, the stdout copy, the OTel file);
     *        replaying the same origin with the same bytes is the same import
     */
    public record Binding(String artifact, String role, String origin) {
    }

    /** Artifact to binding. */
    public Map<String, Binding> byArtifact() {
        Map<String, Binding> out = new TreeMap<>();
        bindings.forEach(b -> out.put(b.artifact(), b));
        return out;
    }

    /** The precedence rank of an artifact (its role's position in the profile's precedence; unknown last). */
    public int rank(String artifact) {
        Binding b = byArtifact().get(artifact);
        int i = b == null ? -1 : profile.precedence().indexOf(b.role());
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
