package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes ExpectedFacts' attribute comparisons and retains the precise result in the ordinary
 * oracle report.
 */
final class AttributedFacts {

    private record Actual(Event event, String pointer, Json value) {
        String address(String owner) {
            return owner + ":line=" + event.line + "#" + pointer;
        }
    }

    private AttributedFacts() {}

    static void register(OracleReport report, ExpectedFacts facts, String owner) {
        for (ExpectedFacts.Fact fact : facts.facts) {
            List<String> support = supports(facts, fact);
            report.expect(
                    new OracleReport.JsonExpectation(
                            owner, key(fact), fact.meaning(), FactComparison.value(1), support));
            fact.fields()
                    .forEach(
                            (pointer, value) ->
                                    report.expect(
                                            new OracleReport.JsonExpectation(
                                                    owner,
                                                    key(fact) + pointer,
                                                    fact.meaning(),
                                                    value,
                                                    support)));
        }
    }

    private static String key(ExpectedFacts.Fact fact) {
        return fact.meaning()
                + "|"
                + fact.recordIndex()
                + ":"
                + fact.className()
                + "/"
                + fact.activityId()
                + ":"
                + fact.identity()
                + ":"
                + fact.collection()
                + ":"
                + fact.memberIdentity()
                + ":native="
                + fact.support();
    }

    private static List<String> supports(ExpectedFacts facts, ExpectedFacts.Fact fact) {
        return fact.support().stream()
                .map(p -> facts.artifact + ":record=" + fact.recordIndex() + "#" + p)
                .toList();
    }

    static void check(OracleReport report, ExpectedFacts facts, List<Event> events, String owner) {
        Comparison comparison = new Comparison(report, facts, events, owner, new LinkedHashMap<>());
        facts.facts.forEach(comparison::fact);
    }

    private record Comparison(
            OracleReport report,
            ExpectedFacts facts,
            List<Event> events,
            String owner,
            Map<String, String> used) {
        private void fact(ExpectedFacts.Fact fact) {
            List<Actual> found = find(fact, events);
            String id = key(fact);
            List<String> support = supports(facts, fact);
            FactComparison.compare(
                    report,
                    "fact.population",
                    owner,
                    new Check.Evidence(
                            fact.meaning(),
                            id,
                            support,
                            found.size() == 1 ? found.get(0).address(owner) : null,
                            FactComparison.value(1),
                            FactComparison.value(found.size())));
            if (found.size() != 1) {
                fact.fields()
                        .forEach(
                                (pointer, value) ->
                                        report.add(
                                                new Check(
                                                        Check.Category.NORMALIZATION,
                                                        "fact.value",
                                                        owner,
                                                        found.isEmpty()
                                                                ? Check.Outcome.DEFECTIVE
                                                                : Check.Outcome.UNRESOLVED,
                                                        found.isEmpty()
                                                                ? "MISSING subject for " + id
                                                                : "AMBIGUOUS subject for " + id,
                                                        1,
                                                        new Check.Evidence(
                                                                fact.meaning(),
                                                                id + pointer,
                                                                support,
                                                                null,
                                                                value,
                                                                null))));
                return;
            }
            Actual actual = found.get(0);
            fact.fields()
                    .forEach((pointer, value) -> field(fact, actual, id, support, pointer, value));
        }

        private void field(
                ExpectedFacts.Fact fact,
                Actual actual,
                String id,
                List<String> support,
                String pointer,
                Json value) {
            String assertion = actual.address(owner) + pointer;
            String previous = used.putIfAbsent(assertion, id);
            if (previous != null
                    && !previous.equals(id)
                    && previous.startsWith(fact.meaning() + "|")) {
                report.add(
                        Check.Category.NORMALIZATION,
                        "fact.ambiguous",
                        owner,
                        Check.Outcome.UNRESOLVED,
                        assertion + " is claimed by both " + previous + " and " + id);
            }
            FactComparison.compare(
                    report,
                    "fact.value",
                    owner,
                    new Check.Evidence(
                            fact.meaning(),
                            id + pointer,
                            support,
                            assertion,
                            value,
                            FactComparison.at(actual.value(), pointer)));
        }
    }

    private static List<Actual> find(ExpectedFacts.Fact fact, List<Event> events) {
        List<Actual> out = new ArrayList<>();
        for (Event event : events) {
            if (!event.is(fact.className(), fact.activityId())
                    || !Objects.equals(event.recordIndex(), fact.recordIndex())
                    || !matches(event.json, fact.identity())) {
                continue;
            }
            collect(fact, event, out);
        }
        return out;
    }

    private static void collect(ExpectedFacts.Fact fact, Event event, List<Actual> out) {
        if (fact.collection() == null) {
            out.add(new Actual(event, "", event.json));
            return;
        }
        if ("/usage_observations/*/components".equals(fact.collection())) {
            components(fact, event, out);
            return;
        }
        Json collection = FactComparison.at(event.json, fact.collection());
        if (collection instanceof Json.JArray array) {
            for (int i = 0; i < array.elements().size(); i++) {
                Json value = array.elements().get(i);
                if (matches(value, fact.memberIdentity())) {
                    out.add(new Actual(event, fact.collection() + "/" + i, value));
                }
            }
        } else if (collection != null && matches(collection, fact.memberIdentity())) {
            out.add(new Actual(event, fact.collection(), collection));
        }
    }

    private static void components(ExpectedFacts.Fact fact, Event event, List<Actual> out) {
        Map<String, Json> usageIdentity = new LinkedHashMap<>(fact.memberIdentity());
        Json name = usageIdentity.remove("/name");
        List<Json> usages = event.elements("usage_observations");
        for (int i = 0; i < usages.size(); i++) {
            Json usage = usages.get(i);
            if (!matches(usage, usageIdentity)) {
                continue;
            }
            List<Json> components =
                    usage.member("components") == null
                            ? List.of()
                            : usage.member("components").elements();
            for (int j = 0; j < components.size(); j++) {
                Json component = components.get(j);
                if (Json.sameValue(name, component.member("name"))) {
                    out.add(
                            new Actual(
                                    event,
                                    "/usage_observations/" + i + "/components/" + j,
                                    component));
                }
            }
        }
    }

    private static boolean matches(Json value, Map<String, Json> identity) {
        return identity.entrySet().stream()
                .allMatch(e -> Json.sameValue(e.getValue(), FactComparison.at(value, e.getKey())));
    }
}
