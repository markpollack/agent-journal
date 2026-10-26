package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the consolidated canonical event of one entity from its observations (in the profile's precedence order),
 * field by field: a field one observation reports is taken from it; equal reports give their common value; reports that
 * differ only in number spelling keep the primary's spelling; objects merge member by member; arrays whose elements
 * carry a declared identity (usage and cost observations by scope, kind and model, content blocks by index, protocol
 * objects by definition, tool definitions by name, errors by layer) merge element by element; any other difference is a
 * conflict, recorded with every value and its occurrence, and the field is left out of the canonical event. Provenance
 * members (source references, bases, derivations, native residual data) are each observation's own: the canonical event
 * carries the primary's and every supporting observation keeps its own. No universal source-wins rule applies.
 */
final class EventMerge {

    /** Members the envelope copies from the primary observation; never merged. */
    static final Set<String> ENVELOPE = Set.of("class_uid", "class_name", "category_uid", "category_name", "activity_id", "activity_name", "type_uid", "type_name",
            "severity_id", "severity", "time", "time_dt", "metadata", "journal", "source_ref");
    /** Members that belong to one observation and are dropped from the consolidated event (each supporting observation keeps its own). */
    static final Set<String> OWN = Set.of("assembly", "data", "raw_data", "raw_bytes_b64");
    /**
     * Members that state how one source established a value, at any depth: the primary's is kept, never compared as a
     * fact; {@code components} states how one observation established its usage values. (A top-level {@code data} member
     * is the native residual and is {@link #OWN}; a nested {@code data} member is its object's native residual, and so
     * provenance, when that object states a {@code basis} or {@code source_ref}; otherwise, such as MCP image or resource
     * data, it is a fact and is compared.)
     */
    static final Set<String> PROVENANCE = Set.of("source_ref", "basis", "derivation", "native_pointers", "tool_kind_basis", "projection_basis", "components");
    /** Arrays whose elements carry an identity, with the members that form it. */
    static final Map<String, List<String>> ARRAY_KEYS = Map.of(
            "execution_metrics", List.of("metric_name", "metric_unit", "scope_id", "scope", "model_ref"),
            "usage_observations", List.of("scope_id", "scope", "aggregation_id", "model_ref"),
            "cost_observations", List.of("quantity_kind_id", "scope_id", "currency", "quantity_unit", "model_ref"),
            "content_blocks", List.of("block_index"),
            "protocol_objects", List.of("protocol_definition", "projection_id"),
            "tool_definitions", List.of("name"),
            "components", List.of("name"),
            "errors", List.of("error_layer_id"),
            "permission_decisions", List.of("decision_id", "scope_id"));

    /** A partial protocol projection's list of required members it lacks. */
    static final String MISSING = "missing_required";
    static final Map<Integer, String> RELATION = Map.of(1, "Identical", 2, "Representation only", 3, "One richer", 4, "Each richer", 5, "Conflicting", 6, "Ambiguous",
            7, "Single source", 8, "Superseded");
    /** Arrays whose elements carry a finality: of one quantity, only the most final observations reach the canonical event. */
    static final Map<String, String> FINALITY = Map.of("usage_observations", "usage_status_id");

    /** One observation's value at a location. */
    record Contribution(String occurrence, Jv value) {
    }

    /** The consolidated event, the relation of its observations, and the per-field evidence and conflicts. */
    record Result(Jv.Obj event, int relation, Jv.Arr evidence, Jv.Arr conflicts) {
    }

    /** Declared field authorities of the class merged: pointer to (role, basis); and each occurrence's role. */
    record Policy(Map<String, String[]> authorities, Map<String, String> roleOf) {
        static final Policy NONE = new Policy(Map.of(), Map.of());
    }

    private final Policy policy;
    private final Jv.Arr evidence = new Jv.Arr();
    private final Jv.Arr conflicts = new Jv.Arr();
    private final Set<String> contributors = new LinkedHashSet<>();
    private boolean representation;
    private final int total;

    private EventMerge(int total, Policy policy) {
        this.total = total;
        this.policy = policy;
    }

    static Result merge(List<Assembler.Observation> members) {
        return merge(members, 0, Policy.NONE);
    }

    /**
     * @param envelope the member whose envelope (class, activity, time, metadata, journal, source reference) the
     *        consolidated event takes: the primary, or the most advanced lifecycle stage under a staged join
     */
    static Result merge(List<Assembler.Observation> members, int envelope, Policy policy) {
        EventMerge m = new EventMerge(members.size(), policy);
        Jv.Obj primary = members.get(envelope).event();
        Jv.Obj out = new Jv.Obj();
        primary.members().forEach((k, v) -> {
            if (ENVELOPE.contains(k)) {
                out.put(k, Jv.copy(v));
            }
        });
        for (Map.Entry<String, List<Contribution>> f : fields(members).entrySet()) {
            // top-level provenance is the envelope member's own: the consolidated event states one source's basis for its stage
            Jv v = PROVENANCE.contains(f.getKey()) ? Jv.copy(primary.get(f.getKey())) : m.at("/" + escape(f.getKey()), f.getKey(), f.getValue());
            if (v != null) {
                out.put(f.getKey(), v);
            }
        }
        return new Result(out, m.relation(), m.evidence, m.conflicts);
    }

    /** Each fact member of the observations with every occurrence's value. */
    private static Map<String, List<Contribution>> fields(List<Assembler.Observation> members) {
        Map<String, List<Contribution>> fields = new LinkedHashMap<>();
        for (Assembler.Observation o : members) {
            o.event().members().forEach((k, v) -> {
                if (!ENVELOPE.contains(k) && !OWN.contains(k)) {
                    fields.computeIfAbsent(k, x -> new ArrayList<>()).add(new Contribution(o.occurrence(), v));
                }
            });
        }
        return fields;
    }

    private int relation() {
        if (!conflicts.elements().isEmpty()) {
            return 5;
        }
        if (!contributors.isEmpty()) {
            return contributors.size() > 1 ? 4 : 3;
        }
        return representation ? 2 : 1;
    }

    private Jv at(String pointer, String name, List<Contribution> entries) {
        if (entries.size() == 1) {
            if (total > 1) {
                single(pointer, entries.get(0));
            }
            return Jv.copy(entries.get(0).value());
        }
        if (PROVENANCE.contains(name) || exactlyEqual(entries)) {
            return Jv.copy(entries.get(0).value());
        }
        if (entries.stream().allMatch(c -> McpAlternatives.sameJson(c.value(), entries.get(0).value()))) {
            representation = true;
            evidence.add(evidence(pointer, 2, entries, "equal values; the primary's spelling is kept"));
            return Jv.copy(entries.get(0).value());
        }
        return structured(pointer, name, entries);
    }

    /** Objects merge member by member and identified arrays element by element; anything else that differs conflicts. */
    private Jv structured(String pointer, String name, List<Contribution> entries) {
        if (entries.stream().allMatch(c -> c.value() instanceof Jv.Obj)) {
            return object(pointer, entries);
        }
        Jv merged = entries.stream().allMatch(c -> c.value() instanceof Jv.Arr) && ARRAY_KEYS.containsKey(name) ? array(pointer, name, entries) : null;
        return merged != null ? merged : conflict(pointer, entries);
    }

    private Jv.Obj object(String pointer, List<Contribution> entries) {
        Map<String, List<Contribution>> members = new LinkedHashMap<>();
        for (Contribution c : entries) {
            for (Map.Entry<String, Jv> m : ((Jv.Obj) c.value()).members().entrySet()) {
                members.computeIfAbsent(m.getKey(), k -> new ArrayList<>()).add(new Contribution(c.occurrence(), m.getValue()));
            }
        }
        Jv.Obj out = new Jv.Obj();
        boolean observation = members.containsKey("basis") || members.containsKey("source_ref");
        for (Map.Entry<String, List<Contribution>> m : members.entrySet()) {
            Jv v = member(pointer, m.getKey(), m.getValue(), entries, observation);
            if (v != null) {
                out.put(m.getKey(), v);
            }
        }
        return out;
    }

    private Jv member(String pointer, String name, List<Contribution> values, List<Contribution> entries, boolean observation) {
            if (observation && "data".equals(name)) {
                return Jv.copy(values.get(0).value());
            }
            else if (pointer.endsWith("/model_ref") && ("provider_basis_id".equals(name) || "provider_basis".equals(name))) {
                List<Contribution> reported = entries.stream().filter(c -> c.value().member("provider_kind") != null)
                        .map(c -> new Contribution(c.occurrence(), c.value().member(name))).filter(c -> c.value() != null).toList();
                return at(pointer + "/" + name, name, reported.isEmpty() ? values : reported);
            }
            else if (MISSING.equals(name)) {
                return stillMissing(pointer + "/" + MISSING, entries);
            }
            else {
                return at(pointer + "/" + escape(name), name, values);
            }
    }

    /** Merges arrays element by element by the declared identity; {@code null} when an identity repeats within one array. */
    private Jv.Arr array(String pointer, String name, List<Contribution> entries) {
        Map<String, List<Contribution>> elements = new LinkedHashMap<>();
        for (Contribution c : entries) {
            Set<String> own = new LinkedHashSet<>();
            for (Jv el : c.value().elements()) {
                String k = identity(name, el);
                if (k == null || !own.add(k)) {
                    return null;
                }
                elements.computeIfAbsent(k, x -> new ArrayList<>()).add(new Contribution(c.occurrence(), el));
            }
        }
        Jv.Arr out = new Jv.Arr();
        for (List<Contribution> all : elements.values()) {
            List<Contribution> el = FINALITY.containsKey(name) ? mostFinal(pointer + "/" + out.elements().size(), FINALITY.get(name), all) : all;
            Jv v = at(pointer + "/" + out.elements().size(), "", el);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    /**
     * Of observations of one quantity, the ones at the highest finality (final over provisional); the others are recorded
     * as superseded and stay on their supporting observations.
     */
    private List<Contribution> mostFinal(String pointer, String member, List<Contribution> all) {
        int best = 0;
        for (Contribution c : all) {
            best = Math.max(best, finality(c, member));
        }
        List<Contribution> kept = new ArrayList<>();
        List<Contribution> superseded = new ArrayList<>();
        for (Contribution c : all) {
            (finality(c, member) == best ? kept : superseded).add(c);
        }
        if (!superseded.isEmpty()) {
            contributors.addAll(kept.stream().map(Contribution::occurrence).toList());
            evidence.add(evidence(pointer, 8, superseded, "superseded: a final observation of the same quantity (" + String.join(", ", kept.stream().map(Contribution::occurrence).toList())
                    + ") is carried; the provisional values stay on their supporting observations"));
        }
        return kept;
    }

    private static int finality(Contribution c, String member) {
        return c.value().member(member) instanceof Jv.Num n ? n.value().intValue() : 0;
    }

    /**
     * The members a merged partial protocol projection still lacks: those every supporting projection lacks (a
     * projection without the list lacks none); {@code null} when none is missing. One source's projection supplying a
     * member another lacked is complementary, not a conflict.
     */
    private Jv stillMissing(String pointer, List<Contribution> parents) {
        java.util.LinkedHashSet<String> missing = null;
        for (Contribution c : parents) {
            java.util.LinkedHashSet<String> own = new java.util.LinkedHashSet<>();
            Jv list = c.value().member(MISSING);
            (list == null ? List.<Jv>of() : list.elements()).forEach(x -> own.add(x.text()));
            if (missing == null) {
                missing = own;
            }
            else {
                missing.retainAll(own);
            }
        }
        if (missing == null || missing.isEmpty()) {
            evidence.add(evidence(pointer, 3, parents, "the merged partial projection lacks no member: each member one projection lacked another supplied"));
            return null;
        }
        Jv.Arr out = new Jv.Arr();
        missing.forEach(m -> out.add(new Jv.Str(m)));
        return out;
    }

    private static String identity(String name, Jv element) {
        if (!(element instanceof Jv.Obj o)) {
            return null;
        }
        StringBuilder k = new StringBuilder();
        for (String m : ARRAY_KEYS.get(name)) {
            Jv v = "model_ref".equals(m) ? o.path("model_ref", "name") : o.get(m);
            k.append(v == null ? "" : Jv.write(v)).append('|');
        }
        return k.toString();
    }

    private void single(String pointer, Contribution c) {
        contributors.add(c.occurrence());
        evidence.add(evidence(pointer, 7, List.of(c), "only this occurrence reports it"));
    }

    /** Records a conflict; returns the value a declared authority selects, else {@code null} (the field stays absent). */
    private Jv conflict(String pointer, List<Contribution> entries) {
        Jv.Arr values = new Jv.Arr();
        entries.forEach(c -> values.add(new Jv.Obj().put("occurrence_uid", c.occurrence()).put("value", Jv.copy(c.value()))));
        String[] authority = policy.authorities().get(pointer);
        Contribution chosen = null;
        for (Contribution c : entries) {
            if (authority != null && chosen == null && authority[0].equals(policy.roleOf().get(c.occurrence()))) {
                chosen = c;
            }
        }
        String resolution = chosen == null ? "unresolved: the canonical event leaves the field absent; each value stays on its supporting observation"
                : "selected by the profile's declared authority: the " + authority[0] + " source's value (" + authority[1] + "); every value stays on its supporting observation";
        conflicts.add(new Jv.Obj().put("field_pointer", pointer).put("observed_values", values).put("resolution", resolution));
        return chosen == null ? null : Jv.copy(chosen.value());
    }

    private static Jv.Obj evidence(String pointer, int relation, List<Contribution> entries, String basis) {
        Jv.Arr occ = new Jv.Arr();
        entries.forEach(c -> occ.add(new Jv.Str(c.occurrence())));
        return new Jv.Obj().put("field_pointer", pointer).put("relation_class_id", relation).put("relation_class", RELATION.get(relation))
                .put("occurrence_uids", occ).put("selection_basis", basis);
    }

    private static boolean exactlyEqual(List<Contribution> entries) {
        String first = Jv.write(entries.get(0).value());
        return entries.stream().allMatch(c -> Jv.write(c.value()).equals(first));
    }

    static String escape(String member) {
        return member.replace("~", "~0").replace("/", "~1");
    }
}
