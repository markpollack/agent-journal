package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Which declared sources belong to the execution being assembled. A source's session identity is what its own
 * observations report ({@code session_uid}), or, for a role whose identity is held by a companion artifact (Junie's
 * session events, identified by the summary.json beside them), that companion's, provided both inputs are declared in
 * the same acquisition. The run's identity is the one the sources in precedence order establish first, and the source
 * that establishes it is the run's anchor; a source that reports another identity, or several, is refused as another
 * execution; a required source that establishes none is refused as unestablished. A source that reports no identity
 * (a role declared {@code acquisition}, or an optional one) belongs only when its declared acquisition is the anchor's:
 * requirement level or role name is never membership evidence. A declared acquisition is the caller's assertion and is
 * declared as such.
 *
 * <p>Import identity: a source acquired from the same origin with the same bytes as one already accepted is the same
 * import and contributes nothing again. Identical bytes from a distinct origin are related to the earlier source only
 * by a declared copy lineage ({@link AssemblyPlan.Binding#copyOf()}), which the equal bytes then verify; without one,
 * equal content establishes no occurrence relation, and since consolidating by position or counting the copy again
 * would each be a guess, the source is refused with that reason.
 */
final class AssemblyGate {

    /** The gate's finding for one declared artifact. */
    record Verdict(String artifact, String role, boolean accepted, String reason, Set<String> sessions, String identityBasis) {
    }

    /**
     * What the writer knows about a declared source when it has ended.
     *
     * @param reportedSessions the session identities the source's own records reported, when its mapper states them
     *        apart from the session its events carry; {@code null} when the events' session_uid is what they report
     */
    record SourceFacts(String artifact, String sha256, int records, long bytes, String ended, java.util.Set<String> reportedSessions) {

        SourceFacts(String artifact, String sha256, int records, long bytes, String ended) {
            this(artifact, sha256, records, bytes, ended, null);
        }
    }

    private AssemblyGate() {
    }

    static Map<String, Verdict> decide(AssemblyPlan plan, List<Assembler.Observation> observations, Map<String, SourceFacts> facts) {
        Map<String, Set<String>> sessions = reported(observations);
        facts.values().stream().filter(f -> f.reportedSessions() != null).forEach(f -> sessions.put(f.artifact(), f.reportedSessions()));
        List<AssemblyPlan.Binding> ordered = new ArrayList<>(plan.bindings());
        ordered.sort((a, b) -> Integer.compare(plan.rank(a.artifact()), plan.rank(b.artifact())));
        Map<String, Verdict> out = new LinkedHashMap<>();
        // 1. imports, undeclared equal bytes, and every source with an identity of its own or its companion's
        AssemblyPlan.Binding anchor = identified(plan, ordered, sessions, facts, out);
        // 2. sources without an identity: members only through the anchor's declared acquisition
        for (AssemblyPlan.Binding b : ordered) {
            if (!out.containsKey(b.artifact()) && b.copyOf() == null) {
                out.put(b.artifact(), identityFree(b, plan.profile().role(b.role()), anchor));
            }
        }
        // 3. declared copies, after their originals are decided
        for (AssemblyPlan.Binding b : ordered) {
            if (b.copyOf() != null && !out.containsKey(b.artifact())) {
                out.put(b.artifact(), copy(b, out.get(b.copyOf()), facts));
            }
        }
        Map<String, Verdict> inPlanOrder = new LinkedHashMap<>();
        ordered.forEach(b -> inPlanOrder.put(b.artifact(), out.get(b.artifact())));
        return inPlanOrder;
    }

    /**
     * Decides every repeated import, undeclared equal bytes and source with an identity into {@code out}, in precedence
     * order; returns the anchor, the first accepted source that establishes the run's identity.
     */
    private static AssemblyPlan.Binding identified(AssemblyPlan plan, List<AssemblyPlan.Binding> ordered, Map<String, Set<String>> sessions,
            Map<String, SourceFacts> facts, Map<String, Verdict> out) {
        Map<String, String> imports = new LinkedHashMap<>();
        Map<String, String> firstBySha = new LinkedHashMap<>();
        AssemblyPlan.Binding anchor = null;
        for (AssemblyPlan.Binding b : ordered) {
            AssemblyProfile.Role role = plan.profile().role(b.role());
            Set<String> own = identity(b, role, plan, sessions);
            Verdict v = screened(b, role, own, sha(facts, b.artifact()), imports, firstBySha);
            if (v == null && b.copyOf() == null && !own.isEmpty()) {
                v = identified(b, role, own, runOf(anchor, out));
                anchor = establishes(anchor, v) ? b : anchor;
            }
            if (v != null) {
                out.put(b.artifact(), v);
            }
        }
        return anchor;
    }

    /** The run's session identity, as the anchor established it, or {@code null} before any source did. */
    private static String runOf(AssemblyPlan.Binding anchor, Map<String, Verdict> out) {
        return anchor == null ? null : out.get(anchor.artifact()).sessions().iterator().next();
    }

    /** Whether this verdict makes its source the anchor: the first accepted source with exactly one identity. */
    private static boolean establishes(AssemblyPlan.Binding anchor, Verdict v) {
        return anchor == null && v.accepted() && v.sessions().size() == 1;
    }

    /** A repeated import, or equal bytes from another origin with no declared copy lineage; {@code null} otherwise. */
    private static Verdict screened(AssemblyPlan.Binding b, AssemblyProfile.Role role, Set<String> own, String sha, Map<String, String> imports, Map<String, String> firstBySha) {
        String duplicateOf = imports.putIfAbsent(b.origin() + "#" + sha, b.artifact());
        if (duplicateOf != null) {
            return new Verdict(b.artifact(), b.role(), false, "the same import as " + duplicateOf + " (same origin " + b.origin() + " and sha256): replaying it adds nothing", own, basis(b, role, null));
        }
        if (b.copyOf() != null) {
            return null;
        }
        String sameBytes = sha.isEmpty() ? null : firstBySha.putIfAbsent(sha, b.artifact());
        return sameBytes == null ? null : new Verdict(b.artifact(), b.role(), false, "identical bytes to " + sameBytes + " from another origin (" + b.origin()
                + ") with no declared copy lineage: equal content does not establish that they are one native occurrence, and consolidating it by position"
                + " or counting it again would each be a guess", own, basis(b, role, null));
    }

    /** A source that reports an identity: one execution's, agreeing with the run's so far. */
    private static Verdict identified(AssemblyPlan.Binding b, AssemblyProfile.Role role, Set<String> own, String run) {
        String basis = basis(b, role, null);
        if (own.size() > 1) {
            return new Verdict(b.artifact(), b.role(), false, "reports more than one session identity " + own + ": not one execution", own, basis);
        }
        if (run != null && !run.equals(own.iterator().next())) {
            return new Verdict(b.artifact(), b.role(), false, "reports session " + own.iterator().next() + ", another execution than " + run, own, basis);
        }
        return new Verdict(b.artifact(), b.role(), true, "the session identity agrees with the run's", own, basis);
    }

    /** A source that reports no identity: refused when required (unless acquisition-bound), else a member only by the anchor's acquisition. */
    private static Verdict identityFree(AssemblyPlan.Binding b, AssemblyProfile.Role role, AssemblyPlan.Binding anchor) {
        String refused = refusal(b, role, anchor);
        if (refused != null) {
            return new Verdict(b.artifact(), b.role(), false, refused, Set.of(), basis(b, role, anchor));
        }
        return new Verdict(b.artifact(), b.role(), true, "acquisition".equals(role.identity()) ? "bound by the acquisition " + b.acquisition() + " that also acquired " + anchor.artifact()
                : "establishes no session identity; a member by the acquisition " + b.acquisition() + " it shares with " + anchor.artifact() + ", its facts scoped to it", Set.of(), basis(b, role, anchor));
    }

    private static String declared(String acquisition) {
        return acquisition == null ? "(none declared)" : acquisition;
    }

    /** Why a source without an identity is refused, or {@code null} when it is a member. */
    private static String refusal(AssemblyPlan.Binding b, AssemblyProfile.Role role, AssemblyPlan.Binding anchor) {
        if (role.identity().startsWith("companion:") && role.required()) {
            return "a required source establishes no session identity: no " + role.identity().substring("companion:".length()) + " source in the same declared acquisition "
                    + declared(b.acquisition()) + " reports one";
        }
        if (role.required() && !"acquisition".equals(role.identity())) {
            return "a required source establishes no session identity";
        }
        if (anchor == null) {
            return "reports no session identity and no source established the run's: membership not established";
        }
        if (b.acquisition() == null || !b.acquisition().equals(anchor.acquisition())) {
            return "reports no session identity, and its declared acquisition " + declared(b.acquisition()) + " is not that of " + anchor.artifact()
                    + ", which established the run (" + declared(anchor.acquisition()) + "): membership not established";
        }
        return null;
    }

    /** A declared copy: the original's verdict when the bytes are equal; refused when they differ or the original is not an earlier input. */
    private static Verdict copy(AssemblyPlan.Binding b, Verdict original, Map<String, SourceFacts> facts) {
        String basis = "a declared copy of " + b.copyOf() + " (caller assertion), verified by byte equality";
        if (original == null) {
            return new Verdict(b.artifact(), b.role(), false, "declared a copy of " + b.copyOf() + ", which is not another input of this assembly", Set.of(), basis);
        }
        if (!Objects.equals(sha(facts, b.artifact()), sha(facts, b.copyOf())) || sha(facts, b.artifact()).isEmpty()) {
            return new Verdict(b.artifact(), b.role(), false, "declared a copy of " + b.copyOf() + ", but its bytes differ: not a copy", Set.of(), basis);
        }
        if (!b.role().equals(original.role())) {
            return new Verdict(b.artifact(), b.role(), false, "declared a copy of " + b.copyOf() + ", which fills role " + original.role() + ", not " + b.role(), Set.of(), basis);
        }
        return new Verdict(b.artifact(), b.role(), original.accepted(), original.accepted()
                ? "a declared copy of " + b.copyOf() + " with equal bytes: the same native records acquired again from " + b.origin() + ", one entity with the original's, never extra executions"
                : "a declared copy of " + b.copyOf() + ", which is refused (" + original.reason() + ")", original.sessions(), basis);
    }

    private static String sha(Map<String, SourceFacts> facts, String artifact) {
        return facts.containsKey(artifact) ? facts.get(artifact).sha256() : "";
    }

    /** The run's session identity: the one the accepted sources report. */
    static String runSession(Map<String, Verdict> verdicts) {
        for (Verdict v : verdicts.values()) {
            if (v.accepted() && v.sessions().size() == 1) {
                return v.sessions().iterator().next();
            }
        }
        return null;
    }

    private static Map<String, Set<String>> reported(List<Assembler.Observation> observations) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Assembler.Observation o : observations) {
            Set<String> s = out.computeIfAbsent(o.artifact(), k -> new LinkedHashSet<>());
            if (o.event().get("session_uid") instanceof Jv.Str id) {
                s.add(id.value());
            }
        }
        return out;
    }

    /**
     * The identity of one bound artifact: what its own observations report, or what its companions report, counting
     * only companions declared in the same acquisition (never a companion role of another acquisition).
     */
    private static Set<String> identity(AssemblyPlan.Binding b, AssemblyProfile.Role role, AssemblyPlan plan, Map<String, Set<String>> sessions) {
        if (!role.identity().startsWith("companion:")) {
            return sessions.getOrDefault(b.artifact(), Set.of());
        }
        String companion = role.identity().substring("companion:".length());
        Set<String> out = new LinkedHashSet<>();
        for (AssemblyPlan.Binding c : plan.bindings()) {
            if (c.role().equals(companion) && b.acquisition() != null && b.acquisition().equals(c.acquisition())) {
                out.addAll(sessions.getOrDefault(c.artifact(), Set.of()));
            }
        }
        return out;
    }

    private static String basis(AssemblyPlan.Binding b, AssemblyProfile.Role role, AssemblyPlan.Binding anchor) {
        if (role.identity().startsWith("companion:")) {
            return "the session identity the companion source " + role.identity().substring("companion:".length()) + " reports, related by the declared acquisition "
                    + (b.acquisition() == null ? "(none declared)" : b.acquisition()) + " (caller assertion)";
        }
        if ("acquisition".equals(role.identity())) {
            return "bound by its declared acquisition " + (b.acquisition() == null ? "(none declared)" : b.acquisition()) + " (caller assertion), which must be the run anchor's"
                    + (anchor == null ? "" : " (" + anchor.artifact() + ")") + "; it reports no session identity";
        }
        return "the session_uid its own observations report";
    }
}
