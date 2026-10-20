package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The common analysis views, computed from the core alone (events plus the manifest's semantic contract) by the
 * named operations in {@link SemanticOperation}. Every row carries the rule it applied and the occurrences it read,
 * so a derived result can be explained from the saved data. Nothing is reconciled or summed: conflicting
 * observations are reported as conflicts, and missing identities as unresolved.
 */
public final class AnalysisViews {

    static final Map<String, String> SCOPE = Map.of("0", "Unknown", "1", "Tool call", "2", "Model response",
            "3", "Prompt turn", "4", "Run", "5", "Session", "6", "Model", "7", "Child run");
    static final Map<String, String> OUTCOME = Map.of("0", "Unknown", "1", "Unique", "2", "Ambiguous", "3", "Unattributed");
    static final Map<String, String> METHOD = Map.of("0", "Unknown", "1", "Reported identifier", "2", "Derived by rule");
    static final Map<String, String> STATUS = Map.of("0", "Unknown", "1", "Provisional", "2", "Final");
    static final List<String> COMPONENTS = List.of("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens",
            "reasoning_tokens", "reported_total_tokens");

    private AnalysisViews() {
    }

    /** All views of a package and, under {@code children}, of each linked child, each computed on its own core. */
    public static Json all(OcsfPackage pkg) {
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("run_uid", str(pkg.manifest.runUid()));
        out.put("label", str(pkg.label()));
        out.put("submissions", submissions(pkg));
        out.put("responses", responses(pkg));
        out.put("usage", usage(pkg));
        out.put("associations", associations(pkg));
        out.put("tool_exchanges", toolExchanges(pkg));
        List<Json> children = new ArrayList<>();
        for (OcsfPackage c : pkg.children) {
            children.add(all(c));
        }
        out.put("children", new Json.JArray(children));
        return new Json.JObject(out);
    }

    private static Json declaration(OcsfPackage pkg, SemanticOperation op) {
        Json d = SemanticContract.declaration(pkg.manifest.semanticContract(), op.name());
        if (d == null) {
            throw new IllegalStateException("the package declares no " + op + " operation; the view is unavailable from this core");
        }
        return d;
    }

    // ------------------------------------------------------------------------------------------ submissions

    public static Json submissions(OcsfPackage pkg) {
        Json decl = declaration(pkg, SemanticOperation.SUBMISSION_IDENTITY);
        Map<String, Map<String, Json>> rows = new LinkedHashMap<>();
        int anonymous = 0;
        for (Event e : pkg.events("prompt_turn", 1)) {
            String text = blockText(e.elements("content_blocks"));
            Json turn = e.get("turn_uid");
            String turnUid = turn == null || turn.isNull() ? null : turn.text();
            String label = "submission";
            boolean counts = true;
            String rule = SchemaCatalog.text(decl, "id");
            for (Json c : decl.member("classifiers") == null ? List.<Json>of() : decl.member("classifiers").elements()) {
                String contract = SchemaCatalog.text(c, "contract_uid");
                String prefix = SchemaCatalog.text(c, "first_block_starts_with");
                List<Json> blocks = e.elements("content_blocks");
                String first = blocks.isEmpty() ? null : SchemaCatalog.text(blocks.get(0), "text");
                if (contract != null && contract.equals(e.contractUid()) && prefix != null && first != null && first.startsWith(prefix)) {
                    label = SchemaCatalog.text(c, "label");
                    counts = c.member("counts_as_submission") instanceof Json.JBool b && b.value();
                    rule = SchemaCatalog.text(c, "rule");
                }
            }
            String basis;
            String key;
            if (!counts) {
                basis = "classifier " + rule;
                key = "ctx:" + (++anonymous);
            }
            else if (turnUid != null && text != null) {
                basis = "turn_uid + exact text";
                key = "turn:" + turnUid + "|text:" + sha(text);
            }
            else if (turnUid != null) {
                basis = "turn_uid (content unavailable at source)";
                key = "turn:" + turnUid;
            }
            else {
                basis = text == null ? "occurrence (no turn id; content unavailable at source)" : "occurrence (no turn id; a repeated identical text cannot be told from a new submission)";
                key = "occ:" + (++anonymous);
            }
            final String fLabel = label;
            final boolean fCounts = counts;
            final String fRule = rule;
            final String fBasis = basis;
            Map<String, Json> row = rows.computeIfAbsent(key, k -> {
                Map<String, Json> r = new LinkedHashMap<>();
                r.put("classification", str(fLabel));
                r.put("counts_as_submission", new Json.JBool(fCounts));
                r.put("identity_basis", str(fBasis));
                r.put("turn_uid", turnUid == null ? Json.JNull.INSTANCE : str(turnUid));
                r.put("text_sha256", text == null ? Json.JNull.INSTANCE : str(sha(text)));
                r.put("text_chars", text == null ? Json.JNull.INSTANCE : num(text.length()));
                r.put("occurrences", new Json.JArray(List.of()));
                r.put("rule", str(fRule));
                return r;
            });
            ((Json.JArray) row.get("occurrences")).elements().add(occurrence(e));
        }
        List<Json> list = new ArrayList<>();
        int submissions = 0;
        for (Map<String, Json> r : rows.values()) {
            if (((Json.JBool) r.get("counts_as_submission")).value()) {
                submissions++;
            }
            list.add(new Json.JObject(r));
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("submission_count", num(submissions));
        out.put("rows", new Json.JArray(list));
        out.put("rule", str(SchemaCatalog.text(decl, "id")));
        return new Json.JObject(out);
    }

    private static String blockText(List<Json> blocks) {
        if (blocks.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        for (Json b : blocks) {
            String t = SchemaCatalog.text(b, "text");
            if (t != null) {
                any = true;
                sb.append(t).append('\u0000');
            }
        }
        return any ? sb.toString() : null;
    }

    // ------------------------------------------------------------------------------------------ responses

    public static Json responses(OcsfPackage pkg) {
        Json decl = declaration(pkg, SemanticOperation.RESPONSE_ASSEMBLY);
        Map<String, Json> semantics = new LinkedHashMap<>();
        for (Json u : decl.member("update_semantics").elements()) {
            semantics.put(SchemaCatalog.text(u, "contract_uid"), u);
        }
        Map<String, List<Event>> groups = new LinkedHashMap<>();
        int unresolved = 0;
        for (Event e : pkg.events("model_response")) {
            Json u = semantics.get(e.contractUid());
            String scope = u == null ? "run" : SchemaCatalog.text(u, "identity_scope");
            Json uid = e.get("response_uid");
            String key;
            if (uid == null || uid.isNull()) {
                key = "unresolved:" + (++unresolved);
            }
            else {
                key = "artifact".equals(scope) ? e.artifact() + "|" + uid.text() : uid.text();
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Json> rows = new ArrayList<>();
        for (Map.Entry<String, List<Event>> g : groups.entrySet()) {
            List<Event> events = g.getValue();
            Event first = events.get(0);
            Json u = semantics.get(first.contractUid());
            Map<String, Json> row = new LinkedHashMap<>();
            Json uid = first.get("response_uid");
            row.put("response_uid", uid == null ? Json.JNull.INSTANCE : uid);
            row.put("identity_scope", str(u == null ? "run (no declared update semantics)" : SchemaCatalog.text(u, "identity_scope")));
            row.put("resolved", new Json.JBool(!g.getKey().startsWith("unresolved:")));
            row.put("occurrence_count", num(events.size()));
            Map<String, List<Event>> byArtifact = new LinkedHashMap<>();
            for (Event e : events) {
                byArtifact.computeIfAbsent(e.artifact(), k -> new ArrayList<>()).add(e);
            }
            Map<String, Json> artifacts = new LinkedHashMap<>();
            List<List<Json>> assemblies = new ArrayList<>();
            List<String> stopReasons = new ArrayList<>();
            for (Map.Entry<String, List<Event>> a : byArtifact.entrySet()) {
                Json us = semantics.get(a.getValue().get(0).contractUid());
                String mode = us == null ? "UNKNOWN" : SchemaCatalog.text(us, "mode");
                Map<String, Json> art = new LinkedHashMap<>();
                art.put("update_semantics", str(mode));
                List<Json> occ = new ArrayList<>();
                List<Json> assembled = new ArrayList<>();
                List<List<Json>> snapshots = new ArrayList<>();
                for (Event e : a.getValue()) {
                    Map<String, Json> o = new LinkedHashMap<>();
                    o.put("record_index", num(e.recordIndex()));
                    o.put("sequence", num(e.sequence));
                    o.put("activity", str(e.text("activity_name")));
                    List<Json> types = new ArrayList<>();
                    for (Json b : e.elements("content_blocks")) {
                        types.add(str(SchemaCatalog.text(b, "block_type")));
                    }
                    o.put("block_types", e.get("content_blocks") == null ? str("unavailable at source") : new Json.JArray(types));
                    Json stop = e.get("stop_reason");
                    o.put("stop_reason", stop == null ? str("unavailable at source") : stop);
                    occ.add(new Json.JObject(o));
                    if (stop != null && !stop.isNull() && e.activityId != null && e.activityId == 2 && !stopReasons.contains(stop.text())) {
                        stopReasons.add(stop.text());
                    }
                    if ("APPEND_BLOCKS".equals(mode)) {
                        for (Json b : e.elements("content_blocks")) {
                            Map<String, Json> ab = new LinkedHashMap<>();
                            ab.put("block_type", str(SchemaCatalog.text(b, "block_type")));
                            ab.put("from_record", num(e.recordIndex()));
                            ab.put("data_sha256", str(sha(NeutralJson.write(b.member("data") == null ? Json.JNull.INSTANCE : b.member("data")))));
                            assembled.add(new Json.JObject(ab));
                        }
                    }
                    else if ("SNAPSHOT".equals(mode)) {
                        snapshots.add(e.elements("content_blocks"));
                    }
                }
                art.put("occurrences", new Json.JArray(occ));
                if ("APPEND_BLOCKS".equals(mode)) {
                    art.put("assembled_blocks", new Json.JArray(assembled));
                    assemblies.add(assembled);
                }
                else if ("SNAPSHOT".equals(mode)) {
                    boolean textEqual = true;
                    boolean structureEqual = true;
                    for (List<Json> s : snapshots) {
                        if (!blockTexts(snapshots.get(0)).equals(blockTexts(s))) {
                            textEqual = false;
                        }
                        if (!Json.sameValue(new Json.JArray(blockData(snapshots.get(0))), new Json.JArray(blockData(s)))) {
                            structureEqual = false;
                        }
                    }
                    art.put("snapshot_consistency", str(snapshots.size() < 2 ? "single snapshot"
                            : !textEqual ? "text conflict" : structureEqual ? "text and native structure equal" : "text equal; native block structure differs"));
                    List<Json> last = new ArrayList<>();
                    for (Json b : snapshots.get(snapshots.size() - 1)) {
                        Map<String, Json> ab = new LinkedHashMap<>();
                        ab.put("block_type", str(SchemaCatalog.text(b, "block_type")));
                        ab.put("data_sha256", str(sha(NeutralJson.write(b.member("data") == null ? Json.JNull.INSTANCE : b.member("data")))));
                        last.add(new Json.JObject(ab));
                    }
                    art.put("assembled_blocks", new Json.JArray(last));
                    assemblies.add(last);
                }
                else {
                    art.put("assembled_blocks", str("unsupported: update semantics unknown for " + a.getValue().get(0).contractUid()));
                }
                artifacts.put(a.getKey(), new Json.JObject(art));
            }
            row.put("artifacts", new Json.JObject(artifacts));
            if (assemblies.size() > 1) {
                boolean same = true;
                for (List<Json> a : assemblies) {
                    if (!Json.sameValue(new Json.JArray(stripFrom(assemblies.get(0))), new Json.JArray(stripFrom(a)))) {
                        same = false;
                    }
                }
                row.put("cross_source", str(same ? "equal block types and content" : "differs"));
            }
            else {
                row.put("cross_source", str(byArtifact.size() > 1 ? "not comparable" : "single source"));
            }
            row.put("completed_stop_reasons", new Json.JArray(stopReasons.stream().map(AnalysisViews::str).toList()));
            row.put("rule", str(SchemaCatalog.text(decl, "id")));
            rows.add(new Json.JObject(row));
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("response_count", num(rows.size() - unresolved));
        out.put("unresolved_count", num(unresolved));
        out.put("rows", new Json.JArray(rows));
        out.put("rule", str(SchemaCatalog.text(decl, "id")));
        return new Json.JObject(out);
    }

    private static List<String> blockTexts(List<Json> blocks) {
        List<String> out = new ArrayList<>();
        for (Json b : blocks) {
            out.add(SchemaCatalog.text(b, "text"));
        }
        return out;
    }

    private static List<Json> blockData(List<Json> blocks) {
        List<Json> out = new ArrayList<>();
        for (Json b : blocks) {
            out.add(b.member("data") == null ? Json.JNull.INSTANCE : b.member("data"));
        }
        return out;
    }

    private static List<Json> stripFrom(List<Json> assembled) {
        List<Json> out = new ArrayList<>();
        for (Json a : assembled) {
            Map<String, Json> m = new LinkedHashMap<>(((Json.JObject) a).members());
            m.remove("from_record");
            out.add(new Json.JObject(m));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ usage

    public static Json usage(OcsfPackage pkg) {
        Json decl = declaration(pkg, SemanticOperation.FINAL_USAGE_SELECTION);
        Map<String, Map<String, Json>> groups = new LinkedHashMap<>();
        Map<String, List<Map<String, Json>>> observations = new LinkedHashMap<>();
        for (Event e : pkg.events) {
            if (e.className == null) {
                continue;
            }
            for (Json u : e.elements("usage_observations")) {
                String scope = scalar(u.member("scope_id"));
                String owner = switch (scope == null ? "" : scope) {
                    case "2" -> e.text("response_uid");
                    case "3" -> e.text("turn_uid");
                    default -> e.runUid();
                };
                final String fOwner = owner == null ? ("3".equals(scope) ? "turn" : "owner") + " unidentified in " + e.artifact() : owner;
                owner = fOwner;
                String model = SchemaCatalog.text(u.member("model_ref"), "name");
                String artifact = SchemaCatalog.text(u.member("source_ref"), "artifact");
                String key = scope + "|" + owner + "|" + model + "|" + artifact;
                groups.computeIfAbsent(key, k -> {
                    Map<String, Json> g = new LinkedHashMap<>();
                    g.put("scope", str(SCOPE.getOrDefault(scope, scope)));
                    g.put("owner", str(fOwner));
                    g.put("model", model == null ? Json.JNull.INSTANCE : str(model));
                    g.put("source", str(artifact));
                    return g;
                });
                Map<String, Json> obs = new LinkedHashMap<>();
                obs.put("record_index", u.path("source_ref", "record_index") == null ? Json.JNull.INSTANCE : u.path("source_ref", "record_index"));
                obs.put("event_sequence", num(e.sequence));
                String status = scalar(u.member("usage_status_id"));
                obs.put("status", str(STATUS.getOrDefault(status, status)));
                Map<String, Json> comps = new LinkedHashMap<>();
                for (String c : COMPONENTS) {
                    if (u.member(c) != null) {
                        comps.put(c, u.member(c));
                    }
                }
                obs.put("components", new Json.JObject(comps));
                observations.computeIfAbsent(key, k -> new ArrayList<>()).add(obs);
            }
        }
        List<Json> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Json>> g : groups.entrySet()) {
            Map<String, Json> row = g.getValue();
            List<Map<String, Json>> obs = observations.get(g.getKey());
            List<Json> finals = new ArrayList<>();
            int provisional = 0;
            int unknown = 0;
            for (Map<String, Json> o : obs) {
                String s = o.get("status").text();
                if ("Final".equals(s)) {
                    finals.add(o.get("components"));
                }
                else if ("Provisional".equals(s)) {
                    provisional++;
                }
                else {
                    unknown++;
                }
            }
            boolean conflict = false;
            for (Json f : finals) {
                if (!Json.sameValue(finals.get(0), f)) {
                    conflict = true;
                }
            }
            row.put("final_observations", num(finals.size()));
            row.put("repeated_final_observations", num(Math.max(0, finals.size() - 1)));
            row.put("provisional_observations", num(provisional));
            row.put("unknown_observations", num(unknown));
            row.put("conflict", new Json.JBool(conflict));
            row.put("applicable_final", finals.isEmpty() || conflict ? Json.JNull.INSTANCE : finals.get(0));
            row.put("applicable_final_basis", str(finals.isEmpty() ? "none: no Final observation in this group"
                    : conflict ? "none: Final observations disagree" : finals.size() + " equal Final observation(s), counted once"));
            List<Json> list = new ArrayList<>();
            for (Map<String, Json> o : obs) {
                list.add(new Json.JObject(o));
            }
            row.put("observations", new Json.JArray(list));
            rows.add(new Json.JObject(row));
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("group_count", num(rows.size()));
        out.put("groups", new Json.JArray(rows));
        out.put("aggregation", str("none: groups are never summed across scopes, sources, models or child runs"));
        out.put("rule", str(SchemaCatalog.text(decl, "id")));
        return new Json.JObject(out);
    }

    // ------------------------------------------------------------------------------------------ associations

    public static Json associations(OcsfPackage pkg) {
        Json decl = declaration(pkg, SemanticOperation.ASSOCIATION_EVIDENCE);
        List<Json> rows = new ArrayList<>();
        for (Event e : pkg.events("tool_call", 3)) {
            Json op = e.get("tool_operation");
            Json att = op == null ? null : op.member("attribution");
            Map<String, Json> row = new LinkedHashMap<>();
            row.put("fact", str("operation"));
            row.put("uid", op == null ? Json.JNull.INSTANCE : op.member("uid"));
            row.put("occurrence", str(e.occurrence()));
            attribution(att, row);
            String outcome = att == null ? null : scalar(att.member("outcome_id"));
            String method = att == null ? null : scalar(att.member("method_id"));
            List<Json> cands = att == null || att.member("candidates") == null ? List.of() : att.member("candidates").elements();
            Json stored = e.get("tool_call_uid");
            row.put("stored_tool_call_uid", stored == null ? Json.JNull.INSTANCE : stored);
            row.put("reported_parent", "1".equals(outcome) && "1".equals(method) && !cands.isEmpty() ? cands.get(0) : Json.JNull.INSTANCE);
            row.put("derived_association", "1".equals(outcome) && "2".equals(method) && !cands.isEmpty() ? cands.get(0) : Json.JNull.INSTANCE);
            rows.add(new Json.JObject(row));
        }
        for (Event e : pkg.events) {
            if (e.className == null) {
                continue;
            }
            List<Json> decisions = new ArrayList<>();
            if (e.get("permission_decision") != null) {
                decisions.add(e.get("permission_decision"));
            }
            decisions.addAll(e.elements("permission_decisions"));
            for (Json d : decisions) {
                Map<String, Json> row = new LinkedHashMap<>();
                row.put("fact", str("permission_decision"));
                row.put("decision", d.member("decision") == null ? Json.JNull.INSTANCE : d.member("decision"));
                row.put("reported_decision", d.member("reported_decision") == null ? Json.JNull.INSTANCE : d.member("reported_decision"));
                row.put("occurrence", str(e.occurrence()));
                String scope = scalar(d.member("scope_id"));
                row.put("scope", str(SCOPE.getOrDefault(scope, scope)));
                attribution(d.member("attribution"), row);
                String outcome = scalar(d.member("attribution") == null ? null : d.member("attribution").member("outcome_id"));
                row.put("attached_to_tool_call", "tool_call".equals(e.className) && e.get("tool_call_uid") != null ? e.get("tool_call_uid") : Json.JNull.INSTANCE);
                row.put("remains_at_scope", "1".equals(outcome) ? Json.JNull.INSTANCE : str(SCOPE.getOrDefault(scope, scope)));
                rows.add(new Json.JObject(row));
            }
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("rows", new Json.JArray(rows));
        out.put("rule", str(SchemaCatalog.text(decl, "id")));
        return new Json.JObject(out);
    }

    private static void attribution(Json att, Map<String, Json> row) {
        String outcome = att == null ? null : scalar(att.member("outcome_id"));
        String method = att == null ? null : scalar(att.member("method_id"));
        List<Json> cands = att == null || att.member("candidates") == null ? List.of() : att.member("candidates").elements();
        row.put("outcome", str(outcome == null ? "absent" : OUTCOME.getOrDefault(outcome, outcome)));
        row.put("method", str(method == null ? "absent" : METHOD.getOrDefault(method, method)));
        row.put("candidate_count", num(cands.size()));
        row.put("candidates", new Json.JArray(cands));
        row.put("basis", att == null || att.member("basis") == null ? Json.JNull.INSTANCE : att.member("basis"));
    }

    // ------------------------------------------------------------------------------------------ tool exchanges

    public static Json toolExchanges(OcsfPackage pkg) {
        Json decl = declaration(pkg, SemanticOperation.RESULT_OWNERSHIP);
        Map<String, Map<String, Json>> calls = new LinkedHashMap<>();
        Map<String, List<String>> ownersByContent = new TreeMap<>();
        for (Event e : pkg.events("tool_call")) {
            if (e.activityId == null || e.activityId == 3) {
                continue;
            }
            Json uid = e.get("tool_call_uid");
            String owner = uid == null || uid.isNull() ? "(no owner) " + e.occurrence() : uid.text();
            Map<String, Json> call = calls.computeIfAbsent(owner, k -> {
                Map<String, Json> c = new LinkedHashMap<>();
                c.put("tool_call_uid", str(owner));
                c.put("requests", new Json.JArray(List.of()));
                c.put("results", new Json.JArray(List.of()));
                return c;
            });
            if (e.activityId == 1) {
                Map<String, Json> r = new LinkedHashMap<>();
                r.put("occurrence", str(e.occurrence()));
                r.put("name", e.get("tool_name") == null ? Json.JNull.INSTANCE : e.get("tool_name"));
                r.put("kind", e.get("tool_kind") == null ? Json.JNull.INSTANCE : e.get("tool_kind"));
                r.put("arguments_sha256", str(sha(NeutralJson.write(e.get("arguments") == null ? Json.JNull.INSTANCE : e.get("arguments")))));
                ((Json.JArray) call.get("requests")).elements().add(new Json.JObject(r));
            }
            else if (e.activityId == 2) {
                Map<String, Json> r = new LinkedHashMap<>();
                r.put("occurrence", str(e.occurrence()));
                Json result = e.get("tool_result");
                String contentSha = result == null ? null : sha(NeutralJson.write(result.member("data") == null ? Json.JNull.INSTANCE : result.member("data")));
                r.put("content_sha256", contentSha == null ? str("unavailable at source") : str(contentSha));
                r.put("error_reported", result == null || result.member("error_reported") == null ? str("unavailable at source") : result.member("error_reported"));
                r.put("status", e.get("status") == null ? Json.JNull.INSTANCE : e.get("status"));
                r.put("status_basis", e.get("status_basis") == null ? Json.JNull.INSTANCE : e.get("status_basis"));
                ((Json.JArray) call.get("results")).elements().add(new Json.JObject(r));
                if (contentSha != null) {
                    ownersByContent.computeIfAbsent(contentSha, k -> new ArrayList<>()).add(owner);
                }
            }
        }
        List<Json> identical = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : ownersByContent.entrySet()) {
            if (e.getValue().size() > 1) {
                Map<String, Json> g = new LinkedHashMap<>();
                g.put("content_sha256", str(e.getKey()));
                g.put("owners", new Json.JArray(e.getValue().stream().map(AnalysisViews::str).toList()));
                g.put("kept_separate", new Json.JBool(true));
                identical.add(new Json.JObject(g));
            }
        }
        Map<String, Json> out = new LinkedHashMap<>();
        List<Json> list = new ArrayList<>();
        for (Map<String, Json> c : calls.values()) {
            list.add(new Json.JObject(c));
        }
        out.put("calls", new Json.JArray(list));
        out.put("identical_result_content", new Json.JArray(identical));
        out.put("rule", str(SchemaCatalog.text(decl, "id")));
        return new Json.JObject(out);
    }

    // ------------------------------------------------------------------------------------------ helpers

    static Json occurrence(Event e) {
        Map<String, Json> o = new LinkedHashMap<>();
        o.put("artifact", str(e.artifact()));
        o.put("record_index", num(e.recordIndex()));
        o.put("sequence", num(e.sequence));
        return new Json.JObject(o);
    }

    static String scalar(Json v) {
        return v == null ? null : v.scalarText();
    }

    static Json str(String s) {
        return s == null ? Json.JNull.INSTANCE : new Json.JString(s);
    }

    static Json num(Integer n) {
        return n == null ? Json.JNull.INSTANCE : new Json.JNumber(Integer.toString(n));
    }

    static String sha(String text) {
        return NativeArtifact.sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
