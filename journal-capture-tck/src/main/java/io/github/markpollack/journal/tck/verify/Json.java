package io.github.markpollack.journal.tck.verify;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A neutral JSON value: what the text said, nothing more. Numbers keep both their exact value (decimal arithmetic,
 * never {@code double}) and their source spelling, so {@code 1} and {@code 1.0} are the same value with different
 * representations and {@code 9007199254740993} is exact. Object member order is kept for reporting but is not part
 * of value equality. Nothing here knows Journal's or any vendor's types.
 *
 * <p>This is the only JSON model used on the checking path (see {@link NeutralJson}); production DTOs, capture
 * parsers and SDK message parsing never interpret an expected or a candidate value.
 */
public sealed interface Json permits Json.JObject, Json.JArray, Json.JString, Json.JNumber, Json.JBool, Json.JNull {

    record JObject(Map<String, Json> members) implements Json {
        public JObject {
            members = new LinkedHashMap<>(members);
        }

        public Json get(String key) {
            return members.get(key);
        }
    }

    record JArray(List<Json> elements) implements Json {
        public JArray {
            elements = new ArrayList<>(elements);
        }
    }

    record JString(String value) implements Json {
    }

    /**
     * @param text the number as spelled in the source, for example {@code 1.0} or {@code 2.50}
     * @param value its exact value
     */
    record JNumber(String text, BigDecimal value) implements Json {
        public JNumber(String text) {
            this(text, new BigDecimal(text));
        }

        public boolean sameValue(JNumber other) {
            return value.compareTo(other.value) == 0;
        }
    }

    record JBool(boolean value) implements Json {
    }

    record JNull() implements Json {
        public static final JNull INSTANCE = new JNull();
    }

    /** The member at {@code key} of an object, or {@code null} when this is not an object or has no such member. */
    default Json member(String key) {
        return this instanceof JObject o ? o.get(key) : null;
    }

    /** Follows object members; {@code null} as soon as a step is not an object or lacks the key. */
    default Json path(String... keys) {
        Json current = this;
        for (String key : keys) {
            current = current == null ? null : current.member(key);
        }
        return current;
    }

    /** The string value, or {@code null} when this is not a string. */
    default String text() {
        return this instanceof JString s ? s.value() : null;
    }

    /** A plain-text rendering of a scalar for identities (string, number spelling, boolean); {@code null} otherwise. */
    default String scalarText() {
        if (this instanceof JString s) {
            return s.value();
        }
        if (this instanceof JNumber n) {
            return n.text();
        }
        if (this instanceof JBool b) {
            return Boolean.toString(b.value());
        }
        return null;
    }

    default boolean isNull() {
        return this instanceof JNull;
    }

    default List<Json> elements() {
        return this instanceof JArray a ? a.elements() : List.of();
    }

    /** Whether this value, or any value inside it, is a number. */
    default boolean containsNumber() {
        if (this instanceof JNumber) {
            return true;
        }
        if (this instanceof JObject o) {
            return o.members().values().stream().anyMatch(Json::containsNumber);
        }
        if (this instanceof JArray a) {
            return a.elements().stream().anyMatch(Json::containsNumber);
        }
        return false;
    }

    static String kindOf(Json json) {
        if (json == null) {
            return "missing";
        }
        if (json instanceof JObject) {
            return "object";
        }
        if (json instanceof JArray) {
            return "array";
        }
        if (json instanceof JString) {
            return "string";
        }
        if (json instanceof JNumber) {
            return "number";
        }
        if (json instanceof JBool) {
            return "boolean";
        }
        return "null";
    }

    /**
     * Every difference between an expected value and an actual one, by path. Numbers that are equal in value but
     * spelled differently are a {@link Difference.Kind#REPRESENTATION} difference, reported separately from a
     * changed value; a boolean is never equal to a number; a {@code null} member is not a missing one; an empty
     * array is not an empty object.
     */
    static List<Difference> differences(Json expected, Json actual) {
        List<Difference> out = new ArrayList<>();
        diff(expected, actual, "$", out);
        return out;
    }

    /** Whether the two values are equal in value: no difference other than representation. */
    static boolean sameValue(Json expected, Json actual) {
        return differences(expected, actual).stream().allMatch(d -> d.kind() == Difference.Kind.REPRESENTATION);
    }

    private static void diff(Json expected, Json actual, String path, List<Difference> out) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected == null) {
            out.add(new Difference(path, Difference.Kind.EXTRA, "actual has " + kindOf(actual)));
            return;
        }
        if (actual == null) {
            out.add(new Difference(path, Difference.Kind.MISSING, "expected " + kindOf(expected)));
            return;
        }
        if (!kindOf(expected).equals(kindOf(actual))) {
            if (expected.isNull() || actual.isNull()) {
                out.add(new Difference(path, Difference.Kind.NULL_VS_VALUE,
                        "expected " + kindOf(expected) + ", actual " + kindOf(actual)));
            }
            else {
                out.add(new Difference(path, Difference.Kind.KIND,
                        "expected " + kindOf(expected) + ", actual " + kindOf(actual)));
            }
            return;
        }
        if (expected instanceof JObject eo && actual instanceof JObject ao) {
            for (Map.Entry<String, Json> member : eo.members().entrySet()) {
                diff(member.getValue(), ao.members().get(member.getKey()), path + "." + member.getKey(), out);
            }
            for (String key : ao.members().keySet()) {
                if (!eo.members().containsKey(key)) {
                    out.add(new Difference(path + "." + key, Difference.Kind.EXTRA,
                            "actual has " + kindOf(ao.members().get(key))));
                }
            }
        }
        else if (expected instanceof JArray ea && actual instanceof JArray aa) {
            int common = Math.min(ea.elements().size(), aa.elements().size());
            for (int i = 0; i < common; i++) {
                diff(ea.elements().get(i), aa.elements().get(i), path + "[" + i + "]", out);
            }
            for (int i = common; i < ea.elements().size(); i++) {
                out.add(new Difference(path + "[" + i + "]", Difference.Kind.MISSING, "expected element"));
            }
            for (int i = common; i < aa.elements().size(); i++) {
                out.add(new Difference(path + "[" + i + "]", Difference.Kind.EXTRA, "actual has element"));
            }
        }
        else if (expected instanceof JNumber en && actual instanceof JNumber an) {
            if (!en.sameValue(an)) {
                out.add(new Difference(path, Difference.Kind.VALUE, "number value differs"));
            }
            else if (!en.text().equals(an.text())) {
                out.add(new Difference(path, Difference.Kind.REPRESENTATION,
                        "expected spelling " + en.text() + ", actual " + an.text()));
            }
        }
        else if (expected instanceof JString es && actual instanceof JString as) {
            if (!es.value().equals(as.value())) {
                out.add(new Difference(path, Difference.Kind.VALUE, "string differs"));
            }
        }
        else if (expected instanceof JBool eb && actual instanceof JBool ab) {
            if (eb.value() != ab.value()) {
                out.add(new Difference(path, Difference.Kind.VALUE, "boolean differs"));
            }
        }
        // two nulls: equal
    }
}
