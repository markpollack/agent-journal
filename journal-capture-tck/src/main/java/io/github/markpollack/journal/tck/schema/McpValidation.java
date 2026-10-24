package io.github.markpollack.journal.tck.schema;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Kept outside the oracle core package: it binds JSON to Jackson's tree for the validator, which the oracle core never
 * does; it judges conformance of what a package carries and computes no expected value.
 *
 * <p>Validates an extracted MCP protocol object against the pinned official definition an event file declared (draft
 * 2020-12, networknt), with only the required members a partial projection declared missing relaxed; and JSON
 * equality that ignores member order and number spelling.
 */
public final class McpValidation {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private McpValidation() {
    }

    /**
     * Validation messages of {@code value} against the pinned definition: empty when it conforms. {@code relaxed} names
     * top-level required members a partial projection declared missing; they are removed from the definition's required
     * list for this check and nothing else is relaxed.
     */
    public static List<String> validate(String dialect, Map<String, Json> schemaDefinitions, String definition, Json value, Set<String> relaxed) {
        Map<String, Json> defs = new LinkedHashMap<>(schemaDefinitions);
        Json def = defs.get(definition);
        if (def == null) {
            return List.of("the declared closure has no official definition " + definition);
        }
        if (!relaxed.isEmpty()) {
            defs.put(definition, relax(def, relaxed));
        }
        Map<String, Json> root = new LinkedHashMap<>();
        root.put("$schema", new Json.JString(dialect));
        root.put("$defs", new Json.JObject(defs));
        root.put("$ref", new Json.JString("#/$defs/" + definition));
        try {
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(MAPPER.readTree(NeutralJson.write(new Json.JObject(root))));
            JsonNode node = MAPPER.readTree(NeutralJson.write(value));
            List<String> out = new ArrayList<>();
            for (ValidationMessage m : schema.validate(node)) {
                out.add(m.getMessage());
            }
            return out;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Json relax(Json def, Set<String> relaxed) {
        Json.JObject d = (Json.JObject) NeutralJson.parse(NeutralJson.write(def));
        if (d.get("required") instanceof Json.JArray req) {
            List<Json> kept = new ArrayList<>();
            for (Json r : req.elements()) {
                if (!relaxed.contains(r.text())) {
                    kept.add(r);
                }
            }
            Map<String, Json> m = new LinkedHashMap<>(d.members());
            m.put("required", new Json.JArray(kept));
            return new Json.JObject(m);
        }
        return d;
    }

    /** JSON equality ignoring member order: numbers by value, strings and literals exactly. */
    public static boolean sameJson(Json a, Json b) {
        if (a instanceof Json.JNumber x && b instanceof Json.JNumber y) {
            return x.value().compareTo(y.value()) == 0;
        }
        if (a instanceof Json.JObject x && b instanceof Json.JObject y) {
            return x.members().keySet().equals(y.members().keySet()) && x.members().entrySet().stream().allMatch(m -> sameJson(m.getValue(), y.get(m.getKey())));
        }
        if (a instanceof Json.JArray x && b instanceof Json.JArray y) {
            return sameArray(x, y);
        }
        return a != null && b != null && NeutralJson.write(a).equals(NeutralJson.write(b));
    }

    private static boolean sameArray(Json.JArray x, Json.JArray y) {
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
