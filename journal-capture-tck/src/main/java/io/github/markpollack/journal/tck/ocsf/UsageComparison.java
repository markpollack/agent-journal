package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * A comparison context shared by registration and execution of the same precise usage expectations.
 */
record UsageComparison(
        OracleReport report,
        UsageComparisons.Actual actual,
        String owner,
        String id,
        List<String> support) {
    void values(ExpectedFacts facts, ExpectedFacts.Usage usage) {
        field("/source_ref/artifact", facts.artifact);
        field("/source_ref/record_index", usage.recordIndex());
        if (facts.usageData.containsKey(usage)) {
            field("/data", facts.usageData.get(usage));
        }
        field("/scope_id", usage.scopeId());
        field("/usage_status_id", usage.statusId());
        field("/aggregation_id", usage.aggregationId());
        field("/measurement_status_id", usage.measurementStatusId());
        field("/model_ref/name", usage.model());
        field("/model_ref/provider_kind", usage.provider());
        if (usage.scopeId() == 99) {
            field("/scope", usage.scopeName());
        }
        for (String name : AnalysisViews.COMPONENTS) {
            component(usage, name);
        }
        if (usage.tranches() != null) {
            tranches(usage.tranches());
        }
    }

    private void component(ExpectedFacts.Usage usage, String name) {
        List<String> pointers =
                usage.tranches() == null
                        ? List.of()
                        : usage.tranches().pointers().getOrDefault(name, List.of());
        List<String> addresses =
                pointers.isEmpty()
                        ? support
                        : pointers.stream().map(p -> support.get(0) + p).toList();
        new UsageComparison(report, actual, owner, id, addresses)
                .field("/" + name, usage.components().get(name));
    }

    private void tranches(ExpectedFacts.Tranches tranches) {
        field("/reasoning_relation_id", tranches.reasoningRelationId());
        List<Json> components = elements("components");
        field("/components/count", tranches.bases().size(), components.size());
        tranches.bases().forEach((name, basis) -> basis(tranches, components, name, basis));
        List<Json> tiers = elements("cache_write_tiers");
        field("/cache_write_tiers/count", tranches.tiers().size(), tiers.size());
        for (ExpectedFacts.Tier tier : tranches.tiers()) {
            tier(tiers, tier);
        }
    }

    private List<Json> elements(String name) {
        return actual == null || actual.value().member(name) == null
                ? List.of()
                : actual.value().member(name).elements();
    }

    private static List<Integer> indices(List<Json> values, String field, String expected) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (expected.equals(SourceProfile.text(values.get(i), field))) {
                out.add(i);
            }
        }
        return out;
    }

    private void basis(
            ExpectedFacts.Tranches tranches, List<Json> components, String name, Integer basis) {
        List<Integer> matches = indices(components, "name", name);
        field("/components/by-name/" + name, 1, matches.size());
        if (actual != null && matches.size() != 1) {
            return;
        }
        String p = "/components/" + (actual == null ? "expected:" + name : matches.get(0));
        field(p + "/name", name);
        field(p + "/component_basis_id", basis);
        field(p + "/measurement_status_id", tranches.statuses().get(name));
        field(p + "/conditional_value", tranches.conditionals().get(name));
        field(p + "/native_pointers", tranches.pointers().get(name));
    }

    private void tier(List<Json> tiers, ExpectedFacts.Tier tier) {
        List<Integer> matches = indices(tiers, "tier", tier.tier());
        field("/cache_write_tiers/by-tier/" + tier.tier(), 1, matches.size());
        if (actual != null && matches.size() != 1) {
            return;
        }
        String p =
                "/cache_write_tiers/"
                        + (actual == null ? "expected:" + tier.tier() : matches.get(0));
        field(p + "/tier", tier.tier());
        new UsageComparison(report, actual, owner, id, List.of(support.get(0) + tier.pointer()))
                .field(p + "/token_count", tier.count());
        field(p + "/native_pointers", List.of(tier.pointer()));
    }

    private void field(String pointer, Object expected) {
        field(
                pointer,
                expected,
                actual == null ? null : FactComparison.at(actual.value(), pointer));
    }

    private void field(String pointer, Object expected, Object observed) {
        String fact = id + factPointer(pointer);
        if (actual == null) {
            report.expect(
                    new OracleReport.JsonExpectation(
                            owner,
                            fact,
                            "canonical-usage/v1",
                            FactComparison.value(expected),
                            support));
        } else {
            FactComparison.compare(
                    report,
                    "fact.usage.value",
                    owner,
                    new Check.Evidence(
                            "canonical-usage/v1",
                            fact,
                            support,
                            actual.address(owner) + pointer,
                            FactComparison.value(expected),
                            FactComparison.value(observed)));
        }
    }

    private String factPointer(String pointer) {
        if (!pointer.matches("/(components|cache_write_tiers)/[0-9]+/.*")) {
            return pointer;
        }
        String[] parts = pointer.split("/", 4);
        Json item = actual.value().member(parts[1]).elements().get(Integer.parseInt(parts[2]));
        String name = SourceProfile.text(item, "components".equals(parts[1]) ? "name" : "tier");
        return "/" + parts[1] + "/expected:" + name + "/" + parts[3];
    }
}
