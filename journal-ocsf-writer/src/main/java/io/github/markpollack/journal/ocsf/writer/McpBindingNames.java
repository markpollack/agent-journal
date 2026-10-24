package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.ocsf.writer.McpNode.ArrayNode;
import io.github.markpollack.journal.ocsf.writer.McpNode.Ref;
import io.github.markpollack.journal.ocsf.writer.McpNode.Scalar;
import io.github.markpollack.journal.ocsf.writer.McpNode.ScalarUnion;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Names of the generated MCP objects and attributes. An object-typed member is held in an attribute named after the
 * type it holds ({@code _list} for an array or a map); a scalar or free-JSON member in {@code mcp_<snake(member)>},
 * suffixed with its signature when that member name has more than one signature anywhere in the pinned revisions. An
 * OCSF dictionary attribute has one type, so one name never carries two signatures.
 */
final class McpBindingNames {

    static final String PREFIX = "mcp_";

    /** attribute name to its OCSF signature, filled as names are handed out */
    final Map<String, String> signatures = new TreeMap<>();
    private final Map<String, Set<String>> memberSignatures = new TreeMap<>();

    McpBindingNames(McpSchemaModel model) {
        for (McpSchemaModel.Generated g : model.objects.values()) {
            for (McpSchemaModel.Shape s : g.revisions.values()) {
                for (McpSchemaModel.Member m : s.members.values()) {
                    memberSignatures.computeIfAbsent(base(m.name(), m.node()), k -> new TreeSet<>()).add(m.node().signature());
                }
            }
        }
    }

    private static String base(String member, McpNode n) {
        McpNode inner = n instanceof ArrayNode a ? a.items() : n;
        if (inner instanceof Ref r) {
            return r.object() + (n instanceof ArrayNode || r.isMap() ? "_list" : "");
        }
        return PREFIX + snake(member);
    }

    /** The attribute a member is held in. */
    String member(String member, McpNode n) {
        String base = base(member, n);
        String sig = n.signature();
        return claim(memberSignatures.getOrDefault(base, Set.of(sig)).size() > 1 ? base + "_" + suffix(sig) : base, sig);
    }

    /** The attribute a union alternative, a map value or a protocol-object slot is held in: named after the type it holds. */
    String value(McpNode n) {
        return claim(valueName(n), n.signature());
    }

    private static String valueName(McpNode n) {
        if (n instanceof Ref r) {
            return r.isMap() ? r.object() + "_list" : r.object();
        }
        if (n instanceof ArrayNode a) {
            return a.items() instanceof Ref r ? r.object() + "_list" : PREFIX + suffix(a.items().signature()) + "_values";
        }
        if (n instanceof Scalar s) {
            return PREFIX + s.type() + "_value";
        }
        return PREFIX + (n instanceof ScalarUnion ? "scalar_value" : "json_value");
    }

    /** Fixed attributes the generator adds to every object of a kind. */
    void claimFixed(String name, String signature) {
        claim(name, signature);
    }

    private String claim(String name, String sig) {
        String had = signatures.putIfAbsent(name, sig);
        if (had != null && !had.equals(sig)) {
            throw new IllegalStateException("attribute " + name + " has two signatures: " + had + " and " + sig);
        }
        return name;
    }

    private static String suffix(String signature) {
        String s = signature.endsWith("[]") ? signature.substring(0, signature.length() - 2) + "_list" : signature;
        return s.replace("_t_list", "_list").replaceAll("_t$", "").replace(PREFIX, "");
    }

    /** {@code Def.member[]|0{}} to {@code mcp_def__member__item__alt0__value}. */
    static String objectName(String path) {
        String s = path.replace("[]", "\u0001item").replace("{}", "\u0001value").replace("|", "\u0001alt").replace(".", "\u0001");
        StringBuilder out = new StringBuilder(PREFIX);
        String[] parts = s.split("\u0001");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append("__");
            }
            out.append(snake(parts[i]));
        }
        return out.toString().replaceAll("_+$", "");
    }

    /** camelCase, PascalCase and acronyms to snake_case; leading underscores and non-alphanumerics dropped. */
    static String snake(String name) {
        String s = name.replaceAll("[^A-Za-z0-9]", "_");
        s = s.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        return s.toLowerCase(Locale.ROOT).replaceAll("_+", "_").replaceAll("^_|_$", "");
    }

    static String caption(String name) {
        StringBuilder out = new StringBuilder();
        for (String w : name.split("_+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(w.equals("mcp") ? "MCP" : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return out.toString();
    }
}
