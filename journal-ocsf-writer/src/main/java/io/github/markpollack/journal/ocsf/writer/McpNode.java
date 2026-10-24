package io.github.markpollack.journal.ocsf.writer;

import java.util.List;

/**
 * A node of the MCP binding: how one location of the pinned JSON Schema is represented in the generated typed objects.
 * Free JSON and scalar unions are {@code json_t}; scalars are OCSF scalars; objects, unions and typed maps are generated
 * objects ({@link Ref}); arrays hold one of those.
 */
sealed interface McpNode permits McpNode.JsonNode, McpNode.Scalar, McpNode.ScalarUnion, McpNode.Ref, McpNode.ArrayNode {

    Jv.Obj toJv();

    /** The OCSF signature of a member held in this node: its type or object name, {@code []} for an array. */
    String signature();

    record JsonNode() implements McpNode {
        @Override
        public Jv.Obj toJv() {
            return new Jv.Obj().put("kind", "json");
        }

        @Override
        public String signature() {
            return "json_t";
        }
    }

    record Scalar(String type, Jv constValue, Jv enumValues, String format) implements McpNode {
        @Override
        public Jv.Obj toJv() {
            Jv.Obj o = new Jv.Obj().put("kind", "scalar").put("type", type).put("const", constValue).put("enum", enumValues);
            return format == null ? o : o.put("format", format);
        }

        @Override
        public String signature() {
            return switch (type) {
                case "string" -> "string_t";
                case "integer" -> "long_t";
                case "number" -> "float_t";
                case "boolean" -> "boolean_t";
                default -> throw new IllegalArgumentException(type);
            };
        }
    }

    record ScalarUnion(List<String> types) implements McpNode {
        @Override
        public Jv.Obj toJv() {
            Jv.Arr t = new Jv.Arr();
            types.forEach(x -> t.add(new Jv.Str(x)));
            return new Jv.Obj().put("kind", "scalar_union").put("types", t);
        }

        @Override
        public String signature() {
            return "json_t";
        }
    }

    /** A generated object: {@code kind} object, variant or map (whose object is the entry object). */
    record Ref(String kind, String object) implements McpNode {
        @Override
        public Jv.Obj toJv() {
            return new Jv.Obj().put("kind", kind).put("object", object);
        }

        @Override
        public String signature() {
            return isMap() ? object + "[]" : object;
        }

        boolean isMap() {
            return "map".equals(kind);
        }
    }

    record ArrayNode(McpNode items) implements McpNode {
        @Override
        public Jv.Obj toJv() {
            return new Jv.Obj().put("kind", "array").put("items", items.toJv());
        }

        @Override
        public String signature() {
            return items.signature() + "[]";
        }
    }

    static boolean primitive(String type) {
        return "string".equals(type) || "integer".equals(type) || "number".equals(type) || "boolean".equals(type);
    }
}
