package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which alternative of an MCP union a value is, by the match the binding records for each alternative: its JSON
 * type, constant members, required members and named members. Also the scalar fit and JSON equality the embedding uses.
 */
final class McpAlternatives {

    private final McpEmbedder embedder;

    McpAlternatives(McpEmbedder embedder) {
        this.embedder = embedder;
    }

    /**
     * The one alternative a value matches by JSON type, constant members and required members; ties keep the
     * alternatives with the most matched constants, then the most required members, then the fewest unnamed members;
     * {@code null} when none or several remain.
     */
    Jv choose(String variant, Jv value) {
        List<Jv> best = new ArrayList<>();
        long[] bestScore = null;
        for (Jv alt : embedder.shape(variant).member("alternatives").elements()) {
            if (!matches(alt.member("match"), value)) {
                continue;
            }
            long[] score = score(alt.member("match"), value);
            int cmp = bestScore == null ? 1 : compare(score, bestScore);
            if (cmp > 0) {
                best.clear();
                bestScore = score;
            }
            if (cmp >= 0) {
                best.add(alt);
            }
        }
        return best.size() == 1 ? best.get(0) : closestFit(best, value);
    }

    /**
     * Among alternatives the top-level match cannot tell apart, the one whose embedding needs no additional,
     * nonconforming or unresolved members while every other does (the titled and untitled enum schemas differ only
     * below the top level); {@code null} when that does not single one out either.
     */
    private Jv closestFit(List<Jv> tied, Jv value) {
        Jv clean = null;
        for (Jv alt : tied) {
            Jv e = embedder.embed(alt.member("node"), value);
            if (e != null && !Jv.write(e).matches("(?s).*\"(mcp_additional_members|mcp_nonconforming_members|mcp_unresolved)\".*")) {
                if (clean != null) {
                    return null;
                }
                clean = alt;
            }
        }
        return clean;
    }

    private static int compare(long[] a, long[] b) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Long.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static long[] score(Jv match, Jv value) {
        Jv consts = match.member("consts");
        Jv required = match.member("required");
        long carried = consts == null ? 0 : ((Jv.Obj) consts).members().keySet().stream().filter(k -> value.member(k) != null).count();
        return new long[] {carried, required == null ? 0 : required.elements().size(), -unnamed(match, value)};
    }

    private static long unnamed(Jv match, Jv value) {
        Jv named = match.member("members");
        if (!(value instanceof Jv.Obj o) || named == null) {
            return 0;
        }
        Set<String> names = new TreeSet<>();
        named.elements().forEach(n -> names.add(n.text()));
        return o.members().keySet().stream().filter(k -> !names.contains(k)).count();
    }

    private boolean matches(Jv match, Jv value) {
        Jv type = match.member("json_type");
        boolean typed = type instanceof Jv.Arr types ? fitsAny(types, value) : fitsType(type.text(), value);
        if (!typed) {
            return false;
        }
        Jv nested = match.member("nested_variant");
        if (nested != null) {
            return embedder.choose(nested.text(), value) != null;
        }
        return constsHold(match, value) && requiredPresent(match.member("required"), value);
    }

    /**
     * Every constant member the value carries equals its constant; a constant member the value omits disqualifies it
     * only when the alternative requires that member (the form-mode elicitation omits its optional mode).
     */
    private static boolean constsHold(Jv match, Jv value) {
        if (!(match.member("consts") instanceof Jv.Obj c)) {
            return true;
        }
        java.util.Set<String> required = new java.util.HashSet<>();
        for (Jv r : match.member("required") == null ? List.<Jv>of() : match.member("required").elements()) {
            required.add(r.text());
        }
        for (Map.Entry<String, Jv> e : c.members().entrySet()) {
            Jv actual = value.member(e.getKey());
            boolean absentAndOptional = actual == null && !required.contains(e.getKey());
            if (!absentAndOptional && (actual == null || !sameJson(actual, e.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean requiredPresent(Jv required, Jv value) {
        for (Jv r : required == null ? List.<Jv>of() : required.elements()) {
            if (value.member(r.text()) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean fitsAny(Jv types, Jv value) {
        for (Jv t : types.elements()) {
            if (fitsScalar(t.text(), value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fitsType(String type, Jv value) {
        return switch (type) {
            case "any" -> true;
            case "object" -> value instanceof Jv.Obj;
            case "array" -> value instanceof Jv.Arr;
            default -> fitsScalar(type, value);
        };
    }

    static boolean fitsScalar(String type, Jv value) {
        return switch (type) {
            case "string" -> value instanceof Jv.Str;
            case "integer" -> value instanceof Jv.Num n && n.integral();
            case "number" -> value instanceof Jv.Num;
            case "boolean" -> value instanceof Jv.Bool;
            case "null" -> value instanceof Jv.Null;
            default -> false;
        };
    }

    /** Whether a json, scalar or scalar-union node admits the value. */
    static boolean fits(Jv node, Jv value) {
        return switch (node.member("kind").text()) {
            case "scalar" -> fitsScalar(node.member("type").text(), value);
            case "scalar_union" -> fitsAny(node.member("types"), value);
            default -> true;
        };
    }

    /** JSON equality: numbers by value, objects by members regardless of order, everything else exactly. */
    static boolean sameJson(Jv a, Jv b) {
        if (a instanceof Jv.Num x && b instanceof Jv.Num y) {
            return x.value().compareTo(y.value()) == 0;
        }
        if (a instanceof Jv.Obj x && b instanceof Jv.Obj y) {
            return sameObject(x, y);
        }
        if (a instanceof Jv.Arr x && b instanceof Jv.Arr y) {
            return sameArray(x, y);
        }
        return Jv.write(a).equals(Jv.write(b));
    }

    private static boolean sameObject(Jv.Obj x, Jv.Obj y) {
        return x.members().keySet().equals(y.members().keySet())
                && x.members().entrySet().stream().allMatch(m -> sameJson(m.getValue(), y.get(m.getKey())));
    }

    private static boolean sameArray(Jv.Arr x, Jv.Arr y) {
        if (x.elements().size() != y.elements().size()) {
            return false;
        }
        for (int i = 0; i < x.elements().size(); i++) {
            if (!sameJson(x.elements().get(i), y.elements().get(i))) {
                return false;
            }
        }
        return true;
    }
}
