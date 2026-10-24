package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;

/**
 * The {@code assembly} declaration of a finally assembled file, written once before its events: the profile by value
 * (its id, version, status, roles, joins and precedence), the engine and its contract, the original packages the same
 * acquisition produced (lineage), every declared source with its role, requirement, acquisition origin, extent and
 * hash, the identity the gate established and its verdict, and the result: entities, consolidated and canonical counts,
 * conflicts, ambiguity, refused sources and missing required roles, with the assembly status they give.
 */
final class AssemblyDeclaration {

    private AssemblyDeclaration() {
    }

    static Jv.Obj payload(AssemblyPlan plan, Map<String, AssemblyGate.Verdict> verdicts, Map<String, AssemblyGate.SourceFacts> facts, List<AssemblyGrouping.Group> groups,
            List<EventMerge.Result> merged, String contractUid) {
        Jv.Arr sources = new Jv.Arr();
        for (AssemblyPlan.Binding b : plan.bindings()) {
            sources.add(source(plan, b, verdicts.get(b.artifact()), facts.get(b.artifact())));
        }
        Jv.Arr missing = new Jv.Arr();
        for (AssemblyProfile.Role r : plan.profile().roles()) {
            boolean bound = plan.bindings().stream().anyMatch(b -> b.role().equals(r.role()) && verdicts.get(b.artifact()) != null && verdicts.get(b.artifact()).accepted());
            if (r.required() && !bound) {
                missing.add(new Jv.Str(r.role()));
            }
        }
        Jv.Obj result = result(verdicts, groups, merged);
        String status = !missing.elements().isEmpty() ? "incomplete" : ((Jv.Num) result.get("conflicting_entities")).value().signum() > 0 ? "complete with conflicts" : "complete";
        result.put("missing_required_roles", missing).put("status", status)
                .put("status_meaning", "complete: every required role was acquired, belongs to this execution and was assembled; it is relative to the declared sources and does not claim the vendor reported nothing else");
        return new Jv.Obj().put("profile", plan.profile().document()).put("engine", new Jv.Obj().put("name", Assembler.ENGINE).put("version", Assembler.ENGINE_VERSION).put("contract_uid", contractUid))
                .put("lineage", plan.lineage()).put("run_session_uid", AssemblyGate.runSession(verdicts)).put("sources", sources).put("result", result)
                .put("identity_layers", "acquisition (run_uid), source occurrence (assembly.occurrence_uid: artifact, record and emit ordinal), logical entity and stage (assembly.entity_uid in a declared namespace) and provider or protocol identities (tool_call_uid, response_uid, protocol objects) are distinct; a repeated tool call id across start and completion is two stages of one entity, never a duplicate")
                .put("idempotence", "a source is identified by its acquisition origin and its sha256: replaying the same origin and bytes adds nothing; identical bytes from distinct origins stay distinct sources whose observations consolidate");
    }

    private static Jv.Obj source(AssemblyPlan plan, AssemblyPlan.Binding b, AssemblyGate.Verdict v, AssemblyGate.SourceFacts f) {
        AssemblyProfile.Role role = plan.profile().role(b.role());
        Jv.Obj s = new Jv.Obj().put("artifact", b.artifact()).put("role", b.role()).put("requirement", role.requirement()).put("profile", role.profile()).put("route", role.route())
                .put("origin", b.origin());
        if (f != null) {
            s.put("sha256", f.sha256()).put("records", f.records()).put("bytes", f.bytes()).put("ended", f.ended());
        }
        if (role.json().member("content_settings") != null) {
            s.put("content_settings", role.json().member("content_settings"));
        }
        if (v != null) {
            Jv.Arr ids = new Jv.Arr();
            v.sessions().forEach(x -> ids.add(new Jv.Str(x)));
            s.put("session_identities", ids).put("identity_basis", v.identityBasis()).put("accepted", v.accepted()).put("verdict", v.reason());
        }
        return s;
    }

    private static Jv.Obj result(Map<String, AssemblyGate.Verdict> verdicts, List<AssemblyGrouping.Group> groups, List<EventMerge.Result> merged) {
        int singles = 0;
        int supporting = 0;
        int ambiguous = 0;
        int conflicting = 0;
        int consolidated = 0;
        for (int i = 0; i < groups.size(); i++) {
            AssemblyGrouping.Group g = groups.get(i);
            if (merged.get(i) != null) {
                consolidated++;
                supporting += g.members().size();
                conflicting += merged.get(i).relation() == 5 ? 1 : 0;
            }
            else {
                singles += g.members().size();
                ambiguous += g.ambiguous() ? 1 : 0;
            }
        }
        Jv.Arr refused = new Jv.Arr();
        verdicts.values().stream().filter(v -> !v.accepted()).forEach(v -> refused.add(new Jv.Obj().put("artifact", v.artifact()).put("reason", v.reason())));
        return new Jv.Obj().put("entities", groups.size()).put("consolidated", consolidated).put("canonical_single_observations", singles)
                .put("supporting_observations", supporting).put("canonical_events", singles + consolidated).put("conflicting_entities", conflicting)
                .put("ambiguous_entities", ambiguous).put("refused_sources", refused);
    }
}
