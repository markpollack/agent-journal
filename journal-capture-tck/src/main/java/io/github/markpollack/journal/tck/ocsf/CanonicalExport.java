package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

/** Lossless common-event export. Rows are observations of lifecycle stages, never inferred tool executions. */
public final class CanonicalExport {
    private CanonicalExport() {}

    /**
     * Writes a versioned core-context row followed by canonical event rows, for every available linked core.
     * Original common fields, full arguments/results/content and provenance are retained. Supporting observations
     * occur only nested under their canonical event, never as extra rows. No vendor parser or optional views contract.
     */
    public static void write(OcsfPackage pkg, Writer out) throws IOException {
        for (OcsfPackage core : pkg.all()) { validate(core); }
        for (OcsfPackage core : pkg.all()) {
            line(out, context(core));
            for (Event event : core.canonical()) { line(out, event(core, event)); }
        }
    }

    private static void validate(OcsfPackage core) {
        if (core.refused()) { throw new IllegalArgumentException("refused core " + core.label()); }
        if (core.combination() == OcsfPackage.Combination.UNVALIDATED_EVIDENCE_LOG
                || core.combination() == OcsfPackage.Combination.UNVALIDATED_UNDECLARED
                || core.combination() == OcsfPackage.Combination.CONTRADICTORY) {
            throw new IllegalArgumentException("unvalidated combination " + core.label());
        }
    }

    private static Json.JObject context(OcsfPackage core) {
        Json.JObject context = row("canonical-core", core);
        context.members().put("run", core.declarations == null ? core.manifest.json() : core.declarations.run());
        context.members().put("diagnostics", new Json.JArray(core.diagnostics.stream().map(d -> (Json) new Json.JString(d.toString())).toList()));
        if (core.declarations != null) {
            context.members().put("recording", core.declarations.recording());
            context.members().put("children", new Json.JArray(core.declarations.children()));
            if (core.declarations.assembly() != null) { context.members().put("assembly", core.declarations.assembly()); }
        }
        context.members().put("combination", new Json.JString(core.combination().name()));
        return context;
    }

    private static Json.JObject event(OcsfPackage core, Event event) {
        Json.JObject row = row("canonical-event", core);
        row.members().put("class", event.className == null ? Json.JNull.INSTANCE : new Json.JString(event.className));
        row.members().put("event", PackageReader.selfContained(core, event));
        List<Json> supporting = new ArrayList<>();
        for (Json ref : event.elements("assembly", "observation_refs")) {
            String uid = SchemaCatalog.text(ref, "occurrence_uid");
            List<Event> matches = core.events.stream().filter(e -> uid != null && uid.equals(e.text("assembly", "occurrence_uid"))).toList();
            if (matches.size() != 1) { throw new IllegalArgumentException("unresolved observation reference " + uid); }
            supporting.add(PackageReader.selfContained(core, matches.get(0)));
        }
        row.members().put("source_observations", new Json.JArray(supporting));
        return row;
    }

    private static Json.JObject row(String kind, OcsfPackage core) {
        LinkedHashMap<String, Json> fields = new LinkedHashMap<>();
        fields.put("kind", new Json.JString("agent-journal/" + kind));
        fields.put("version", new Json.JString("0.1"));
        fields.put("core", new Json.JString(core.label()));
        fields.put("run_uid", new Json.JString(core.manifest.runUid()));
        return new Json.JObject(fields);
    }
    private static void line(Writer out, Json row) throws IOException { out.write(NeutralJson.write(row)); out.write('\n'); }
}
