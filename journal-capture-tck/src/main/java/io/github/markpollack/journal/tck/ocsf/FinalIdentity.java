package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Native-backed final observation identity. */
final class FinalIdentity {
    private FinalIdentity() {}

    private static final Map<String, List<String>> MEMBERS =
            Map.ofEntries(
                    Map.entry(
                            "execution_metrics",
                            List.of(
                                    "metric_name",
                                    "metric_unit",
                                    "scope_id",
                                    "scope",
                                    "model_ref/name")),
                    Map.entry(
                            "usage_observations",
                            List.of("scope_id", "scope", "aggregation_id", "model_ref/name")),
                    Map.entry(
                            "cost_observations",
                            List.of(
                                    "quantity_kind_id",
                                    "quantity_unit",
                                    "currency",
                                    "scope_id",
                                    "scope",
                                    "aggregation_id",
                                    "model_ref/name")),
                    Map.entry("content_blocks", List.of("block_index")),
                    Map.entry("components", List.of("name")),
                    Map.entry("tool_definitions", List.of("name")),
                    Map.entry("cache_write_tiers", List.of("tier")),
                    Map.entry(
                            "protocol_objects", List.of("protocol_definition", "binding_revision")),
                    Map.entry("errors", List.of("error_layer_id")));

    static String key(Json event, String path) {
        StringBuilder key = new StringBuilder();
        StringBuilder pointer = new StringBuilder();
        Json parent = event;
        for (String part : path.substring(1).split("/")) {
            pointer.append('/').append(part);
            Json value = FactComparison.at(event, pointer.toString());
            if (parent instanceof Json.JArray && value instanceof Json.JObject) {
                String collection = key.substring(key.lastIndexOf("/") + 1);
                List<String> members = MEMBERS.getOrDefault(collection, List.of());
                if (!members.isEmpty()) {
                    key.append("/{");
                    for (String member : members) {
                        key.append(member)
                                .append('=')
                                .append(FactComparison.at(value, "/" + member))
                                .append(';');
                    }
                    key.append('}');
                } else {
                    key.append('/').append(part);
                }
            } else {
                key.append('/').append(part);
            }
            parent = value;
        }
        return key.toString();
    }
}
