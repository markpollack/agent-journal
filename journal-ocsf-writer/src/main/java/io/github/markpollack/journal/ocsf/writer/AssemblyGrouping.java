package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups observations of different sources into logical entities by the profile's declared joins, and nothing else:
 * two observations are one entity at one stage only when a join applies to both and they carry equal values at every
 * key pointer, directly or through a chain of such joins (a bridge through a third source). Position, timestamps and
 * similar text never relate observations. A group in which one source contributes two observations from the same
 * mapping rule is ambiguous (the identity repeats within that source) and is not consolidated; observations of one
 * source from different rules (a message and its usage record) are complementary views of one entity.
 */
final class AssemblyGrouping {

    /** One entity: its namespaced identity, members in emission order, and whether its pairing is ambiguous. */
    record Group(String entity, List<Assembler.Observation> members, boolean ambiguous, List<Integer> stageOrder) {

        /**
         * The member whose lifecycle stage the consolidated event takes: under a join that orders stages (a record a
         * stream shows provisionally and a session file shows final), the most advanced stage; otherwise the primary.
         */
        int envelope() {
            int best = 0;
            for (int i = 1; i < members.size(); i++) {
                if (stageOrder.indexOf(activity(members.get(i).event())) > stageOrder.indexOf(activity(members.get(best).event()))) {
                    best = i;
                }
            }
            return best;
        }
    }

    private AssemblyGrouping() {
    }

    static List<Group> group(AssemblyPlan plan, List<Assembler.Observation> observations) {
        return group(plan, observations, Map.of());
    }

    /**
     * @param copyOf for an accepted artifact whose bytes equal an earlier accepted artifact's (identical bytes from a
     *        distinct origin), that earlier artifact: the copies' observations of the same record and emit ordinal are
     *        one entity, so a copy never becomes extra executions while both origins keep their provenance
     */
    static List<Group> group(AssemblyPlan plan, List<Assembler.Observation> observations, Map<String, String> copyOf) {
        int n = observations.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        Map<Integer, String> entityOf = new LinkedHashMap<>();
        Map<Integer, List<Integer>> stagesOf = new LinkedHashMap<>();
        Map<String, Integer> firstWithKey = new LinkedHashMap<>();
        Map<String, AssemblyPlan.Binding> bindings = plan.byArtifact();
        for (AssemblyProfile.Join j : plan.profile().joins()) {
            for (int i = 0; i < n; i++) {
                String key = key(j, observations.get(i), bindings);
                if (key != null) {
                    entityOf.putIfAbsent(i, key);
                    stagesOf.putIfAbsent(i, j.stageOrder());
                    unionFirst(parent, firstWithKey, key, i);
                }
            }
        }
        unionCopies(parent, observations, copyOf);
        Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<Group> out = new ArrayList<>();
        byRoot.values().forEach(members -> out.add(group(members, observations, entityOf, stagesOf)));
        return out;
    }

    private static void unionFirst(int[] parent, Map<String, Integer> firstWithKey, String key, int i) {
        Integer first = firstWithKey.putIfAbsent(key, i);
        if (first != null) {
            union(parent, first, i);
        }
    }

    /** One entity from its members: its declared identity, or for copies without one, its first occurrence. */
    private static Group group(List<Integer> members, List<Assembler.Observation> observations, Map<Integer, String> entityOf, Map<Integer, List<Integer>> stagesOf) {
        List<Assembler.Observation> obs = new ArrayList<>();
        members.forEach(i -> obs.add(observations.get(i)));
        String entity = members.stream().map(entityOf::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        if (entity == null && obs.size() > 1) {
            // no declared identity: copies of one record from distinct origins are one entity named by its first occurrence
            entity = obs.get(0).className() + "/occurrence:" + obs.get(0).occurrence();
        }
        return new Group(entity, obs, ambiguous(obs), stagesOf.getOrDefault(members.get(0), List.of()));
    }

    /** The namespaced key of an observation under a join, or {@code null} when the join does not apply to it. */
    static String key(AssemblyProfile.Join j, Assembler.Observation o, Map<String, AssemblyPlan.Binding> bindings) {
        Jv.Obj e = o.event();
        AssemblyPlan.Binding b = bindings.get(o.artifact());
        boolean staged = !j.stageOrder().isEmpty();
        boolean applies = b != null && (j.roles().isEmpty() || j.roles().contains(b.role())) && j.className().equals(o.className())
                && (j.activities().isEmpty() || j.activities().contains(activity(e))) && (!staged || j.stageOrder().contains(activity(e)))
                && (j.rules().isEmpty() || j.rules().contains(rule(e)));
        if (!applies) {
            return null;
        }
        // the core (root or a child's owner) is part of the key: observations of different cores are never one entity
        // a staged join relates one occurrence across its lifecycle stages (the stage is not part of its key)
        StringBuilder key = new StringBuilder(o.owner() == null ? "" : o.owner() + ">").append(j.className()).append('/').append(staged ? "stage" : Integer.toString(activity(e)))
                .append('/').append(j.namespace()).append(':');
        for (String pointer : j.key()) {
            Jv v = Assembler.at(e, pointer);
            if (v == null || v.isNull()) {
                return null;
            }
            key.append(v instanceof Jv.Str s ? s.value() : Jv.write(v)).append('|');
        }
        return key.substring(0, key.length() - 1);
    }

    private static void unionCopies(int[] parent, List<Assembler.Observation> observations, Map<String, String> copyOf) {
        Map<String, Integer> byOccurrence = new LinkedHashMap<>();
        for (int i = 0; i < observations.size(); i++) {
            byOccurrence.put(observations.get(i).occurrence(), i);
        }
        for (int i = 0; i < observations.size(); i++) {
            Assembler.Observation o = observations.get(i);
            String original = copyOf.get(o.artifact());
            Integer first = original == null ? null : byOccurrence.get(original + "#" + o.record() + "/" + o.ordinal());
            if (first != null) {
                union(parent, first, i);
            }
        }
    }

    private static boolean ambiguous(List<Assembler.Observation> members) {
        Set<String> seen = new LinkedHashSet<>();
        for (Assembler.Observation o : members) {
            if (!seen.add(o.artifact() + "#" + rule(o.event()))) {
                return true;
            }
        }
        return false;
    }

    static int activity(Jv.Obj e) {
        return e.get("activity_id") instanceof Jv.Num n ? n.value().intValue() : 0;
    }

    static String rule(Jv.Obj e) {
        Jv r = e.path("journal", "rule_uid");
        return r == null ? "" : r.text();
    }

    private static int find(int[] parent, int i) {
        int r = i;
        while (parent[r] != r) {
            r = parent[r];
        }
        return r;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}
