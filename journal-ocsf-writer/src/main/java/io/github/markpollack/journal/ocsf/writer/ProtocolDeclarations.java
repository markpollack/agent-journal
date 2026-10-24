package io.github.markpollack.journal.ocsf.writer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The {@code protocol_binding} declarations of one event file: before an event that carries MCP protocol objects, the
 * pinned official JSON Schema definitions and the binding shapes a reader needs to extract and validate them offline
 * (the reference closure of the definitions the event names), once per file and revision, amended by a later
 * declaration when an event needs more.
 */
final class ProtocolDeclarations {

    /** Where a declaration goes: the file's own append-only declaration writer. */
    interface Sink {
        void declare(String kind, String uid, Jv.Obj payload);
    }

    private final Map<String, McpBinding.Closure> declared = new LinkedHashMap<>();
    private final Map<String, Integer> count = new LinkedHashMap<>();

    void declareFor(Jv event, Sink sink) {
        for (Map.Entry<String, Set<String>> used : McpBinding.definitionsUsed(event).entrySet()) {
            declare(used.getKey(), used.getValue(), sink);
        }
    }

    private void declare(String revision, Set<String> used, Sink sink) {
        McpBinding binding = McpBinding.bundled();
        McpBinding.Closure need = binding.closure(revision, used);
        McpBinding.Closure have = declared.getOrDefault(revision, new McpBinding.Closure(Set.of(), Set.of()));
        Set<String> defs = minus(need.definitions(), have.definitions());
        Set<String> objs = minus(need.objects(), have.objects());
        if (defs.isEmpty() && objs.isEmpty()) {
            return;
        }
        int n = count.merge(revision, 1, Integer::sum);
        String base = "protocol_binding:mcp/" + revision;
        sink.declare("protocol_binding", n == 1 ? base : base + "#" + n, payload(binding, revision, n, defs, objs));
        declared.put(revision, new McpBinding.Closure(union(need.definitions(), have.definitions()), union(need.objects(), have.objects())));
    }

    private static Jv.Obj payload(McpBinding binding, String revision, int n, Set<String> defs, Set<String> objs) {
        Jv official = binding.officialSchema(revision);
        Jv.Obj schemaDefs = new Jv.Obj();
        for (String d : defs) {
            schemaDefs.put(d, official.path("$defs", d));
        }
        Jv pins = binding.document().member("pins");
        Jv.Obj p = new Jv.Obj().put("protocol", "mcp").put("revision", revision).put("amends", n == 1 ? null : "protocol_binding:mcp/" + revision)
                .put("pins", binding.pin(revision))
                .put("upstream", new Jv.Obj().put("repository", pins.member("repository")).put("commit", pins.member("commit")).put("licence", pins.member("licence")))
                .put("dialect", official.member("$schema")).put("schema_definitions", schemaDefs)
                .put("binding", binding.bindingSubset(revision, new McpBinding.Closure(defs, objs)));
        return n == 1 ? p.put("binding_version", binding.document().member("version")).put("conventions", binding.document().member("conventions")) : p;
    }

    private static Set<String> minus(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.removeAll(b);
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
