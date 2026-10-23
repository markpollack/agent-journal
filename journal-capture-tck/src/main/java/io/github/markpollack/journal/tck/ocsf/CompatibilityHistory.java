package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The compatibility selections a package declares for one source, oldest first, as the reader derived them from the
 * file's declarations ({@code compatibility_history}; an ancillary manifest carries only the selection in force). Each
 * selection governs the records after its {@code records_before_selection} until the next one takes effect. Only a
 * tested or in-range selection permits vendor normalization (RULE-9, RULE-10, RULE-33): a record that arrived under
 * {@code version_unknown}, {@code no_match} or {@code conflict} is retained whole and unnormalized, and none of its facts
 * is owed or credited. Read from the saved file alone; no registry is consulted.
 */
public final class CompatibilityHistory {

    /** One declared selection and the number of records of the source that arrived before it took effect. */
    public record Step(String status, int before, Json declaration) {
    }

    private final List<Step> steps;

    private CompatibilityHistory(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    /** The history of a declared source entry (its {@code compatibility_history}, else its in-force {@code compatibility}), empty when it declares none. */
    public static CompatibilityHistory of(Json source) {
        List<Json> declared = new ArrayList<>();
        if (source != null && source.member("compatibility_history") instanceof Json.JArray h) {
            declared.addAll(h.elements());
        }
        else if (source != null && source.member("compatibility") != null) {
            declared.add(source.member("compatibility"));
        }
        List<Step> steps = new ArrayList<>();
        for (Json d : declared) {
            Integer before = Event.integer(d.member("records_before_selection"));
            steps.add(new Step(SchemaCatalog.text(d, "status"), before == null ? 0 : before, d));
        }
        return new CompatibilityHistory(steps);
    }

    public List<Step> steps() {
        return steps;
    }

    public boolean declared() {
        return !steps.isEmpty();
    }

    /** The selection in force (the last declared), or {@code null}. */
    public Step inForce() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1);
    }

    /** Whether a selection status lets a record be normalized: a selected entry, or a profile that normalizes nothing. */
    public static boolean normalizing(String status) {
        return "tested".equals(status) || "in_range".equals(status) || "not_version_scoped".equals(status);
    }

    /** The selection status that governed record {@code index} (1-based), or {@code null} when the source declares none. */
    public String statusOf(int index) {
        String status = null;
        for (Step s : steps) {
            if (s.before() < index) {
                status = s.status();
            }
        }
        return status;
    }

    /**
     * The record indices in {@code 1..records} that arrived under a declared non-normalizing selection. A record no
     * declared selection governs (an ancillary manifest that keeps only the selection in force) is not claimed either way.
     */
    public Set<Integer> unselected(int records) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int i = 1; i <= records; i++) {
            String status = statusOf(i);
            if (status != null && !normalizing(status)) {
                out.add(i);
            }
        }
        return out;
    }

    /** The value at an RFC 6901 pointer ({@code ~1} is {@code /}, {@code ~0} is {@code ~}), or {@code null}; the reader's own, not the writer's. */
    public static Json at(Json root, String pointer) {
        if (root == null || pointer == null || !pointer.startsWith("/")) {
            return null;
        }
        Json x = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            x = step(x, raw.replace("~1", "/").replace("~0", "~"));
            if (x == null) {
                return null;
            }
        }
        return x;
    }

    private static Json step(Json x, String step) {
        if (x instanceof Json.JObject o) {
            return o.get(step);
        }
        if (x instanceof Json.JArray a && step.matches("0|[1-9][0-9]*") && Integer.parseInt(step) < a.elements().size()) {
            return a.elements().get(Integer.parseInt(step));
        }
        return null;
    }

    /** The distinct source-reported version assertions the declared selections cite for records of {@code artifact}. */
    public List<Json> sourceAssertions(String artifact) {
        List<Json> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Step s : steps) {
            Json stated = s.declaration().member("version_assertions");
            for (Json a : stated == null ? List.<Json>of() : stated.elements()) {
                boolean cites = "source".equals(SchemaCatalog.text(a, "origin")) && artifact.equals(SchemaCatalog.text(a, "artifact"));
                if (cites && seen.add(NeutralJson.write(a))) {
                    out.add(a);
                }
            }
        }
        return out;
    }
}
