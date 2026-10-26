package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check.Category;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The consolidated events of an assembled file against their supporting observations, judged leaf by leaf (array
 * positions are wildcards, since elements merge by identity): no consolidated value that no supporting observation
 * reports (nothing invented), and no supporting value missing from the consolidated event unless it lies under a listed
 * conflict or was superseded by a final observation of the same quantity (nothing silently lost). Envelope members,
 * each observation's own members and provenance members (how one source established a value) are not facts and are
 * not compared. References must resolve: each consolidated event names supporting observations of its entity, and each
 * supporting observation is named by exactly one consolidated event.
 */
final class AssemblyConsolidation {

    static final Set<String> ENVELOPE = Set.of("class_uid", "class_name", "category_uid", "category_name", "activity_id", "activity_name", "type_uid", "type_name",
            "severity_id", "severity", "time", "time_dt", "metadata", "journal", "source_ref", "assembly", "data", "raw_data", "raw_bytes_b64");
    static final Set<String> PROVENANCE = Set.of("source_ref", "basis", "derivation", "native_pointers", "tool_kind_basis", "projection_basis", "components", "provider_basis_id", "provider_basis");

    private AssemblyConsolidation() {
    }

    /** The supporting occurrences each consolidated event names, by entity. */
    static Map<String, Set<String>> refsByEntity(OcsfPackage pkg) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Event e : pkg.events) {
            if (Integer.valueOf(3).equals(e.assemblyRole())) {
                Set<String> refs = out.computeIfAbsent(e.text("assembly", "entity_uid"), k -> new TreeSet<>());
                e.elements("assembly", "observation_refs").forEach(r -> refs.add(RawInventory.text(r, "occurrence_uid")));
            }
        }
        return out;
    }

    static void check(OracleReport report, OcsfPackage pkg) {
        Map<String, Event> byOccurrence = new LinkedHashMap<>();
        for (Event e : pkg.observations()) {
            if (e.assemblyRole() != null) {
                byOccurrence.put(e.text("assembly", "occurrence_uid"), e);
            }
        }
        List<String> refProblems = new ArrayList<>();
        List<String> fieldProblems = new ArrayList<>();
        Map<String, Integer> named = new LinkedHashMap<>();
        List<Event> consolidated = pkg.events.stream().filter(e -> Integer.valueOf(3).equals(e.assemblyRole())).toList();
        for (Event c : consolidated) {
            fieldProblems.addAll(fields(c, supporting(c, byOccurrence, named, refProblems)));
        }
        byOccurrence.forEach((occ, e) -> {
            int n = named.getOrDefault(occ, 0);
            if (Integer.valueOf(2).equals(e.assemblyRole()) != (n == 1)) {
                refProblems.add(occ + " is role " + e.assemblyRole() + " and named by " + n + " consolidated event(s)");
            }
        });
        report.add(Category.NORMALIZATION, "assembly.references", AssemblyOracle.OWNER, refProblems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                refProblems.isEmpty() ? consolidated.size() + " consolidated event(s); every reference resolves to a supporting observation of its entity, each named once" : limit(refProblems));
        report.add(Category.EXPLANATION, "assembly.consolidation", AssemblyOracle.OWNER, fieldProblems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                fieldProblems.isEmpty() ? "every consolidated value is reported by a supporting observation, and every supporting value is carried, listed as a conflict or superseded" : limit(fieldProblems));
    }

    private static List<Event> supporting(Event c, Map<String, Event> byOccurrence, Map<String, Integer> named, List<String> problems) {
        List<Event> out = new ArrayList<>();
        String entity = c.text("assembly", "entity_uid");
        for (Json r : c.elements("assembly", "observation_refs")) {
            String occ = RawInventory.text(r, "occurrence_uid");
            Event s = byOccurrence.get(occ);
            named.merge(occ, 1, Integer::sum);
            if (s == null || !entity.equals(s.text("assembly", "entity_uid")) || !RawInventory.text(r, "artifact").equals(s.artifact())
                    || !String.valueOf(s.recordIndex()).equals(r.member("record_index") == null ? null : r.member("record_index").scalarText())) {
                problems.add(c + " names " + occ + ", which is not a supporting observation of " + entity + " at that record");
                continue;
            }
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ fields

    private static List<String> fields(Event c, List<Event> supporting) {
        List<String> problems = new ArrayList<>(envelope(c, supporting));
        Map<String, List<Json>> carried = leaves(c.json);
        Map<String, List<Json>> reported = new LinkedHashMap<>();
        supporting.forEach(s -> leaves(s.json).forEach((p, vs) -> reported.computeIfAbsent(p, k -> new ArrayList<>()).addAll(vs)));
        Set<String> excused = excused(c);
        carried.forEach((p, vs) -> vs.forEach(v -> {
            if (!contains(reported.get(p), v)) {
                problems.add(c + " carries " + p + " = " + AssemblyOracle.write(v) + ", which no supporting observation reports");
            }
        }));
        for (Event s : supporting) {
            leaves(s.json).forEach((p, vs) -> vs.forEach(v -> {
                // a merged partial projection lacks only what every projection lacked (the final-assembly contract)
                if (!contains(carried.get(p), v) && !under(p, excused) && !p.endsWith("/missing_required/*")) {
                    problems.add(c + " drops " + p + " = " + AssemblyOracle.write(v) + " of " + s.text("assembly", "occurrence_uid") + " without a conflict or supersession");
                }
            }));
        }
        return problems;
    }

    /**
     * The consolidated event's envelope and top-level provenance are one supporting observation's: its source
     * reference, activity, time and basis come from the same occurrence, never mixed across sources.
     */
    private static List<String> envelope(Event c, List<Event> supporting) {
        for (Event s : supporting) {
            boolean same = sameAt(c, s, "source_ref") && sameAt(c, s, "activity_id") && sameAt(c, s, "time") && sameAt(c, s, "basis") && sameAt(c, s, "class_uid");
            if (same) {
                return List.of();
            }
        }
        return List.of(c + " has an envelope (source_ref, activity, time, basis) that no single supporting observation has");
    }

    private static boolean sameAt(Event a, Event b, String member) {
        Json x = a.get(member);
        Json y = b.get(member);
        return x == null ? y == null : y != null && Json.sameValue(x, y);
    }

    /** The wildcarded pointers whose values a consolidated event may lack: listed conflicts and superseded observations. */
    private static Set<String> excused(Event c) {
        Set<String> out = new TreeSet<>();
        for (Json conflict : c.elements("assembly", "assembly_conflicts")) {
            out.add(wildcard(RawInventory.text(conflict, "field_pointer")));
        }
        for (Json f : c.elements("assembly", "field_evidence")) {
            if (f.member("relation_class_id") != null && "8".equals(f.member("relation_class_id").scalarText())) {
                out.add(wildcard(RawInventory.text(f, "field_pointer")));
            }
        }
        return out;
    }

    private static boolean under(String pointer, Set<String> prefixes) {
        return prefixes.stream().anyMatch(p -> pointer.equals(p) || pointer.startsWith(p + "/"));
    }

    private static String wildcard(String pointer) {
        return pointer.replaceAll("/\\d+(?=/|$)", "/*");
    }

    private static boolean contains(List<Json> values, Json v) {
        return values != null && values.stream().anyMatch(x -> Json.sameValue(x, v));
    }

    /** Fact leaves by wildcarded pointer: envelope, own and provenance members left out. */
    static Map<String, List<Json>> leaves(Json event) {
        Map<String, List<Json>> out = new LinkedHashMap<>();
        if (event instanceof Json.JObject o) {
            o.members().forEach((k, v) -> {
                if (!ENVELOPE.contains(k) && !PROVENANCE.contains(k)) {
                    walk(v, "/" + k, out);
                }
            });
        }
        return out;
    }

    private static void walk(Json v, String path, Map<String, List<Json>> out) {
        if (v instanceof Json.JObject o && !o.members().isEmpty()) {
            boolean observation = o.members().containsKey("basis") || o.members().containsKey("source_ref");
            o.members().forEach((k, x) -> {
                // an observation's nested data is its native residual (provenance), as the final-assembly contract states
                if (!PROVENANCE.contains(k) && !(observation && "data".equals(k))) {
                    walk(x, path + "/" + k.replace("~", "~0").replace("/", "~1"), out);
                }
            });
            return;
        }
        if (v instanceof Json.JArray a && !a.elements().isEmpty()) {
            a.elements().forEach(x -> walk(x, path + "/*", out));
            return;
        }
        out.computeIfAbsent(path, k -> new ArrayList<>()).add(v);
    }

    static String limit(List<String> problems) {
        return String.join("; ", problems.subList(0, Math.min(10, problems.size()))) + (problems.size() > 10 ? "; ... " + problems.size() + " in all" : "");
    }
}
