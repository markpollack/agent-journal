package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
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
 */
final class AssemblyPartition {

    private final List<Event> observations;
    private final int[] parent;
    private final Set<Integer> ambiguous = new LinkedHashSet<>();
    private final List<String> unmatched = new ArrayList<>();
    private final Map<String, List<Integer>> groups = new LinkedHashMap<>();
    private final Set<String> ambiguousGroups = new LinkedHashSet<>();

    private AssemblyPartition(List<Event> observations) {
        this.observations = observations;
        this.parent = new int[observations.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
    }

    static void check(OracleReport report, OcsfPackage pkg, Json spec, List<Supplied> accepted) {
        AssemblyPartition p = new AssemblyPartition(pkg.observations().stream().filter(e -> e.className != null).toList());
        for (Json entity : spec.member("entities").elements()) {
            for (Supplied s : accepted) {
                for (Json locator : locators(entity, s.role())) {
                    p.key(entity, s, locator);
                }
            }
        }
        p.copies(accepted);
        p.compare(report, AssemblyConsolidation.refsByEntity(pkg));
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
            Set<Integer> records = new TreeSet<>();
            k.getValue().forEach(r -> records.add(r.index()));
            List<Integer> members = new ArrayList<>();
            for (int r : records) {
                members.addAll(observationsOf(entity, s.name(), r, keysPerRecord.get(r) > 1 ? k.getKey() : null));
            }
            String group = RawInventory.text(entity, "name") + "|" + k.getKey();
            if (records.size() > 1 && repeatsAStage(members)) {
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

    /**
     * Whether two native records of one source report the identity at the same lifecycle stage (the activity of the
     * observations they produced). Records at different stages (a request and its completion) are one entity's
     * lifecycle, not a repeated identity.
     */
    private boolean repeatsAStage(List<Integer> members) {
        Map<Integer, Set<Integer>> recordsByStage = new LinkedHashMap<>();
        for (int i : members) {
            Event e = observations.get(i);
            recordsByStage.computeIfAbsent(e.activityId, a -> new TreeSet<>()).add(e.recordIndex());
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
        }
        String detail = byRoot.size() + " entities from the native identities, " + multi + " observed by more than one occurrence, " + ambiguous.size()
                + " ambiguous observation(s)" + (unmatched.isEmpty() ? "" : "; native identities with no observation of their class: " + unmatched);
        report.add(Category.NORMALIZATION, "assembly.entities", AssemblyOracle.OWNER, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
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
