package io.github.markpollack.journal.ocsf.writer;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Merges the parts of an MCP {@code allOf} (Result with Task, Error with a refined code) into one object schema. */
final class McpAllOf {

    private final Jv.Obj defs;

    McpAllOf(Jv.Obj defs) {
        this.defs = defs;
    }

    /** The members of an {@code allOf}, later members refining earlier ones (the Error refinements of the pinned revisions). */
    Jv.Obj merged(Jv.Obj s) {
        Jv.Obj props = new Jv.Obj();
        Set<String> req = new LinkedHashSet<>();
        boolean closed = false;
        String description = s.get("description") == null ? null : s.get("description").text();
        for (Jv part : s.get("allOf").elements()) {
            Jv.Obj p = dereference((Jv.Obj) part);
            mergeProperties(props, p.get("properties"));
            req.addAll(McpSchemaModel.names(p.get("required")));
            closed |= p.get("additionalProperties") instanceof Jv.Bool b && !b.value();
            if (description == null && p.get("description") != null) {
                description = p.get("description").text();
            }
        }
        Jv.Arr required = new Jv.Arr();
        req.forEach(r -> required.add(new Jv.Str(r)));
        Jv.Obj out = new Jv.Obj().put("type", "object").put("properties", props).put("required", required).put("description", description);
        return closed ? out.put("additionalProperties", new Jv.Bool(false)) : out;
    }

    /** A schema with its references followed and its own allOf merged. */
    Jv.Obj dereference(Jv.Obj schema) {
        Jv.Obj p = schema;
        while (p.get("$ref") != null) {
            p = (Jv.Obj) defs.get(McpSchemaModel.refName(p.get("$ref").text()));
        }
        return p.get("allOf") != null ? merged(p) : p;
    }

    private static void mergeProperties(Jv.Obj into, Jv props) {
        if (props instanceof Jv.Obj pp) {
            for (Map.Entry<String, Jv> m : pp.members().entrySet()) {
                if (into.get(m.getKey()) == null || specific(m.getValue())) {
                    into.put(m.getKey(), m.getValue());
                }
            }
        }
    }

    /** Whether a member schema says more than a description (so a later allOf part refines an earlier one). */
    private static boolean specific(Jv schema) {
        return schema instanceof Jv.Obj o && o.members().keySet().stream().anyMatch(k -> !k.equals("description"));
    }

}
