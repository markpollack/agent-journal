package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small builders for native readers' attributed facts. No producer code or candidate values enter
 * these builders.
 */
final class SourceFacts {

    private SourceFacts() {}

    static Map<String, Json> fields(Object... pairs) {
        Map<String, Json> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], FactComparison.value(pairs[i + 1]));
        }
        return out;
    }

    record Usage(
            Json data,
            ExpectedFacts.Tranches tranches,
            int scope,
            int status,
            int aggregation,
            String model,
            String provider) {}

    record Quantity(
            Json amount,
            int kind,
            String unit,
            String currency,
            int scope,
            String scopeName,
            int aggregation,
            String model) {}

    record Target(ExpectedFacts f, int record, String cls, int activity) {
        void event(
                String support,
                String meaning,
                Map<String, Json> identity,
                Map<String, Json> fields) {
            Map<String, Json> values = new LinkedHashMap<>(fields);
            values.putAll(identity);
            values.put("/activity_id", FactComparison.value(activity));
            values.put("/source_ref/artifact", FactComparison.value(f.artifact));
            values.put("/source_ref/record_index", FactComparison.value(record));
            f.facts.add(
                    new ExpectedFacts.Fact(
                            record,
                            cls,
                            activity,
                            identity,
                            null,
                            Map.of(),
                            values,
                            List.of(support),
                            meaning));
            f.normalized.add(record);
        }

        void observation(
                String collection,
                String support,
                String meaning,
                Map<String, Json> identity,
                Map<String, Json> values) {
            Map<String, Json> fields = new LinkedHashMap<>(values);
            fields.putAll(identity);
            if (!"/tool_result/errors".equals(collection)) {
                fields.put("/source_ref/artifact", FactComparison.value(f.artifact));
                fields.put("/source_ref/record_index", FactComparison.value(record));
            }
            f.facts.add(
                    new ExpectedFacts.Fact(
                            record,
                            cls,
                            activity,
                            Map.of(),
                            collection,
                            identity,
                            fields,
                            List.of(support),
                            meaning));
        }

        void usage(String support, String meaning, Usage usage) {
            Map<String, Json> identity =
                    fields(
                            "/scope_id",
                            usage.scope(),
                            "/model_ref/name",
                            usage.model(),
                            "/aggregation_id",
                            usage.aggregation());
            Map<String, Json> fields =
                    fields(
                            "/usage_status_id",
                            usage.status(),
                            "/measurement_status_id",
                            null,
                            "/model_ref/provider_kind",
                            usage.provider(),
                            "/reasoning_relation_id",
                            usage.tranches().reasoningRelationId(),
                            "/data",
                            usage.data());
            for (String component : AnalysisViews.COMPONENTS) {
                fields.put("/" + component, usage.tranches().values().get(component));
            }
            // Components are keyed by their canonical name, not by order in either file.
            observation("/usage_observations", support, meaning, identity, fields);
            List<String> names = new ArrayList<>(usage.tranches().bases().keySet());
            for (String name : names) {
                Map<String, Json> component =
                        fields(
                                "/name",
                                name,
                                "/component_basis_id",
                                usage.tranches().bases().get(name),
                                "/measurement_status_id",
                                usage.tranches().statuses().get(name),
                                "/conditional_value",
                                usage.tranches().conditionals().get(name),
                                "/native_pointers",
                                usage.tranches().pointers().get(name));
                // Nested collection selection is represented separately by the same native
                // observation identity.
                f.facts.add(
                        new ExpectedFacts.Fact(
                                record,
                                cls,
                                activity,
                                Map.of(),
                                "/usage_observations/*/components",
                                merge(identity, fields("/name", name)),
                                component,
                                List.of(support),
                                meaning));
            }
        }

        void quantity(String support, String meaning, Quantity quantity) {
            if (!(quantity.amount() instanceof Json.JNumber)) {
                return;
            }
            Map<String, Json> values =
                    fields(
                            "/total_cost",
                            quantity.amount(),
                            "/quantity_unit",
                            quantity.unit(),
                            "/currency",
                            quantity.currency(),
                            "/scope",
                            quantity.scopeName());
            if (quantity.kind() == 99) {
                values.put("/quantity_kind", FactComparison.value("Other"));
            }
            String key =
                    support.substring(support.lastIndexOf('/') + 1)
                            .replace("~1", "/")
                            .replace("~0", "~");
            if (meaning.startsWith("copilot.")) {
                key =
                        support.startsWith("/attributes/")
                                ? key
                                : support.startsWith("/data/usage/")
                                        ? support.substring(1).replace('/', '.')
                                        : support.startsWith("/usage/")
                                                ? "result." + support.substring(1).replace('/', '.')
                                                : "accountingSnapshot." + key;
            }
            if ("copilot.model_request_cost/v1".equals(meaning)) {
                key = "requests.cost";
            }
            values.put("/data", new Json.JObject(Map.of(key, quantity.amount())));
            observation(
                    "/cost_observations",
                    support,
                    meaning,
                    fields(
                            "/quantity_kind_id",
                            quantity.kind(),
                            "/scope_id",
                            quantity.scope(),
                            "/model_ref/name",
                            quantity.model(),
                            "/aggregation_id",
                            quantity.aggregation()),
                    values);
        }
    }

    static Map<String, Json> merge(Map<String, Json> first, Map<String, Json> second) {
        Map<String, Json> out = new LinkedHashMap<>(first);
        out.putAll(second);
        return out;
    }

    static Map<String, Json> textBlock(Json text, String type) {
        return fields(
                "/content_blocks/0/block_index",
                0,
                "/content_blocks/0/block_type",
                type,
                "/content_blocks/0/block_kind_id",
                1,
                "/content_blocks/0/text",
                text,
                "/content_blocks/0/data",
                text);
    }

    static void time(ExpectedFacts f, int record, Json timestamp) {
        if (timestamp instanceof Json.JNumber n) {
            f.nativeTime.put(record, Instant.ofEpochMilli(n.value().longValueExact()).toString());
        } else if (timestamp instanceof Json.JString s) {
            f.nativeTime.put(record, s.value());
        }
    }

    record TrancheNames(
            String input,
            String cached,
            String writes,
            String output,
            String reasoning,
            boolean inclusiveInput) {}

    static ExpectedFacts.Tranches tranches(Json value, TrancheNames names) {
        Tranches out =
                new Tranches(
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>(),
                        new LinkedHashMap<>());
        out.copies(value, names);
        out.input(value, names);
        if (!names.inclusiveInput()) {
            for (String component : List.of("cache_read_tokens", "cache_write_tokens")) {
                Json amount = out.components().get(component);
                if (amount != null && SourceProfile.decimal(amount).signum() == 0) out.statuses().put(component, 2);
            }
        }
        return new ExpectedFacts.Tranches(
                out.components(),
                out.bases(),
                out.pointers(),
                List.of(),
                null,
                out.statuses(),
                out.conditional());
    }

    private record Tranches(
            Map<String, Json> components,
            Map<String, Integer> bases,
            Map<String, List<String>> pointers,
            Map<String, Integer> statuses,
            Map<String, Json> conditional) {
        private void copies(Json value, TrancheNames names) {
            String[][] copies = {
                {names.cached(), "cache_read_tokens"},
                {names.writes(), "cache_write_tokens"},
                {names.output(), "output_tokens"},
                {names.reasoning(), "reasoning_tokens"}
            };
            for (String[] pair : copies) {
                if (pair[0] != null && SourceProfile.isIntegral(value.member(pair[0]))) {
                    components.put(pair[1], value.member(pair[0]));
                    bases.put(pair[1], 1);
                    pointers.put(pair[1], List.of("/" + FactComparison.escape(pair[0])));
                }
            }
        }

        private void input(Json value, TrancheNames names) {
            Json in = value.member(names.input());
            Json read = value.member(names.cached());
            Json write = value.member(names.writes());
            if (available(in, read, names.inclusiveInput())) {
                boolean known = names.inclusiveInput() && known(in, read, write, true);
                bases.put("uncached_input_tokens", known ? 2 : 3);
                pointers.put(
                        "uncached_input_tokens",
                        names.inclusiveInput()
                                ? List.of("/" + names.input(), "/" + names.cached())
                                : List.of(
                                        "/" + names.input(),
                                        "/" + names.cached(),
                                        "/" + names.writes()));
                if (known) {
                    components.put(
                            "uncached_input_tokens",
                            new Json.JNumber(
                                    (names.inclusiveInput()
                                                    ? SourceProfile.decimal(in)
                                                            .subtract(SourceProfile.decimal(read))
                                                    : SourceProfile.decimal(in))
                                            .toPlainString()));
                } else if (!names.inclusiveInput() && known(in, read, write, false)) {
                    statuses.put("uncached_input_tokens", 3);
                    conditional.put("uncached_input_tokens", in);
                } else if (names.inclusiveInput() && SourceProfile.isIntegral(read)) {
                    statuses.put("uncached_input_tokens", 3);
                    conditional.put(
                            "uncached_input_tokens",
                            new Json.JNumber(
                                    SourceProfile.decimal(in)
                                            .subtract(SourceProfile.decimal(read))
                                            .toPlainString()));
                }
            }
        }
    }

    private static boolean available(Json input, Json cached, boolean inclusive) {
        return SourceProfile.isIntegral(input) && (!inclusive || SourceProfile.isIntegral(cached));
    }

    private static boolean known(Json in, Json read, Json write, boolean inclusive) {
        return SourceProfile.isIntegral(read)
                && SourceProfile.isIntegral(write)
                && SourceProfile.decimal(write).signum() == 0
                && (inclusive
                        ? SourceProfile.decimal(read).compareTo(SourceProfile.decimal(in)) <= 0
                        : SourceProfile.decimal(read).signum() == 0);
    }
}
