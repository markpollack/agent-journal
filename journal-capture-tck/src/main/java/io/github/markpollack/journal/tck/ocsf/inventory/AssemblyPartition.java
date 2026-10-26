package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.FactComparison;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle.Supplied;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceLocator.Located;
import io.github.markpollack.journal.tck.verify.Check.Category;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which observations of an assembled file are one entity, derived from the native records alone: observations whose
 * native records report the same identity under an expectation's entity locator are one entity (directly, or through a
 * chain across a third source); an identity a locator finds in two records of one artifact at the same lifecycle stage
 * is ambiguous and its observations stay apart (at different stages it is one entity's lifecycle); observations of a
 * declared copy and its original are one entity record by record, while equal bytes alone relate nothing. The
 * partition this gives is compared with the file's: every multi-observation entity must be supporting
 * observations of one consolidated event naming exactly them, every other observation a canonical single one, and an
 * ambiguous one flagged as such.
 *
 * <p>The lifecycle stage of a native record is read from the record, never from the candidate (ACCT-1, review finding
 * CDX-R1D-03): an entity's {@code activities} with one value is that stage, and a locator's {@code stage} rule reads it
 * from the record ({@code {"pointer": "/type"}} names the native discriminator as the stage, {@code {"pointer":
 * "/message/stop_reason", "null": "streamed", "otherwise": "final"}} maps a member's presence). Where neither is
 * stated the candidate's activity is used as before, and the check is then reported UNRESOLVED, naming the entity and
 * role, instead of VERIFIED: a stage the candidate chose cannot prove the candidate's own grouping.
 */
final class AssemblyPartition {

    private OcsfPackage pkg;
    private final List<Event> observations;
    private final int[] parent;
    private final Set<Integer> ambiguous = new LinkedHashSet<>();
    private final List<String> unmatched = new ArrayList<>();
    private final Map<String, List<Integer>> groups = new LinkedHashMap<>();
    private final Set<String> ambiguousGroups = new LinkedHashSet<>();
    /** Entity and role pairs whose records carry no native stage rule, so the candidate's activity stood in. */
    private final Set<String> candidateStages = new LinkedHashSet<>();

    private AssemblyPartition(List<Event> observations) {
        this.observations = observations;
        this.parent = new int[observations.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
    }

    static void check(OracleReport report, OcsfPackage pkg, Json spec, List<Supplied> accepted) {
        partition(report, pkg.observations().stream().filter(e -> e.className != null).toList(), spec, accepted, AssemblyConsolidation.refsByEntity(pkg), pkg);
    }

    /** The partition over given observations and consolidated references (the seam the package-free controls use). */
    static void check(OracleReport report, List<Event> observations, Json spec, List<Supplied> accepted, Map<String, Set<String>> consolidatedRefs) {
        partition(report, observations, spec, accepted, consolidatedRefs, null);
    }

    private static void partition(OracleReport report, List<Event> observations, Json spec, List<Supplied> accepted, Map<String, Set<String>> consolidatedRefs, OcsfPackage pkg) {
        AssemblyPartition p = new AssemblyPartition(observations);
        p.pkg = pkg;
        for (Json entity : spec.member("entities").elements()) {
            for (Supplied s : accepted) {
                for (Json locator : locators(entity, s.role())) {
                    p.key(entity, s, locator);
                }
            }
        }
        p.copies(accepted);
        p.compare(report, consolidatedRefs);
    }

    private static List<Json> locators(Json entity, String role) {
        Json l = entity.path("locators", role);
        if (l == null) {
            return List.of();
        }
        return l instanceof Json.JArray a ? a.elements() : List.of(l);
    }

    /**
     * Collects the observations of one entity locator's identities in one source; an identity two records of one source
     * report is ambiguous, and then no observation of it in any source is paired.
     */
    private void key(Json entity, Supplied s, Json locator) {
        Map<String, List<Located>> keyed = SourceLocator.keyed(AssemblyOracle.located(s), locator);
        Map<Integer, Integer> keysPerRecord = new LinkedHashMap<>();
        keyed.values().forEach(rs -> rs.forEach(r -> keysPerRecord.merge(r.index(), 1, Integer::sum)));
        for (Map.Entry<String, List<Located>> k : keyed.entrySet()) {
            Map<Integer, String> stages = stages(entity, s, locator, k.getValue());
            Set<Integer> records = new TreeSet<>(stages.keySet());
            List<Integer> members = new ArrayList<>();
            for (int r : records) {
                members.addAll(observationsOf(entity, s.name(), r, keysPerRecord.get(r) > 1 ? k.getKey() : null));
            }
            String group = RawInventory.text(entity, "name") + "|" + k.getKey();
            if (records.size() > 1 && repeatsAStage(members, stages)) {
                ambiguousGroups.add(group);
            }
            if (members.isEmpty()) {
                unmatched.add(RawInventory.text(entity, "name") + " " + k.getKey() + " in " + s.name() + ":" + records);
            }
            groups.computeIfAbsent(group, g -> new ArrayList<>()).addAll(members);
        }
    }

    /**
     * The observations of the entity's class (and activities) that a native record produced; when the record holds
     * several identities under the locator, only those observations that carry {@code identity} as a value.
     */
    private List<Integer> observationsOf(Json entity, String artifact, int record, String identity) {
        String className = RawInventory.text(entity, "class");
        List<Integer> activities = AssemblyOracle.integers(entity.member("activities"));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < observations.size(); i++) {
            Event e = observations.get(i);
            boolean match = artifact.equals(e.artifact()) && Integer.valueOf(record).equals(e.recordIndex()) && className.equals(e.className)
                    && (activities.isEmpty() || activities.contains(e.activityId));
            if (match && (identity == null || carries(e.json, identity))) {
                out.add(i);
            }
        }
        return out;
    }

    private static boolean carries(Json value, String identity) {
        if (value instanceof Json.JString s) {
            return s.value().equals(identity);
        }
        if (value instanceof Json.JObject o) {
            return o.members().entrySet().stream().anyMatch(m -> !"assembly".equals(m.getKey()) && carries(m.getValue(), identity));
        }
        return value instanceof Json.JArray a && a.elements().stream().anyMatch(v -> carries(v, identity));
    }

    /** The native stage of each located record (by index), noting the entity and role when none is derivable. */
    private Map<Integer, String> stages(Json entity, Supplied s, Json locator, List<Located> located) {
        Map<Integer, String> stages = new TreeMap<>();
        for (Located r : located) {
            String stage = nativeStage(entity, locator, r.json());
            if (stage == null) {
                candidateStages.add(RawInventory.text(entity, "name") + " in " + s.role());
            }
            stages.put(r.index(), stage);
        }
        return stages;
    }

    /**
     * The lifecycle stage a native record reports under the entity's expectation: the entity's single declared activity,
     * else the locator's {@code stage} rule applied to the record; {@code null} when the expectation states neither.
     */
    static String nativeStage(Json entity, Json locator, Json record) {
        List<Integer> activities = AssemblyOracle.integers(entity.member("activities"));
        Json rule = locator.member("stage");
        if (rule == null) {
            return activities.size() == 1 ? "activity " + activities.get(0) : null;
        }
        return rule instanceof Json.JString s ? s.value() : stageByRule(rule, record);
    }

    /** A stage rule over the record: the value at {@code pointer}, mapped through {@code values} or through {@code null}/{@code otherwise}. */
    private static String stageByRule(Json rule, Json record) {
        String pointer = RawInventory.text(rule, "pointer");
        Json at = pointer == null ? null : SourceLocator.pointer(record, pointer);
        boolean absent = at == null || at.isNull();
        if (rule.member("values") instanceof Json.JObject values) {
            Json mapped = at instanceof Json.JString v ? values.get(v.value()) : null;
            return mapped == null ? "unmapped " + (absent ? "absent" : at.scalarText()) : mapped.text();
        }
        if (rule.member("null") != null || rule.member("otherwise") != null) {
            Json chosen = absent ? rule.member("null") : rule.member("otherwise");
            return chosen == null ? null : chosen.text();
        }
        return absent ? "absent" : at.scalarText();
    }

    /**
     * Whether two native records of one source report the identity at the same lifecycle stage. The stage is the native
     * one where the expectation derives it; otherwise the activity of the observations the record produced (the
     * candidate's choice, reported as such). Records at different stages (a request and its completion) are one
     * entity's lifecycle, not a repeated identity.
     */
    private boolean repeatsAStage(List<Integer> members, Map<Integer, String> stages) {
        Map<String, Set<Integer>> recordsByStage = new LinkedHashMap<>();
        for (int i : members) {
            Event e = observations.get(i);
            String stage = stages.get(e.recordIndex());
            recordsByStage.computeIfAbsent(stage != null ? stage : "candidate activity " + e.activityId, a -> new TreeSet<>()).add(e.recordIndex());
        }
        return recordsByStage.values().stream().anyMatch(r -> r.size() > 1);
    }

    /**
     * Declared copies (the supplier names the source they copy; their equal bytes are checked by the gate): their
     * observations of the same record, class and position are one. Equal bytes without that declaration relate nothing.
     */
    private void copies(List<Supplied> accepted) {
        for (Supplied s : accepted) {
            String original = s.copyOf();
            if (original == null || accepted.stream().noneMatch(a -> a.name().equals(original))) {
                continue;
            }
            Map<String, Integer> slots = slots(original);
            Map<String, Integer> copy = slots(s.name());
            copy.forEach((slot, i) -> {
                if (slots.containsKey(slot)) {
                    groups.computeIfAbsent("copy|" + slot + "|" + s.name(), g -> new ArrayList<>()).addAll(List.of(slots.get(slot), i));
                }
            });
        }
    }

    private Map<String, Integer> slots(String artifact) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Map<String, Integer> nth = new LinkedHashMap<>();
        for (int i = 0; i < observations.size(); i++) {
            Event e = observations.get(i);
            if (artifact.equals(e.artifact())) {
                String base = e.recordIndex() + "/" + e.className + "/" + e.activityId;
                out.put(base + "#" + nth.merge(base, 1, Integer::sum), i);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ comparison

    private void compare(OracleReport report, Map<String, Set<String>> consolidatedRefs) {
        groups.forEach((group, members) -> {
            if (ambiguousGroups.contains(group)) {
                ambiguous.addAll(members);
                return;
            }
            for (int m : members) {
                union(members.get(0), m);
            }
        });
        Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < observations.size(); i++) {
            byRoot.computeIfAbsent(find(i), r -> new ArrayList<>()).add(i);
        }
        List<String> problems = new ArrayList<>();
        int multi = 0;
        for (List<Integer> group : byRoot.values()) {
            multi += group.size() > 1 ? 1 : 0;
            problems.addAll(judge(group, consolidatedRefs));
            if (pkg != null && candidateStages.isEmpty()) {
                provenance(report, group, byRoot, consolidatedRefs);
            }
        }
        verdict(report, byRoot.size(), multi, problems);
    }

    /** Attributes the executed native partition comparison to each role, alias and source reference it establishes. */
    private void provenance(OracleReport report, List<Integer> group, Map<Integer, List<Integer>> partitions, Map<String, Set<String>> refs) {
        List<String> support = group.stream().map(i -> observations.get(i).artifact() + ":record=" + observations.get(i).recordIndex() + "#").toList();
        Set<String> aliases = new LinkedHashSet<>();
        List<Json> coordinates = new ArrayList<>();
        for (int i : group) {
            Event event = observations.get(i);
            int ordinal = 0;
            for (int before = 0; before < i; before++) {
                Event prior = observations.get(before);
                if (event.artifact().equals(prior.artifact()) && java.util.Objects.equals(event.recordIndex(), prior.recordIndex())) { ordinal++; }
            }
            String occurrence = event.artifact() + "#" + event.recordIndex() + "/" + ordinal;
            field(report, event, "/assembly/occurrence_uid", FactComparison.value(occurrence), support);
            field(report, event, "/assembly/assembly_role_id", FactComparison.value(group.size() == 1 ? 1 : 2), support);
            String alias = event.text("assembly", "entity_uid");
            if (alias != null) { aliases.add(alias); }
            coordinates.add(new Json.JObject(Map.of("artifact", FactComparison.value(event.artifact()), "record_index", FactComparison.value(event.recordIndex()),
                    "emit_ordinal", FactComparison.value(ordinal), "occurrence_uid", FactComparison.value(occurrence))));
        }
        boolean unique = aliases.size() <= 1 && partitions.values().stream().filter(g -> !g.equals(group)).flatMap(List::stream)
                .noneMatch(i -> aliases.contains(observations.get(i).text("assembly", "entity_uid")));
        boolean partition = unique && judge(group, refs).isEmpty();
        for (int i : group) {
            Event event = observations.get(i);
            if (event.json.path("assembly", "entity_uid") != null) {
                predicate(report, event, "/assembly/entity_uid", partition, support);
            }
        }
        if (group.size() > 1 && aliases.size() == 1) {
            String alias = aliases.iterator().next();
            for (Event event : pkg.canonical()) {
                if (Integer.valueOf(3).equals(event.assemblyRole()) && alias.equals(event.text("assembly", "entity_uid"))) {
                    field(report, event, "/assembly/assembly_role_id", FactComparison.value(3), support);
                    field(report, event, "/assembly/assembly_role", FactComparison.value("Consolidated canonical"), support);
                    predicate(report, event, "/assembly/entity_uid", partition, support);
                    Json actual = event.json.path("assembly", "observation_refs");
                    List<Json> actualRefs = actual == null ? List.of() : actual.elements();
                    boolean equal = actualRefs.size() == coordinates.size() && coordinates.stream().allMatch(c -> actualRefs.stream().filter(a -> Json.sameValue(a, c)).count() == 1);
                    predicate(report, event, "/assembly/observation_refs", equal, support);
                    field(report, event, "/journal/sequence", FactComparison.value(pkg.events.indexOf(event) + 1), support);
                    field(report, event, "/journal/contract_uid", FactComparison.value("agent-journal/final-assembly/0.1.0-prototype"), support);
                    field(report, event, "/journal/rule_uid", FactComparison.value("assembly.consolidate->canonical"), support);
                }
            }
        }
    }

    private static void field(OracleReport report, Event event, String pointer, Json expected, List<String> support) {
        String owner = "main/" + event.artifact();
        FactComparison.compare(report, "assembly.partition.value", owner,
                new Check.Evidence("assembly.native_partition/v1", event.line + pointer, support, owner + ":line=" + event.line + "#" + pointer, expected, FactComparison.at(event.json, pointer)));
    }

    private static void predicate(OracleReport report, Event event, String pointer, boolean pass, List<String> support) {
        String owner = "main/" + event.artifact();
        report.add(new Check(Category.NORMALIZATION, "assembly.partition.association", owner, pass ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                "the alias/reference set must represent exactly one independently derived native partition", 1,
                new Check.Evidence("assembly.native_partition/v1", event.line + pointer, support, owner + ":line=" + event.line + "#" + pointer,
                        FactComparison.value(true), FactComparison.value(pass), FactComparison.at(event.json, pointer))));
    }

    /** The one check: DEFECTIVE on any problem, VERIFIED with native stages throughout, UNRESOLVED where a candidate stage stood in. */
    private void verdict(OracleReport report, int entities, int multi, List<String> problems) {
        String detail = entities + " entities from the native identities, " + multi + " observed by more than one occurrence, " + ambiguous.size()
                + " ambiguous observation(s)" + (unmatched.isEmpty() ? "" : "; native identities with no observation of their class: " + unmatched)
                + (candidateStages.isEmpty() ? "; lifecycle stages read from the native records" : "; lifecycle stage taken from the candidate (no native stage rule) for " + candidateStages);
        Outcome outcome = !problems.isEmpty() ? Outcome.DEFECTIVE : candidateStages.isEmpty() ? Outcome.VERIFIED : Outcome.UNRESOLVED;
        report.add(Category.NORMALIZATION, "assembly.entities", AssemblyOracle.OWNER, outcome,
                problems.isEmpty() ? detail : String.join("; ", problems.subList(0, Math.min(12, problems.size()))) + (problems.size() > 12 ? "; ... " + problems.size() + " in all" : ""));
    }

    private List<String> judge(List<Integer> group, Map<String, Set<String>> consolidatedRefs) {
        if (group.size() == 1) {
            return single(group.get(0));
        }
        Set<String> expected = new TreeSet<>();
        Set<String> entities = new LinkedHashSet<>();
        for (int i : group) {
            Event e = observations.get(i);
            expected.add(e.text("assembly", "occurrence_uid"));
            entities.add(e.text("assembly", "entity_uid") + " role " + e.assemblyRole());
        }
        Event first = observations.get(group.get(0));
        Set<String> refs = consolidatedRefs.get(first.text("assembly", "entity_uid"));
        boolean ok = entities.size() == 1 && Integer.valueOf(2).equals(first.assemblyRole()) && expected.equals(refs);
        return ok ? List.of() : List.of("observations " + expected + " are one entity by their native identities; the file has " + entities + " consolidated from " + refs);
    }

    /** One observation of its entity: a canonical single observation, flagged when its identity is ambiguous. */
    private List<String> single(int i) {
        Event e = observations.get(i);
        boolean flagged = ambiguous.contains(i);
        Integer relation = e.integer("assembly", "relation_class_id");
        boolean ok = Integer.valueOf(1).equals(e.assemblyRole()) && flagged == Integer.valueOf(6).equals(relation);
        return ok ? List.of() : List.of(e + " should be a canonical single observation" + (flagged ? " flagged ambiguous" : "") + ", is role " + e.assemblyRole() + " relation " + relation);
    }

    private int find(int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private void union(int a, int b) {
        parent[find(a)] = find(b);
    }
}
