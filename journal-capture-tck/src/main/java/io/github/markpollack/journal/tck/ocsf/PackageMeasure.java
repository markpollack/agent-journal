package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Byte measurements of one core, each component separately: native sources (from the inventory), events, the
 * normalized part of events (residual data removed), the OCSF envelope part, the manifest and inside it the
 * definition closure, extension source, mapping contracts, semantic contract and shared facts. Nothing is removed to
 * hit a target; this is the evidence a compaction decision would need.
 */
public final class PackageMeasure {

    private static final Set<String> RESIDUAL = Set.of("data", "raw_data", "raw_bytes_b64", "structured_result");
    private static final List<String> ENVELOPE = List.of("class_uid", "class_name", "category_uid", "category_name", "activity_id",
            "activity_name", "type_uid", "type_name", "severity_id", "severity", "time", "time_dt", "metadata", "journal", "source_ref");

    private PackageMeasure() {
    }

    public static Json measure(OcsfPackage pkg) {
        Map<String, Json> out = new LinkedHashMap<>();
        out.put("run_uid", str(pkg.manifest.runUid()));
        out.put("label", str(pkg.label()));
        Map<String, Json> natives = new LinkedHashMap<>();
        int nativeTotal = 0;
        for (Json s : pkg.manifest.sources()) {
            Map<String, Json> n = new LinkedHashMap<>();
            n.put("bytes", s.member("bytes"));
            n.put("records", s.member("records"));
            natives.put(SchemaCatalog.text(s, "artifact"), new Json.JObject(n));
            nativeTotal += s.member("bytes") instanceof Json.JNumber b ? b.value().intValue() : 0;
        }
        out.put("native_sources", new Json.JObject(natives));
        out.put("native_total_bytes", num(nativeTotal));
        int eventsBytes = size(pkg.dir.resolve("events.jsonl"));
        int normalized = 0;
        int envelope = 0;
        Map<String, Integer> byClass = new TreeMap<>();
        for (Event e : pkg.events) {
            normalized += NeutralJson.write(strip(e.json)).getBytes(StandardCharsets.UTF_8).length + 1;
            Map<String, Json> env = new LinkedHashMap<>();
            for (String k : ENVELOPE) {
                if (e.json.member(k) != null) {
                    env.put(k, e.json.member(k));
                }
            }
            envelope += NeutralJson.write(new Json.JObject(env)).getBytes(StandardCharsets.UTF_8).length + 1;
            byClass.merge(String.valueOf(e.className), 1, Integer::sum);
        }
        out.put("events_jsonl_bytes", num(eventsBytes));
        out.put("event_count", num(pkg.events.size()));
        Map<String, Json> bc = new LinkedHashMap<>();
        byClass.forEach((k, v) -> bc.put(k, num(v)));
        out.put("events_by_class", new Json.JObject(bc));
        out.put("normalized_only_bytes", num(normalized));
        out.put("residual_bytes", num(eventsBytes - normalized));
        out.put("envelope_only_bytes", num(envelope));
        out.put("run_json_bytes", num(size(pkg.dir.resolve("run.json"))));
        Json m = pkg.manifest.json();
        out.put("definitions_closure_bytes", num(bytes(m.path("definitions", "closure"))));
        out.put("definitions_full_reached_closure_bytes_not_embedded", m.path("definitions", "full_reached_closure_bytes_not_embedded") == null
                ? Json.JNull.INSTANCE : m.path("definitions", "full_reached_closure_bytes_not_embedded"));
        out.put("extension_source_bytes", num(bytes(m.path("definitions", "extension_source"))));
        out.put("mapping_contracts_bytes", num(bytes(m.path("shared", "contracts"))));
        out.put("semantic_contract_bytes", num(bytes(m.member("semantic_contract"))));
        out.put("shared_facts_bytes", num(bytes(m.member("shared")) - bytes(m.path("shared", "contracts"))));
        out.put("children", num(pkg.children.size()));
        return new Json.JObject(out);
    }

    private static Json strip(Json v) {
        if (v instanceof Json.JObject o) {
            Map<String, Json> out = new LinkedHashMap<>();
            for (Map.Entry<String, Json> e : o.members().entrySet()) {
                if (!RESIDUAL.contains(e.getKey())) {
                    out.put(e.getKey(), strip(e.getValue()));
                }
            }
            return new Json.JObject(out);
        }
        if (v instanceof Json.JArray a) {
            return new Json.JArray(a.elements().stream().map(PackageMeasure::strip).toList());
        }
        return v;
    }

    private static int bytes(Json v) {
        return v == null ? 0 : NeutralJson.write(v).getBytes(StandardCharsets.UTF_8).length;
    }

    private static int size(java.nio.file.Path p) {
        try {
            return (int) Files.size(p);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Json str(String s) {
        return s == null ? Json.JNull.INSTANCE : new Json.JString(s);
    }

    private static Json num(int n) {
        return new Json.JNumber(Integer.toString(n));
    }
}
