package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Embeds MCP protocol JSON into the generated typed objects under one revision's binding. Total and lossless: a
 * member the definition does not name goes to {@code mcp_additional_members} (or {@code mcp_nonconforming_members}
 * where the definition closes its members), a named member whose value does not fit goes to
 * {@code mcp_nonconforming_members}, and a union value no single alternative matches stays whole in
 * {@code mcp_unresolved}. The protocol's required members are not enforced here.
 */
final class McpEmbedder {

    private static final java.util.Set<String> LEAVES = java.util.Set.of("json", "scalar", "scalar_union");

    private final Jv.Obj objects;
    private final String revision;

    McpEmbedder(Jv.Obj objects, String revision) {
        this.objects = objects;
        this.revision = revision;
    }

    Jv shape(String object) {
        Jv s = objects.path(object, "revisions", revision);
        if (s == null) {
            throw new IllegalStateException("binding object " + object + " has no shape for " + revision);
        }
        return s;
    }

    /** The canonical form of {@code value} under a binding node, or {@code null} when the value does not fit it. */
    Jv embed(Jv node, Jv value) {
        String kind = node.member("kind").text();
        if (LEAVES.contains(kind)) {
            return McpAlternatives.fits(node, value) ? Jv.copy(value) : null;
        }
        return "variant".equals(kind) ? variant(node.member("object").text(), value) : structure(kind, node, value);
    }

    private Jv structure(String kind, Jv node, Jv value) {
        return switch (kind) {
            case "array" -> value instanceof Jv.Arr a ? array(node.member("items"), a) : null;
            case "object", "map" -> value instanceof Jv.Obj o ? container(kind, node.member("object").text(), o) : null;
            default -> throw new IllegalStateException("unknown binding node kind " + kind);
        };
    }

    private Jv container(String kind, String object, Jv.Obj o) {
        return "object".equals(kind) ? object(object, o) : map(object, o);
    }

    private Jv array(Jv items, Jv.Arr a) {
        Jv.Arr out = new Jv.Arr();
        for (Jv el : a.elements()) {
            Jv e = embed(items, el);
            if (e == null) {
                return null;
            }
            out.add(e);
        }
        return out;
    }

    private Jv map(String entryObject, Jv.Obj o) {
        Jv entry = shape(entryObject);
        Jv.Arr out = new Jv.Arr();
        for (Map.Entry<String, Jv> m : o.members().entrySet()) {
            Jv v = embed(entry.member("value"), m.getValue());
            if (v == null) {
                return null;
            }
            out.add(new Jv.Obj().put(entry.member("key_attribute").text(), m.getKey()).put(entry.member("value_attribute").text(), v));
        }
        return out;
    }

    Jv.Obj object(String object, Jv.Obj value) {
        Jv shape = shape(object);
        Jv.Obj members = (Jv.Obj) shape.member("members");
        boolean open = shape.member("additional_allowed") instanceof Jv.Bool b && b.value();
        Jv.Obj out = new Jv.Obj();
        Jv.Obj additional = new Jv.Obj();
        Jv.Obj nonconforming = new Jv.Obj();
        for (Map.Entry<String, Jv> m : value.members().entrySet()) {
            Jv member = members.get(m.getKey());
            Jv e = member == null ? null : embed(member.member("node"), m.getValue());
            if (e != null) {
                out.put(member.member("attribute").text(), e);
            }
            else {
                (member == null && open ? additional : nonconforming).put(m.getKey(), Jv.copy(m.getValue()));
            }
        }
        if (!additional.members().isEmpty()) {
            out.put(McpBindingGenerator.ADDITIONAL, additional);
        }
        if (!nonconforming.members().isEmpty()) {
            out.put(McpBindingGenerator.NONCONFORMING, nonconforming);
        }
        return out;
    }

    private Jv.Obj variant(String object, Jv value) {
        Jv alt = choose(object, value);
        Jv e = alt == null ? null : embed(alt.member("node"), value);
        return e != null ? new Jv.Obj().put(alt.member("attribute").text(), e) : new Jv.Obj().put(McpBindingGenerator.UNRESOLVED, Jv.copy(value));
    }

    /** The one alternative a value matches, or {@code null} (see {@link McpAlternatives}). */
    Jv choose(String variant, Jv value) {
        return new McpAlternatives(this).choose(variant, value);
    }
}
