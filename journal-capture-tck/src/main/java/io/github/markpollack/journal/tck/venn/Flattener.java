package io.github.markpollack.journal.tck.venn;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Turns a JSON record into its leaves. Nulls, empty strings and empty containers are dropped: they carry no value
 * that could be present or missing on the other side. Numbers are normalized so that {@code 1}, {@code 1.0}
 * and {@code 1e0} are the same value.
 */
public final class Flattener {

    private Flattener() {
    }

    public static List<Leaf> flatten(JsonNode record, String kind, String location, boolean keptWhole) {
        List<Leaf> leaves = new ArrayList<>();
        walk(record, "", kind, location, keptWhole, leaves);
        return leaves;
    }

    private static void walk(JsonNode node, String path, String kind, String location, boolean keptWhole,
            List<Leaf> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                walk(field.getValue(), path.isEmpty() ? field.getKey() : path + "." + field.getKey(), kind, location,
                        keptWhole, out);
            }
        }
        else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, path + "[]", kind, location, keptWhole, out);
            }
        }
        else if (node.isTextual()) {
            if (node.asText().isEmpty()) {
                return; // an empty string carries no value that could be present or missing
            }
            out.add(new Leaf(kind, path, node.asText(), location, keptWhole));
        }
        else if (node.isNumber()) {
            out.add(new Leaf(kind, path, normalize(node.decimalValue()), location, keptWhole));
        }
        else if (node.isBoolean()) {
            out.add(new Leaf(kind, path, node.asBoolean(), location, keptWhole));
        }
    }

    static BigDecimal normalize(BigDecimal number) {
        return number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
    }
}
