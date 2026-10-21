package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares an observed {@link RawInventory} with the accepted {@link SourceSpecification} inventory of the same
 * profile and reports every difference as a finding with its location (artifact, variant, path, record indexes):
 *
 * <ul>
 * <li>{@code NEW_VARIANT}: a record variant the accepted specification does not know (proposal);</li>
 * <li>{@code NEW_PATH}: a path within a known variant the accepted specification does not know (proposal), reported
 * even where a derived schema would be permissive;</li>
 * <li>{@code KIND_CHANGE}: a kind observed at a known path that the accepted specification never observed there,
 * including {@code null} where the field was never null (nullability change) (proposal);</li>
 * <li>{@code NEWLY_ABSENT}: a path the accepted specification observed in every record of the variant is absent in
 * some record now (note: optional now, not inferred as removal);</li>
 * <li>{@code NOT_OBSERVED}: an accepted path or variant not seen in this specimen (note: no removal is inferred);</li>
 * <li>{@code EMPTY_CONTAINER}: an empty object/array where the accepted specification never observed one (note).</li>
 * </ul>
 *
 * The report carries a {@code proposed_specification_inventory} (accepted united with observed) explicitly labelled
 * as a proposal for review; nothing here writes the accepted specification. A specimen whose every variant, path and
 * kind is already accepted has {@code shape_drift: false}; a same-shape change of meaning is not visible here and is
 * the business of the semantic oracle.
 */
public final class InventoryComparison {

    public static final String KIND = "agent-journal/native-inventory-drift";

    private InventoryComparison() {
    }

    public static Json compare(Json spec, Json observed) {
        String profile = RawInventory.text(spec, "profile");
        if (profile == null || !profile.equals(RawInventory.text(observed, "profile"))) {
            throw new IllegalArgumentException("specification profile " + profile + " != inventory profile " + RawInventory.text(observed, "profile"));
        }
        String artifact = RawInventory.text(observed, "artifact");
        Json accepted = spec.path("inventory", "variants");
        Json seen = observed.member("variants");
        List<Json> findings = new ArrayList<>();
        for (Map.Entry<String, Json> v : ((Json.JObject) seen).members().entrySet()) {
            Json acc = accepted.member(v.getKey());
            Json ev = v.getValue().member("evidence");
            if (acc == null) {
                findings.add(finding("NEW_VARIANT", "proposal", artifact, profile, v.getKey(), null, ev,
                        "records of a variant the accepted specification does not know: " + describe(v.getValue().member("discriminators")), null));
                continue;
            }
            int acceptedRecords = SourceSpecification.integer(acc.member("records"));
            int observedRecords = SourceSpecification.integer(v.getValue().member("records"));
            Json accPaths = acc.member("paths");
            for (Map.Entry<String, Json> p : ((Json.JObject) v.getValue().member("paths")).members().entrySet()) {
                Json ap = accPaths.member(p.getKey());
                Json pev = p.getValue().member("evidence");
                if (ap == null) {
                    findings.add(finding("NEW_PATH", "proposal", artifact, profile, v.getKey(), p.getKey(), pev,
                            "path not in the accepted specification; kinds " + keys(p.getValue().member("kinds")), null));
                    continue;
                }
                for (String k : keys(p.getValue().member("kinds"))) {
                    if (ap.path("kinds", k) == null) {
                        findings.add(finding("KIND_CHANGE", "proposal", artifact, profile, v.getKey(), p.getKey(), pev,
                                ("null".equals(k) ? "null observed where the accepted specification never saw null (nullability change)" : "kind " + k + " observed")
                                        + "; accepted kinds " + keys(ap.member("kinds")), keys(ap.member("kinds")).toString()));
                    }
                }
                int accPresent = SourceSpecification.integer(ap.member("present_in_records"));
                int obsPresent = SourceSpecification.integer(p.getValue().member("present_in_records"));
                if (accPresent == acceptedRecords && acceptedRecords > 0 && obsPresent < observedRecords) {
                    findings.add(finding("NEWLY_ABSENT", "note", artifact, profile, v.getKey(), p.getKey(), pev,
                            "present in " + obsPresent + " of " + observedRecords + " record(s); the accepted specification observed it in every record; optional now, not inferred as removed", null));
                }
                if (SourceSpecification.integer(p.getValue().member("empty")) > 0 && SourceSpecification.integer(ap.member("empty")) == 0) {
                    findings.add(finding("EMPTY_CONTAINER", "note", artifact, profile, v.getKey(), p.getKey(), pev,
                            "empty container observed where the accepted specification never observed one", null));
                }
            }
            for (Map.Entry<String, Json> ap : ((Json.JObject) accPaths).members().entrySet()) {
                if (v.getValue().member("paths").member(ap.getKey()) == null) {
                    boolean always = SourceSpecification.integer(ap.getValue().member("present_in_records")) == acceptedRecords && acceptedRecords > 0;
                    findings.add(finding("NOT_OBSERVED", "note", artifact, profile, v.getKey(), ap.getKey(), new Json.JArray(List.of()),
                            "accepted path not observed in this specimen" + (always ? " (it was present in every accepted record)" : "") + "; a missing field is not evidence of removal", null));
                }
            }
        }
        for (String av : ((Json.JObject) accepted).members().keySet()) {
            if (seen.member(av) == null) {
                findings.add(finding("NOT_OBSERVED", "note", artifact, profile, av, null, new Json.JArray(List.of()),
                        "accepted variant not observed in this specimen; absence of a variant is not evidence of removal", null));
            }
        }
        int proposals = 0;
        int notes = 0;
        for (Json f : findings) {
            if ("proposal".equals(RawInventory.text(f, "severity"))) {
                proposals++;
            }
            else {
                notes++;
            }
        }
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("kind", RawInventory.str(KIND));
        out.put("profile", RawInventory.str(profile));
        out.put("artifact", RawInventory.str(artifact));
        out.put("artifact_sha256", observed.member("sha256"));
        out.put("specification_version", spec.member("specification_version") == null ? Json.JNull.INSTANCE : spec.member("specification_version"));
        out.put("shape_drift", new Json.JBool(proposals > 0));
        out.put("proposals", RawInventory.num(proposals));
        out.put("notes", RawInventory.num(notes));
        out.put("findings", new Json.JArray(findings));
        out.put("meaning", RawInventory.str("proposal: the accepted specification would have to change to admit this specimen (review required; never applied automatically); "
                + "note: information that does not change the accepted shape. A change of meaning under an unchanged shape is not detectable here: the semantic oracle judges values"));
        Map<String, Json> proposal = new LinkedHashMap<>();
        proposal.put("status", RawInventory.str("PROPOSAL: the accepted inventory united with this specimen; not accepted until reviewed and committed"));
        proposal.put("inventory", SourceSpecification.merge(List.of(acceptedAsInventory(spec), observed)));
        out.put("proposed_specification_inventory", new Json.JObject(proposal));
        return new Json.JObject(out);
    }

    /** The accepted inventory re-expressed as a single-artifact inventory document, so it can be merged. */
    private static Json acceptedAsInventory(Json spec) {
        Map<String, Json> doc = new LinkedHashMap<>();
        doc.put("artifact", RawInventory.str("accepted:" + RawInventory.text(spec, "specification_version")));
        doc.put("sha256", Json.JNull.INSTANCE);
        doc.put("bytes", RawInventory.num(0));
        doc.put("records", RawInventory.num(0));
        doc.put("data_keyed", spec.path("inventory", "data_keyed"));
        doc.put("map_keyed", spec.path("inventory", "map_keyed"));
        Map<String, Json> vs = new LinkedHashMap<>();
        for (Map.Entry<String, Json> v : ((Json.JObject) spec.path("inventory", "variants")).members().entrySet()) {
            Map<String, Json> vd = new LinkedHashMap<>(((Json.JObject) v.getValue()).members());
            vd.put("evidence", new Json.JArray(List.of()));
            Map<String, Json> ps = new LinkedHashMap<>();
            for (Map.Entry<String, Json> p : ((Json.JObject) v.getValue().member("paths")).members().entrySet()) {
                Map<String, Json> pd = new LinkedHashMap<>(((Json.JObject) p.getValue()).members());
                pd.put("evidence", new Json.JArray(List.of()));
                ps.put(p.getKey(), new Json.JObject(pd));
            }
            vd.put("paths", new Json.JObject(ps));
            vs.put(v.getKey(), new Json.JObject(vd));
        }
        doc.put("variants", new Json.JObject(vs));
        return new Json.JObject(doc);
    }

    static Json finding(String kind, String severity, String artifact, String profile, String variant, String path, Json records, String detail, String accepted) {
        Map<String, Json> f = new LinkedHashMap<>();
        f.put("finding", RawInventory.str(kind));
        f.put("severity", RawInventory.str(severity));
        f.put("artifact", RawInventory.str(artifact));
        f.put("profile", RawInventory.str(profile));
        f.put("variant", RawInventory.str(variant));
        f.put("path", path == null ? Json.JNull.INSTANCE : RawInventory.str(path));
        f.put("records", records);
        f.put("detail", RawInventory.str(detail));
        if (accepted != null) {
            f.put("accepted", RawInventory.str(accepted));
        }
        return new Json.JObject(f);
    }

    static List<String> keys(Json obj) {
        return obj instanceof Json.JObject o ? new ArrayList<>(new TreeSet<>(o.members().keySet())) : List.of();
    }

    private static String describe(Json discriminators) {
        if (!(discriminators instanceof Json.JObject o)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        o.members().forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ", ").append(k).append("=").append(v.isNull() ? "null" : v.scalarText()));
        return sb.toString();
    }
}
