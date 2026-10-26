package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The native call, snapshot and last-call quantities of the frozen Copilot sources, with their
 * distinct units and windows.
 */
final class CopilotUsageReading {

    private CopilotUsageReading() {}

    static ExpectedFacts.Tranches tranches(Json usage) {
        return SourceFacts.tranches(
                usage,
                new SourceFacts.TrancheNames(
                        "inputTokens",
                        "cacheReadTokens",
                        "cacheWriteTokens",
                        "outputTokens",
                        "reasoningTokens",
                        true));
    }

    static void call(ExpectedFacts f, int record, Json usage) {
        if (usage.member("duration") != null) {
            f.unresolvedMeanings.put(
                    record,
                    "/data/usage/duration: the frozen empirical specification gives a number but no"
                        + " unit or timing interval; milliseconds and equivalence to an OTel span"
                        + " are not established");
        }
        String model = SourceProfile.text(usage, "model");
        String provider = SourceProfile.text(usage, "provider", "kind");
        CopilotProfile.event(
                new SourceFacts.Target(f, record, "model_response", 2),
                "/data/usage",
                "usage_record",
                Map.of(),
                SourceFacts.fields(
                        "/response_uid",
                        usage.member("apiCallId"),
                        "/identity_basis_id",
                        99,
                        "/identity_basis",
                        "Provider call identifier (apiCallId)",
                        "/model_ref/name",
                        model,
                        "/model_ref/provider_kind",
                        provider));
        new SourceFacts.Target(f, record, "model_response", 2)
                .usage(
                        "/data/usage",
                        "copilot.call.usage/v1",
                        new SourceFacts.Usage(usage, tranches(usage), 2, 2, 1, model, provider));
        new SourceFacts.Target(f, record, "model_response", 2)
                .quantity(
                        "/data/usage/cost",
                        "copilot.model_multiplier/v1",
                        new SourceFacts.Quantity(
                                usage.member("cost"),
                                3,
                                "multiplier",
                                null,
                                2,
                                "Model response",
                                1,
                                null));
        new SourceFacts.Target(f, record, "model_response", 2)
                .quantity(
                        "/data/usage/copilotUsage/totalNanoAiu",
                        "copilot.nano_aiu/v1",
                        new SourceFacts.Quantity(
                                usage.path("copilotUsage", "totalNanoAiu"),
                                4,
                                "nano-AIU",
                                null,
                                2,
                                "Model response",
                                1,
                                null));
    }

    private static String provider(
            SourceFacts.Target target, Json snapshot, String pointer, String model) {
        List<Json> providers =
                snapshot.member("providerModelMetrics") == null
                        ? List.of()
                        : snapshot.member("providerModelMetrics").elements();
        List<Json> matches =
                providers.stream()
                        .filter(p -> model.equals(SourceProfile.text(p, "modelId")))
                        .toList();
        if (matches.size() != 1) return null;
        Json entry = matches.get(0);
        String kind = SourceProfile.text(entry, "provider", "kind");
        target.observation(
                "/usage_observations",
                pointer + "/providerModelMetrics/" + providers.indexOf(entry) + "/provider/kind",
                "copilot.model_provider/v1",
                SourceFacts.fields("/scope_id", 6, "/model_ref/name", model, "/aggregation_id", 2),
                SourceFacts.fields("/model_ref/provider_kind", kind));
        return kind;
    }

    static void snapshot(ExpectedFacts f, int record, Json snapshot, String pointer, int status) {
        CopilotProfile.event(
                new SourceFacts.Target(f, record, "session_context", 3),
                pointer,
                "snapshot",
                Map.of(),
                Map.of());
        SourceFacts.Target target = new SourceFacts.Target(f, record, "session_context", 3);
        new ExecutionMetricReading.Reading(target)
                .quantity(
                        snapshot.member("totalApiDurationMs"),
                        pointer + "/totalApiDurationMs",
                        "ms",
                        5,
                        null);
        Json details = snapshot.member("tokenDetails");
        if (details != null) {
            new SourceFacts.Target(f, record, "session_context", 3)
                    .usage(
                            pointer + "/tokenDetails",
                            "copilot.token_details/v1",
                            new SourceFacts.Usage(
                                    details, tokenDetails(details), 5, status, 2, null, null));
        }
        if (snapshot.member("modelMetrics") instanceof Json.JObject models) {
            models.members()
                    .forEach(
                            (model, metrics) -> {
                                new ExecutionMetricReading.Reading(target)
                                        .quantity(
                                                metrics.path("requests", "count"),
                                                pointer
                                                        + "/modelMetrics/"
                                                        + FactComparison.escape(model)
                                                        + "/requests/count",
                                                "count",
                                                6,
                                                model);
                                String provider = provider(target, snapshot, pointer, model);
                                new SourceFacts.Target(f, record, "session_context", 3)
                                        .quantity(
                                                pointer
                                                        + "/modelMetrics/"
                                                        + FactComparison.escape(model)
                                                        + "/totalNanoAiu",
                                                "copilot.model_nano_aiu/v1",
                                                new SourceFacts.Quantity(
                                                        metrics.member("totalNanoAiu"),
                                                        4,
                                                        "nano-AIU",
                                                        null,
                                                        6,
                                                        "Model",
                                                        2,
                                                        model));
                                target.quantity(
                                        pointer
                                                + "/modelMetrics/"
                                                + FactComparison.escape(model)
                                                + "/requests/cost",
                                        "copilot.model_request_cost/v1",
                                        new SourceFacts.Quantity(
                                                metrics.path("requests", "cost"),
                                                99,
                                                "request cost (unit not named by the source)",
                                                null,
                                                6,
                                                "Model",
                                                2,
                                                model));
                                Json usage = metrics.member("usage");
                                if (usage != null) {
                                    new SourceFacts.Target(f, record, "session_context", 3)
                                            .usage(
                                                    pointer
                                                            + "/modelMetrics/"
                                                            + FactComparison.escape(model)
                                                            + "/usage",
                                                    "copilot.model_metrics/v1",
                                                    new SourceFacts.Usage(
                                                            usage,
                                                            tranches(usage),
                                                            6,
                                                            status,
                                                            2,
                                                            model,
                                                            provider));
                                }
                            });
        }
        new SourceFacts.Target(f, record, "session_context", 3)
                .quantity(
                        pointer + "/totalPremiumRequestCost",
                        "copilot.premium_requests/v1",
                        new SourceFacts.Quantity(
                                snapshot.member("totalPremiumRequestCost"),
                                2,
                                "premium requests",
                                null,
                                5,
                                "Session",
                                2,
                                null));
        new SourceFacts.Target(f, record, "session_context", 3)
                .quantity(
                        pointer + "/totalNanoAiu",
                        "copilot.nano_aiu/v1",
                        new SourceFacts.Quantity(
                                snapshot.member("totalNanoAiu"),
                                4,
                                "nano-AIU",
                                null,
                                5,
                                "Session",
                                2,
                                null));
    }

    private static ExpectedFacts.Tranches tokenDetails(Json details) {
        Map<String, Json> values = new LinkedHashMap<>();
        Map<String, Integer> bases = new LinkedHashMap<>();
        Map<String, List<String>> pointers = new LinkedHashMap<>();
        String[][] names = {
            {"input", "uncached_input_tokens"},
            {"cache_read", "cache_read_tokens"},
            {"cache_write", "cache_write_tokens"},
            {"output", "output_tokens"}
        };
        for (String[] pair : names) {
            Json value = details.path(pair[0], "tokenCount");
            if (SourceProfile.isIntegral(value)) {
                values.put(pair[1], value);
                bases.put(pair[1], 1);
                pointers.put(pair[1], List.of("/" + pair[0] + "/tokenCount"));
            }
        }
        return new ExpectedFacts.Tranches(
                values, bases, pointers, List.of(), null, Map.of(), Map.of());
    }

    static void lastCall(ExpectedFacts f, int record, Json data, Json snapshot) {
        List<Json> states =
                data.member("promptCacheBreakState") == null
                        ? List.of()
                        : data.member("promptCacheBreakState").elements();
        if (states.isEmpty()) {
            return;
        }
        Json state = states.get(0);
        String model = SourceProfile.text(state, "lastActiveModel");
        Json call = model == null ? null : state.path("models", model);
        if (SourceProfile.text(call, "model_call_id") == null
                || !SourceProfile.isIntegral(snapshot.member("lastCallInputTokens"))
                || !SourceProfile.isIntegral(snapshot.member("lastCallOutputTokens"))) {
            return;
        }
        String prefix = "/data/promptCacheBreakState/0/models/" + FactComparison.escape(model);
        Map<String, Json> raw = new LinkedHashMap<>();
        raw.put("lastCallInputTokens", snapshot.member("lastCallInputTokens"));
        raw.put("lastCallOutputTokens", snapshot.member("lastCallOutputTokens"));
        raw.put("model_call", call);
        Map<String, Json> counts =
                SourceFacts.fields(
                        "inputTokens",
                        snapshot.member("lastCallInputTokens"),
                        "outputTokens",
                        snapshot.member("lastCallOutputTokens"),
                        "cacheReadTokens",
                        call.member("cache_read"),
                        "cacheWriteTokens",
                        call.member("cache_write"));
        counts.values().removeIf(java.util.Objects::isNull);
        Json operands = new Json.JObject(counts);
        ExpectedFacts.Tranches original = tranches(operands);
        Map<String, List<String>> pointers = new LinkedHashMap<>();
        pointers.put(
                "uncached_input_tokens",
                List.of("/data/accountingSnapshot/lastCallInputTokens", prefix + "/cache_read"));
        pointers.put("cache_read_tokens", List.of(prefix + "/cache_read"));
        pointers.put("cache_write_tokens", List.of(prefix + "/cache_write"));
        pointers.put("output_tokens", List.of("/data/accountingSnapshot/lastCallOutputTokens"));
        ExpectedFacts.Tranches tranches =
                new ExpectedFacts.Tranches(
                        original.values(),
                        original.bases(),
                        pointers,
                        List.of(),
                        null,
                        Map.of(),
                        Map.of());
        CopilotProfile.event(
                new SourceFacts.Target(f, record, "model_response", 2),
                prefix,
                "last_call",
                Map.of(),
                SourceFacts.fields(
                        "/response_uid",
                        call.member("model_call_id"),
                        "/identity_basis_id",
                        99,
                        "/identity_basis",
                        "Provider call identifier (apiCallId)",
                        "/model_ref/name",
                        model,
                        "/model_ref/provider_kind",
                        null));
        new SourceFacts.Target(f, record, "model_response", 2)
                .usage(
                        prefix,
                        "copilot.last_call/v1",
                        new SourceFacts.Usage(
                                new Json.JObject(raw), tranches, 2, 2, 1, model, null));
    }
}
