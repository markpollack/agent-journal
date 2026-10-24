package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Final assembly inside the one writer: while the declared sources are fed, each mapper's events are held as
 * observations (with their source occurrence: artifact, record and emit ordinal); when every source has ended the
 * assembler applies the identity gate ({@link AssemblyGate}), groups the accepted observations into entities by the
 * profile's declared joins ({@link AssemblyGrouping}), consolidates every multi-source entity into one canonical event
 * ({@link EventMerge}), declares the assembly once, and appends in one pass: each observation in precedence and arrival
 * order (a canonical single observation, or a supporting observation of a consolidated entity) and each consolidated
 * event right after its primary observation. Observations of refused sources are not written; the declaration says why.
 * Canonical consumers read events with assembly role 1 or 3; role 2 is evidence and never counted again.
 */
final class Assembler {

    static final String ENGINE = "journal-ocsf-writer Assembler";
    static final String ENGINE_VERSION = "0.1.0-prototype";
    static final String CONTRACT = "final-assembly";
    static final String RULE = "assembly.consolidate->canonical";
    static final Map<Integer, String> ROLE = Map.of(1, "Canonical single observation", 2, "Supporting observation", 3, "Consolidated canonical");

    /** One mapper event held for assembly, with its source occurrence. */
    record Observation(String artifact, String owner, int record, int ordinal, String className, int arrival, Jv.Obj event) {
        String occurrence() {
            return artifact + "#" + record + "/" + ordinal;
        }
    }

    /** Where the assembled file goes: its declaration writer and the root or a child core. */
    interface Out {
        void declare(Jv.Obj assembly);

        void append(String owner, Jv.Obj event);
    }

    private final AssemblyPlan plan;
    private final Map<Integer, String> classNames;
    private final MappingContracts contracts;
    private final List<Observation> observations = new ArrayList<>();
    private final Map<String, Integer> ordinals = new LinkedHashMap<>();

    Assembler(AssemblyPlan plan, Map<Integer, String> classNames, MappingContracts contracts) {
        this.plan = plan;
        this.classNames = classNames;
        this.contracts = contracts;
    }

    AssemblyPlan plan() {
        return plan;
    }

    void observe(String artifact, String owner, Jv.Obj event) {
        int record = event.path("source_ref", "record_index") instanceof Jv.Num n ? n.value().intValue() : 0;
        int ordinal = ordinals.merge(artifact + "#" + record, 1, Integer::sum) - 1;
        String className = event.get("class_uid") instanceof Jv.Num n ? classNames.getOrDefault(n.value().intValue(), "unknown") : "unknown";
        observations.add(new Observation(artifact, owner, record, ordinal, className, observations.size(), event));
    }

    /** Gates, groups, consolidates and writes everything held; called once when every source has ended. */
    void write(Out out, Map<String, AssemblyGate.SourceFacts> facts) {
        Map<String, AssemblyGate.Verdict> verdicts = AssemblyGate.decide(plan, observations, facts);
        List<Observation> accepted = new ArrayList<>(observations.stream().filter(o -> verdicts.containsKey(o.artifact()) && verdicts.get(o.artifact()).accepted()).toList());
        accepted.sort(Comparator.comparingInt((Observation o) -> plan.rank(o.artifact())).thenComparingInt(Observation::arrival));
        List<AssemblyGrouping.Group> groups = AssemblyGrouping.group(plan, accepted, copies(verdicts, facts));
        Map<Observation, Integer> groupOf = new HashMap<>();
        List<EventMerge.Result> merged = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            AssemblyGrouping.Group g = groups.get(i);
            int index = i;
            g.members().forEach(o -> groupOf.put(o, index));
            merged.add(g.members().size() > 1 && !g.ambiguous() ? EventMerge.merge(g.members(), g.envelope(), policy(g)) : null);
        }
        out.declare(AssemblyDeclaration.payload(plan, verdicts, facts, groups, merged, contracts.uid(CONTRACT)));
        for (Observation o : accepted) {
            emit(out, o, groups.get(groupOf.get(o)), merged.get(groupOf.get(o)));
        }
    }

    /** One observation in its place, and its entity's consolidated event right after the entity's primary observation. */
    private void emit(Out out, Observation o, AssemblyGrouping.Group g, EventMerge.Result m) {
        Integer relation = m == null && g.ambiguous() ? Integer.valueOf(6) : null;
        out.append(o.owner(), annotate(o, g, relation, m == null ? 1 : 2));
        if (m != null && g.members().get(0).equals(o)) {
            out.append(o.owner(), consolidated(g, m));
        }
    }

    /** Accepted artifacts whose bytes equal an earlier accepted artifact's, mapped to that artifact. */
    private Map<String, String> copies(Map<String, AssemblyGate.Verdict> verdicts, Map<String, AssemblyGate.SourceFacts> facts) {
        Map<String, String> firstBySha = new HashMap<>();
        Map<String, String> out = new HashMap<>();
        for (AssemblyPlan.Binding b : plan.bindings()) {
            AssemblyGate.SourceFacts f = facts.get(b.artifact());
            if (f == null || verdicts.get(b.artifact()) == null || !verdicts.get(b.artifact()).accepted()) {
                continue;
            }
            String first = firstBySha.putIfAbsent(f.sha256(), b.artifact());
            if (first != null) {
                out.put(b.artifact(), first);
            }
        }
        return out;
    }

    /** The profile's declared authorities for the group's class, and each member's role. */
    private EventMerge.Policy policy(AssemblyGrouping.Group g) {
        Map<String, String[]> authorities = new HashMap<>();
        String className = g.members().get(0).className();
        for (AssemblyProfile.Authority a : plan.profile().authorities()) {
            if (a.className().equals(className)) {
                authorities.put(a.pointer(), new String[] {a.role(), a.basis()});
            }
        }
        Map<String, String> roleOf = new HashMap<>();
        Map<String, AssemblyPlan.Binding> bindings = plan.byArtifact();
        g.members().forEach(o -> roleOf.put(o.occurrence(), bindings.get(o.artifact()).role()));
        return new EventMerge.Policy(authorities, roleOf);
    }

    private static Jv.Obj annotate(Observation o, AssemblyGrouping.Group g, Integer relation, int role) {
        Jv.Obj a = new Jv.Obj().put("assembly_role_id", role).put("assembly_role", ROLE.get(role)).put("entity_uid", g.entity()).put("occurrence_uid", o.occurrence());
        if (relation != null) {
            a.put("relation_class_id", relation).put("relation_class", EventMerge.RELATION.get(relation))
                    .put("basis", "the declared identity repeats within one source, so no pairing is established; every observation stays canonical and flagged");
        }
        Jv.Obj e = o.event();
        e.put("assembly", a);
        return e;
    }

    private Jv.Obj consolidated(AssemblyGrouping.Group g, EventMerge.Result m) {
        Jv.Obj e = m.event();
        Jv.Obj journal = (Jv.Obj) Jv.copy(e.get("journal"));
        journal.put("contract_uid", contracts.uid(CONTRACT)).put("rule_uid", contracts.rule(CONTRACT, RULE))
                .put("basis", "consolidated by final assembly from the observations in assembly.observation_refs; time, metadata and source_ref are the primary observation's");
        e.put("journal", journal);
        Jv.Arr refs = new Jv.Arr();
        for (Observation o : g.members()) {
            refs.add(new Jv.Obj().put("occurrence_uid", o.occurrence()).put("artifact", o.artifact()).put("record_index", o.record()).put("emit_ordinal", o.ordinal()));
        }
        Jv.Obj a = new Jv.Obj().put("assembly_role_id", 3).put("assembly_role", ROLE.get(3)).put("entity_uid", g.entity())
                .put("relation_class_id", m.relation()).put("relation_class", EventMerge.RELATION.get(m.relation())).put("observation_refs", refs);
        if (!m.evidence().elements().isEmpty()) {
            a.put("field_evidence", m.evidence());
        }
        if (!m.conflicts().elements().isEmpty()) {
            a.put("assembly_conflicts", m.conflicts());
        }
        return e.put("assembly", a.put("basis", "the observations share the declared identity " + g.entity() + "; every field states its occurrences where they are not identical"));
    }

    /** The value at an RFC 6901 pointer, or {@code null}. */
    static Jv at(Jv value, String pointer) {
        Jv v = value;
        for (String step : pointer.substring(1).split("/", -1)) {
            String m = step.replace("~1", "/").replace("~0", "~");
            if (v instanceof Jv.Arr a) {
                int i = Integer.parseInt(m);
                v = i < a.elements().size() ? a.elements().get(i) : null;
            }
            else {
                v = v == null ? null : v.member(m);
            }
        }
        return v;
    }
}
