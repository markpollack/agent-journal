package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.ocsf.writer.McpNode.ArrayNode;
import io.github.markpollack.journal.ocsf.writer.McpNode.Ref;
import io.github.markpollack.journal.ocsf.writer.McpNode.Scalar;
import io.github.markpollack.journal.ocsf.writer.McpNode.ScalarUnion;

import java.util.Map;

/**
 * How a reader recognizes one alternative of an MCP union, recorded in the binding: its JSON type, and for an object
 * its constant members (for example {@code type: "text"}, {@code method: "tools/call"}), its required members and the
 * members it names; a nested union is recognized by its own alternatives.
 */
final class McpUnionMatch {

    private McpUnionMatch() {
    }

    static Jv.Obj of(McpSchemaModel model, String rev, Jv schema, McpNode node) {
        if (node instanceof Ref r && "variant".equals(r.kind())) {
            return new Jv.Obj().put("json_type", "object").put("nested_variant", r.object());
        }
        if (node instanceof Ref) {
            return objectMatch(model.dereference(rev, followAliases(model, rev, (Jv.Obj) schema)));
        }
        if (node instanceof ArrayNode) {
            return new Jv.Obj().put("json_type", "array");
        }
        if (node instanceof Scalar sc) {
            return new Jv.Obj().put("json_type", sc.type());
        }
        if (node instanceof ScalarUnion su) {
            Jv.Arr t = new Jv.Arr();
            su.types().forEach(x -> t.add(new Jv.Str(x)));
            return new Jv.Obj().put("json_type", t);
        }
        return new Jv.Obj().put("json_type", "any");
    }

    private static Jv.Obj followAliases(McpSchemaModel model, String rev, Jv.Obj schema) {
        Jv.Obj s = schema;
        while (McpSchemaModel.alias(s)) {
            s = (Jv.Obj) model.defsByRevision.get(rev).get(McpSchemaModel.refName(s.get("$ref").text()));
        }
        return s;
    }

    private static Jv.Obj objectMatch(Jv.Obj s) {
        Jv.Obj consts = new Jv.Obj();
        Jv.Arr named = new Jv.Arr();
        if (s.get("properties") instanceof Jv.Obj props) {
            for (Map.Entry<String, Jv> p : props.members().entrySet()) {
                named.add(new Jv.Str(p.getKey()));
                consts.put(p.getKey(), p.getValue().member("const"));
            }
        }
        Jv.Arr required = new Jv.Arr();
        McpSchemaModel.names(s.get("required")).forEach(r -> required.add(new Jv.Str(r)));
        return new Jv.Obj().put("json_type", "object").put("consts", consts).put("required", required).put("members", named);
    }
}
