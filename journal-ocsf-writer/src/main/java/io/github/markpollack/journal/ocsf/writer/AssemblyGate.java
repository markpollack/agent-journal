package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which declared sources belong to the execution being assembled. A source's session identity is what its own
 * observations report ({@code session_uid}), or, for a role whose identity is held by a companion artifact (Junie's
 * session events, identified by the summary.json beside them), that companion's. The run's identity is the one the
 * sources in precedence order establish first; a source that reports another identity, or several, is refused as
 * another execution; a required source that establishes none is refused as unestablished; a source acquired from the
 * same origin with the same bytes as one already accepted is the same import and contributes nothing again. Identical
 * bytes from distinct origins are distinct sources. A role declared {@code acquisition} is bound by the capture that
 * acquired it, not by a reported identity, and says so.
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
        String[] run = {null};
        Map<String, String> imports = new LinkedHashMap<>();
        Map<String, Verdict> out = new LinkedHashMap<>();
        for (AssemblyPlan.Binding b : ordered) {
            AssemblyProfile.Role role = plan.profile().role(b.role());
            String importKey = b.origin() + "#" + (facts.containsKey(b.artifact()) ? facts.get(b.artifact()).sha256() : "");
            Verdict v = verdict(b, role, identity(b, role, plan, sessions), imports.putIfAbsent(importKey, b.artifact()), run[0]);
            if (v.accepted() && run[0] == null && v.sessions().size() == 1) {
                run[0] = v.sessions().iterator().next();
            }
            out.put(b.artifact(), v);
        }
        return out;
    }

    /** One source's verdict given its identity, whether it repeats an import, and the run's identity so far. */
    private static Verdict verdict(AssemblyPlan.Binding b, AssemblyProfile.Role role, Set<String> own, String duplicateOf, String run) {
        String basis = basis(role);
        if (duplicateOf != null) {
            return new Verdict(b.artifact(), b.role(), false, "the same import as " + duplicateOf + " (same origin " + b.origin() + " and sha256): replaying it adds nothing", own, basis);
        }
        if (own.size() > 1) {
            return new Verdict(b.artifact(), b.role(), false, "reports more than one session identity " + own + ": not one execution", own, basis);
        }
        if (own.isEmpty() && !"acquisition".equals(role.identity())) {
            return new Verdict(b.artifact(), b.role(), !role.required(), role.required() ? "a required source establishes no session identity"
                    : "establishes no session identity; accepted as optional, its facts are scoped to the declared acquisition", own, basis);
        }
        if (!own.isEmpty() && run != null && !run.equals(own.iterator().next())) {
            return new Verdict(b.artifact(), b.role(), false, "reports session " + own.iterator().next() + ", another execution than " + run, own, basis);
        }
        return new Verdict(b.artifact(), b.role(), true, "the session identity agrees with the run's", own, basis);
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

    /** The identity of one bound artifact: what its own observations report, or what its companion's report. */
    private static Set<String> identity(AssemblyPlan.Binding b, AssemblyProfile.Role role, AssemblyPlan plan, Map<String, Set<String>> sessions) {
        if (!role.identity().startsWith("companion:")) {
            return sessions.getOrDefault(b.artifact(), Set.of());
        }
        String companion = role.identity().substring("companion:".length());
        Set<String> out = new LinkedHashSet<>();
        for (AssemblyPlan.Binding c : plan.bindings()) {
            if (c.role().equals(companion)) {
                out.addAll(sessions.getOrDefault(c.artifact(), Set.of()));
            }
        }
        return out;
    }

    private static String basis(AssemblyProfile.Role role) {
        if (role.identity().startsWith("companion:")) {
            return "the session identity the companion source " + role.identity().substring("companion:".length()) + " reports (the same native session directory)";
        }
        return "acquisition".equals(role.identity()) ? "bound by the acquisition that captured it (the launch's own output location); it reports no session identity"
                : "the session_uid its own observations report";
    }
}
