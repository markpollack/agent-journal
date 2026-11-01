package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Resolves declared common tool-result ownership to the full preserved source record, without native parsing. */
public final class OwnedContents {
    private OwnedContents() { }

    /** Full persisted outputs of this canonical result. Verification against original sources remains a separate operation. */
    public static List<Json> of(OcsfPackage pkg, Event result) {
        if (!isResult(result) || pkg.declarations == null) { return List.of(); }
        Json sources = pkg.declarations.run().path("launch_settings", "acquired_execution", "sources");
        if (sources == null) { return List.of(); }
        List<Json> out = new ArrayList<>();
        for (Json source : sources.elements()) {
            Json binding = source.member("tool_result_binding");
            if (binding == null || !matches(pkg, result, binding)) { continue; }
            String artifact = SchemaCatalog.text(source, "artifact");
            List<Event> records = pkg.canonical().stream().filter(e -> artifact.equals(e.artifact()) && e.wholeRecord()).toList();
            if (records.size() != 1) { throw new IllegalArgumentException("unresolved owned content " + artifact); }
            out.add(new Json.JObject(Map.of("binding", binding, "source", PackageReader.selfContained(pkg, records.get(0)))));
        }
        return out;
    }

    private static boolean isResult(Event event) {
        return "tool_call".equals(event.className) && Integer.valueOf(2).equals(event.activityId);
    }

    private static boolean matches(OcsfPackage pkg, Event result, Json binding) {
        return pkg.manifest.runUid().equals(SchemaCatalog.text(binding, "run_uid"))
                && Json.sameValue(result.get("session_uid"), binding.member("session_uid"))
                && Json.sameValue(result.get("tool_call_uid"), binding.member("tool_call_uid"));
    }
}
