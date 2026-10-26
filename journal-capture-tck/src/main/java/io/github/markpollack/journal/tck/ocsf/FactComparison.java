package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * The oracle's value comparison, retaining its exact subject, operands and addresses in the
 * existing Check ledger.
 */
public final class FactComparison {

    private FactComparison() {}

    public static void compare(
            OracleReport report, String property, String owner, Check.Evidence evidence) {
        Check.Category category =
                evidence.meaning().equals("source.whole_record/v1")
                                || evidence.meaning().equals("source.opaque_text/v1")
                        ? Check.Category.PRESERVATION
                        : Check.Category.NORMALIZATION;
        compare(report, property, owner, evidence, category);
    }

    static void compare(
            OracleReport report,
            String property,
            String owner,
            Check.Evidence evidence,
            Check.Category category) {
        boolean equal = Json.sameValue(evidence.expected(), evidence.actual());
        report.add(
                new Check(
                        category,
                        property,
                        owner,
                        equal ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE,
                        equal
                                ? "exact value and type"
                                : evidence.fact()
                                        + " at "
                                        + evidence.assertion()
                                        + ": "
                                        + Json.differences(evidence.expected(), evidence.actual()),
                        1,
                        evidence));
    }

    public static Json value(Object value) {
        if (value == null || value instanceof Json) {
            return (Json) value;
        }
        if (value instanceof Number) {
            return new Json.JNumber(value.toString());
        }
        if (value instanceof Boolean b) {
            return new Json.JBool(b);
        }
        if (value instanceof List<?> list) {
            List<Json> values = new ArrayList<>();
            list.forEach(v -> values.add(value(v)));
            return new Json.JArray(values);
        }
        return new Json.JString(value.toString());
    }

    /** Exact RFC 6901 lookup, including array indexes; a missing member remains Java null. */
    public static Json at(Json root, String pointer) {
        if (pointer.isEmpty()) {
            return root;
        }
        Json current = root;
        for (String part : pointer.substring(1).split("/", -1)) {
            String key = part.replace("~1", "/").replace("~0", "~");
            current = member(current, key);
        }
        return current;
    }

    private static Json member(Json current, String key) {
        if (!(current instanceof Json.JArray array)) {
            return current == null ? null : current.member(key);
        }
        try {
            int index = Integer.parseInt(key);
            return index >= 0 && index < array.elements().size()
                    ? array.elements().get(index)
                    : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }
}
