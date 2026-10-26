package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Occurrence-specific usage comparisons; identities and windows select observations, never their
 * measured values.
 */
final class UsageComparisons {

    record Actual(Event event, int index, Json value) {
        String address(String owner) {
            return owner + ":line=" + event.line + "#/usage_observations/" + index;
        }
    }

    private UsageComparisons() {}

    static void register(OracleReport report, ExpectedFacts facts, String owner) {
        for (ExpectedFacts.Usage usage : facts.usages) {
            List<String> support = support(facts, usage);
            report.expect(
                    new OracleReport.JsonExpectation(
                            owner,
                            key(usage),
                            "usage-occurrence/v1",
                            FactComparison.value(1),
                            support));
            new UsageComparison(report, null, owner, key(usage), support).values(facts, usage);
        }
    }

    private static List<String> support(ExpectedFacts facts, ExpectedFacts.Usage usage) {
        return List.of(
                facts.artifact
                        + ":record="
                        + usage.recordIndex()
                        + "#"
                        + facts.usagePointers.getOrDefault(usage, ""));
    }

    static void check(OracleReport report, ExpectedFacts facts, List<Event> events, String owner) {
        List<Actual> actual = new ArrayList<>();
        for (Event event : events) {
            List<Json> usages = event.elements("usage_observations");
            for (int i = 0; i < usages.size(); i++) {
                actual.add(new Actual(event, i, usages.get(i)));
            }
        }
        Set<Actual> used = new HashSet<>();
        for (ExpectedFacts.Usage usage : facts.usages) {
            List<Actual> matches = actual.stream().filter(a -> matches(usage, a)).toList();
            String id = key(usage);
            List<String> support = support(facts, usage);
            FactComparison.compare(
                    report,
                    "fact.usage.population",
                    owner,
                    new Check.Evidence(
                            "usage-occurrence/v1",
                            id,
                            support,
                            matches.size() == 1 ? matches.get(0).address(owner) : null,
                            FactComparison.value(1),
                            FactComparison.value(matches.size())));
            if (matches.size() == 1) {
                Actual a = matches.get(0);
                if (!used.add(a)) {
                    report.add(
                            new Check(
                                    Check.Category.NORMALIZATION,
                                    "fact.usage.identity",
                                    owner,
                                    Check.Outcome.UNRESOLVED,
                                    "several native expectations identify the same candidate"
                                            + " observation: "
                                            + id,
                                    1));
                    continue;
                }
                new UsageComparison(report, a, owner, id, support).values(facts, usage);
            }
        }
        for (Actual a : actual) {
            if (!used.contains(a)) {
                FactComparison.compare(
                        report,
                        "fact.usage.unsupported",
                        owner,
                        new Check.Evidence(
                                "usage-occurrence/v1",
                                "unsupported:" + a.address(owner),
                                List.of(),
                                a.address(owner),
                                null,
                                a.value()));
            }
        }
    }

    static String key(ExpectedFacts.Usage u) {
        return "usage:"
                + u.eventRecordIndex()
                + ":"
                + u.recordIndex()
                + ":scope="
                + u.scopeId()
                + ":model="
                + u.model()
                + ":window="
                + u.aggregationId()
                + (u.scopeId() == 99 ? ":unit=" + u.scopeName() : "");
    }

    private static boolean matches(ExpectedFacts.Usage u, Actual a) {
        return Objects.equals(a.event().recordIndex(), u.eventRecordIndex())
                && Objects.equals(
                        Event.integer(a.value().path("source_ref", "record_index")),
                        u.recordIndex())
                && Objects.equals(Event.integer(a.value().member("scope_id")), u.scopeId())
                && Objects.equals(SourceProfile.text(a.value(), "model_ref", "name"), u.model())
                && (u.scopeId() != 99
                        || Objects.equals(SourceProfile.text(a.value(), "scope"), u.scopeName()))
                && Objects.equals(
                        Event.integer(a.value().member("aggregation_id")), u.aggregationId());
    }
}
