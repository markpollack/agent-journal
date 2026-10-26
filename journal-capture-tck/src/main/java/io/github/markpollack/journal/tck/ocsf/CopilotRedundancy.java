package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.NativeRedundancy.Reading;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Native Copilot repeated accounting views, associated only by explicit model and provider keys.
 */
final class CopilotRedundancy {
    private CopilotRedundancy() {}

    static void copilot(Reading reading, String profile) {
        if ("copilot_usage_report".equals(profile)) {
            snapshot(reading, reading.record().json(), "");
        } else if (reading.record().json().path("data", "accountingSnapshot")
                instanceof Json.JObject snapshot) {
            snapshot(reading, snapshot, "/data/accountingSnapshot");
            duplicates(reading, snapshot);
        }
    }

    private static void duplicates(Reading reading, Json.JObject snapshot) {
        for (String field :
                List.of(
                        "totalNanoAiu",
                        "totalApiDurationMs",
                        "tokenDetails",
                        "modelMetrics",
                        "agentMetrics",
                        "providerModelMetrics")) {
            if (reading.record().json().path("data", field) != null) {
                if (field.equals("modelMetrics")
                        && snapshot.member(field) instanceof Json.JObject models) {
                    modelDuplicates(reading, models);
                } else {
                    reading.compare("/data/accountingSnapshot/" + field, "/data/" + field);
                }
            }
        }
    }

    private static void modelDuplicates(Reading reading, Json.JObject models) {
        for (String model : models.members().keySet()) {
            for (String member : List.of("usage", "tokenDetails", "totalNanoAiu", "requests")) {
                String path = "/modelMetrics/" + FactComparison.escape(model) + "/" + member;
                reading.compare("/data/accountingSnapshot" + path, "/data" + path);
            }
        }
    }

    private static void snapshot(Reading reading, Json snapshot, String base) {
        if (!(snapshot.member("modelMetrics") instanceof Json.JObject models)) {
            return;
        }
        for (Map.Entry<String, Json> model : models.members().entrySet()) {
            String path = base + "/modelMetrics/" + FactComparison.escape(model.getKey());
            details(reading, model.getValue(), path);
        }
        if (snapshot.member("agentMetrics") instanceof Json.JObject agents
                && agents.members().keySet().equals(java.util.Set.of("main"))) {
            reading.compare(base + "/totalNanoAiu", base + "/agentMetrics/main/totalNanoAiu");
            reading.compare(
                    base + "/totalApiDurationMs", base + "/agentMetrics/main/totalApiDurationMs");
            for (String model : models.members().keySet()) {
                String name = FactComparison.escape(model);
                for (String member : List.of("usage", "totalNanoAiu", "tokenDetails", "requests")) {
                    reading.compare(
                            base + "/modelMetrics/" + name + "/" + member,
                            base + "/agentMetrics/main/modelMetrics/" + name + "/" + member);
                }
            }
        }
        providers(reading, snapshot, base, models);
    }

    private static void providers(
            Reading reading, Json snapshot, String base, Json.JObject models) {
        List<Json> providers =
                snapshot.member("providerModelMetrics") == null
                        ? List.of()
                        : snapshot.member("providerModelMetrics").elements();
        for (int i = 0; i < providers.size(); i++) {
            String name = SourceProfile.text(providers.get(i), "modelId");
            if (name == null || !models.members().containsKey(name)) {
                continue;
            }
            long count =
                    providers.stream()
                            .filter(p -> name.equals(SourceProfile.text(p, "modelId")))
                            .count();
            if (count != 1) {
                continue;
            }
            for (String member : List.of("usage", "totalNanoAiu", "tokenDetails", "requests")) {
                reading.compare(
                        base + "/modelMetrics/" + FactComparison.escape(name) + "/" + member,
                        base + "/providerModelMetrics/" + i + "/metrics/" + member);
            }
        }
    }

    private static void details(Reading reading, Json model, String path) {
        Json usage = model.member("usage");
        if (usage == null || model.member("tokenDetails") == null) {
            return;
        }
        for (String[] pair :
                new String[][] {
                    {"cache_read", "cacheReadTokens"},
                    {"cache_write", "cacheWriteTokens"},
                    {"output", "outputTokens"}
                }) {
            reading.compare(
                    path + "/usage/" + pair[1], path + "/tokenDetails/" + pair[0] + "/tokenCount");
        }
        if (SourceProfile.isIntegral(usage.member("inputTokens"))
                && SourceProfile.isIntegral(usage.member("cacheReadTokens"))) {
            Json expected =
                    new Json.JNumber(
                            SourceProfile.decimal(usage.member("inputTokens"))
                                    .subtract(
                                            SourceProfile.decimal(usage.member("cacheReadTokens")))
                                    .toPlainString());
            reading.compare(expected, path + "/usage", path + "/tokenDetails/input/tokenCount");
        }
    }
}
