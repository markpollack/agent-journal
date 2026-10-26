package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composes native comparisons with the independently checked assembly partition, preserving
 * collection identities.
 */
public final class FinalComparisons {

    static final Set<String> ENVELOPE =
            Set.of(
                    "class_uid",
                    "class_name",
                    "category_uid",
                    "category_name",
                    "activity_id",
                    "activity_name",
                    "type_uid",
                    "type_name",
                    "severity_id",
                    "severity",
                    "time",
                    "time_dt",
                    "metadata",
                    "journal",
                    "source_ref",
                    "data",
                    "raw_data",
                    "raw_bytes_b64");

    record Supported(
            String key, String pointer, Json value, Check check, int finality, Event event) {}

    static String key(Json event, String path) {
        return FinalIdentity.key(event, path);
    }

    private FinalComparisons() {}

    public static void check(
            OcsfPackage pkg,
            OracleReport sourceReport,
            OracleReport assemblyReport,
            Json specification,
            Map<String, String> inputs) {
        boolean partition =
                assemblyReport.checks().stream()
                        .anyMatch(
                                c ->
                                        c.property().equals("assembly.entities")
                                                && c.outcome() == Check.Outcome.VERIFIED);
        List<Check> nativeChecks = List.copyOf(sourceReport.checks());
        List<AssertionAccounting.Assertion> assertions =
                AssertionAccounting.inspect(pkg, sourceReport);
        Map<String, Event> observations = new LinkedHashMap<>();
        pkg.observations().forEach(e -> observations.put(e.text("assembly", "occurrence_uid"), e));
        Context context =
                new Context(
                        pkg,
                        sourceReport,
                        specification,
                        inputs,
                        partition,
                        nativeChecks,
                        assertions,
                        observations);
        pkg.events.forEach(context::event);
    }

    private record Context(
            OcsfPackage pkg,
            OracleReport sourceReport,
            Json specification,
            Map<String, String> inputs,
            boolean partition,
            List<Check> nativeChecks,
            List<AssertionAccounting.Assertion> assertions,
            Map<String, Event> observations) {
        private void event(Event finalEvent) {
            if (!Integer.valueOf(3).equals(finalEvent.assemblyRole())) {
                return;
            }
            String finalPrefix = prefix(pkg, finalEvent);
            Map<String, List<Supported>> expected = new LinkedHashMap<>();
            List<String> precedence =
                    specification.member("precedence").elements().stream().map(Json::text).toList();
            Map<String, Integer> rank = new LinkedHashMap<>();
            inputs.forEach((name, role) -> rank.put(name, precedence.indexOf(role)));
            List<Event> group =
                    finalEvent.elements("assembly", "observation_refs").stream()
                            .map(
                                    reference ->
                                            observations.get(
                                                    SourceProfile.text(
                                                            reference, "occurrence_uid")))
                            .filter(java.util.Objects::nonNull)
                            .sorted(
                                    java.util.Comparator.comparingInt(
                                                    (Event event) ->
                                                            event.is("model_response", 1) ? 1 : 0)
                                            .thenComparingInt(
                                                    event ->
                                                            rank.getOrDefault(
                                                                    event.artifact(),
                                                                    Integer.MAX_VALUE))
                                            .thenComparingInt(event -> event.recordIndex()))
                            .toList();
            for (Event observation : group) {
                collect(
                        pkg,
                        observation,
                        group.get(0) == observation,
                        assertions,
                        nativeChecks,
                        expected);
            }
            List<AssertionAccounting.Assertion> actual =
                    assertions.stream()
                            .filter(
                                    a ->
                                            a.address().startsWith(finalPrefix)
                                                    && !a.path().startsWith("/assembly/")
                                                    && !a.status().equals("EXPLANATORY_TEXT"))
                            .toList();
            Map<String, List<AssertionAccounting.Assertion>> byKey = new LinkedHashMap<>();
            actual.forEach(
                    a ->
                            byKey.computeIfAbsent(
                                            FinalIdentity.key(finalEvent.json, a.path()),
                                            k -> new ArrayList<>())
                                    .add(a));
            AssemblyEvidence.check(
                    sourceReport,
                    pkg,
                    finalEvent,
                    group,
                    assertions,
                    FinalRelation.relation(expected, group.size()));
            Set<String> keys = new java.util.LinkedHashSet<>(expected.keySet());
            keys.addAll(byKey.keySet());
            keys.forEach(key -> compare(finalEvent, key, expected, byKey));
        }

        private void compare(
                Event finalEvent,
                String key,
                Map<String, List<Supported>> expected,
                Map<String, List<AssertionAccounting.Assertion>> byKey) {
            List<AssertionAccounting.Assertion> values = byKey.getOrDefault(key, List.of());
            List<Supported> supported =
                    FinalSelection.selected(expected.getOrDefault(key, List.of()), expected);
            supported = FinalSelection.authority(supported, finalEvent, specification, inputs);
            if (supported.isEmpty()) {
                return; // remains UNCHECKED in the output census; no source comparison exists to
                // compose
            }
            Json wanted = FinalSelection.merge(supported.stream().map(Supported::value).toList());
            if (key.contains("/missing_required/")
                    && FinalSelection.suppliedProtocolMember(supported, expected)) {
                wanted = null;
            }
            boolean conflict = wanted == null;
            List<String> nativeAddresses =
                    supported.stream()
                            .flatMap(s -> s.check().evidence().nativeAddresses().stream())
                            .distinct()
                            .toList();
            String fact = "final:" + finalEvent.text("assembly", "entity_uid") + ":" + key;
            Json expectedValue = conflict ? null : wanted;
            sourceReport.expect(
                    new OracleReport.JsonExpectation(
                            "assembly",
                            fact,
                            "assembly.native_composition/v1",
                            expectedValue,
                            nativeAddresses));
            Json actualValue = values.size() == 1 ? values.get(0).value() : null;
            String address = values.size() == 1 ? values.get(0).address() : null;
            boolean valid =
                    partition && values.size() <= 1 && Json.sameValue(expectedValue, actualValue);
            sourceReport.add(
                    new Check(
                            Check.Category.NORMALIZATION,
                            "fact.final.value",
                            "assembly",
                            valid ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE,
                            fact
                                    + (valid
                                            ? " equals its native-backed selected fact"
                                            : " is missing, repeated, different, or lacks an"
                                                    + " independently passing partition"),
                            1,
                            new Check.Evidence(
                                    "assembly.native_composition/v1",
                                    fact,
                                    nativeAddresses,
                                    address,
                                    expectedValue,
                                    actualValue)));
        }
    }

    private static void collect(
            OcsfPackage pkg,
            Event source,
            boolean primary,
            List<AssertionAccounting.Assertion> assertions,
            List<Check> checks,
            Map<String, List<Supported>> expected) {
        String prefix = prefix(pkg, source);
        for (AssertionAccounting.Assertion assertion : assertions) {
            if (!contributing(assertion, prefix, primary)) {
                continue;
            }
            Supported support = support(source, assertion, checks);
            if (support != null) {
                expected.computeIfAbsent(support.key(), k -> new ArrayList<>()).add(support);
            }
        }
    }

    private static boolean contributing(
            AssertionAccounting.Assertion assertion, String prefix, boolean primary) {

        if (!assertion.address().startsWith(prefix)
                || !assertion.status().equals("VERIFIED")
                || assertion.path().startsWith("/assembly/")) {
            return false;
        }
        return included(assertion.path(), primary);
    }

    private static boolean included(String path, boolean primary) {
        String top = path.split("/", 3)[1];
        if (Set.of("data", "raw_data", "raw_bytes_b64").contains(top)
                || path.equals("/journal/contract_uid")
                || path.equals("/journal/rule_uid")) {
            return false; // final provenance names the assembly contract; original bytes live in
            // the supporting observations
        }
        if (ENVELOPE.contains(top)
                && (!primary || ("journal".equals(top) && path.endsWith("/sequence")))) {
            return false;
        }
        return true;
    }

    private static Supported support(
            Event source, AssertionAccounting.Assertion assertion, List<Check> checks) {
        for (Check check : checks) {
            Check.Evidence e = check.evidence();
            if (e == null
                    || e.assertion() == null
                    || check.outcome() != Check.Outcome.VERIFIED
                    || check.property().endsWith("population")) {
                continue;
            }
            if (assertion.address().equals(e.assertion())
                    || assertion.address().startsWith(e.assertion() + "/")) {
                String suffix = assertion.address().substring(e.assertion().length());
                Json value = FactComparison.at(e.expected(), suffix);
                // The protocol extractor checked the complete typed slot against native protocol
                // JSON. Its typed
                // representation can be composed only after that exact extraction comparison
                // passed.
                if (!Json.sameValue(e.assertionValue(), e.actual())) {
                    value = FactComparison.at(e.assertionValue(), suffix);
                }
                String key = FinalIdentity.key(source.json, assertion.path());
                int finality = finality(source, assertion.path());
                return new Supported(key, assertion.path(), value, check, finality, source);
            }
        }
        return null;
    }

    private static int finality(Event event, String path) {
        if (path.startsWith("/usage_observations/")) {
            String index = path.split("/", 4)[2];
            Json status =
                    FactComparison.at(
                            event.json, "/usage_observations/" + index + "/usage_status_id");
            return status == null ? 0 : Event.integer(status);
        }
        return 0;
    }

    private static String prefix(OcsfPackage pkg, Event event) {
        return pkg.label() + "/" + event.artifact() + ":line=" + event.line + "#";
    }
}
