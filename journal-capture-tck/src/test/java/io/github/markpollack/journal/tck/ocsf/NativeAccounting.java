package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Connects the native occurrence ledger to exact expected-fact results; a catalog property name
 * grants no credit.
 */
final class NativeAccounting {

    record Outcome(
            int record,
            String pointer,
            String classification,
            String status,
            List<String> comparisons) {}

    private NativeAccounting() {}

    static List<Outcome> account(OccurrenceLedger.Ledger ledger, OracleReport report) {
        List<Outcome> out = new ArrayList<>();
        Map<String, List<Check>> nativeRecords = new TreeMap<>();
        Map<String, List<AssertionAccounting.Assertion>> byEvent = new TreeMap<>();
        report.actualAssertions()
                .forEach(
                        a ->
                                byEvent.computeIfAbsent(
                                                a.address().split("#", 2)[0],
                                                k -> new ArrayList<>())
                                        .add(a));
        for (Check check : report.checks()) {
            if (check.evidence() == null) {
                continue;
            }
            check.evidence().nativeAddresses().stream()
                    .map(a -> a.split("#", 2)[0])
                    .distinct()
                    .forEach(
                            a ->
                                    nativeRecords
                                            .computeIfAbsent(a, k -> new ArrayList<>())
                                            .add(check));
        }
        Map<String, FieldDispositionTest.Entry> entries = new TreeMap<>();
        if (ledger.catalog() != null) {
            ledger.catalog().entries().forEach(e -> entries.put(e.key(), e));
        }
        Map<String, CanonicalObligations.Obligation> obligations = new TreeMap<>();
        CanonicalObligations.read(CanonicalConformanceTest.CATALOG)
                .obligations()
                .forEach(o -> obligations.put("obligation:" + o.id(), o));
        String artifact = ledger.source().artifact();
        for (OccurrenceLedger.Classified classified : ledger.rows()) {
            OccurrenceLedger.Occurrence occurrence = classified.occurrence();
            String address =
                    artifact + ":record=" + occurrence.record() + "#" + occurrence.pointer();
            boolean canonical = "canonical".equals(classified.classification());
            CanonicalObligations.Obligation obligation =
                    classified.entryKey() == null ? null : obligations.get(classified.entryKey());
            FieldDispositionTest.Entry entry =
                    obligation == null
                            ? classified.entryKey() == null
                                    ? null
                                    : entries.get(classified.entryKey())
                            : obligationEntry(obligation, occurrence.path());
            List<Check> matches =
                    nativeRecords
                            .getOrDefault(artifact + ":record=" + occurrence.record(), List.of())
                            .stream()
                            .filter(
                                    c ->
                                            c.evidence() != null
                                                    && c.owner().endsWith("/" + artifact)
                                                    && c.evidence().nativeAddresses().stream()
                                                            .anyMatch(
                                                                    s ->
                                                                            address.equals(s)
                                                                                    || address
                                                                                            .startsWith(
                                                                                                    s
                                                                                                                    .endsWith(
                                                                                                                            "#")
                                                                                                            ? s
                                                                                                            : s
                                                                                                                    + "/")
                                                                                    || List.of(
                                                                                                            "object",
                                                                                                            "array")
                                                                                                    .contains(
                                                                                                            occurrence
                                                                                                                    .kind())
                                                                                            && s
                                                                                                    .startsWith(
                                                                                                            address
                                                                                                                    + "/")))
                            .filter(
                                    c ->
                                            obligation == null
                                                    || List.of("object", "array")
                                                            .contains(occurrence.kind())
                                                    || c.evidence()
                                                            .nativeAddresses()
                                                            .contains(address))
                            .filter(
                                    c ->
                                            canonical
                                                    ? entry != null
                                                            && destination(entry, c, byEvent)
                                                    : "redundant"
                                                                    .equals(
                                                                            classified
                                                                                    .classification())
                                                            ? c.evidence()
                                                                    .meaning()
                                                                    .equals(
                                                                            "source.native_redundancy/v1")
                                                            : c.evidence()
                                                                            .meaning()
                                                                            .equals(
                                                                                    "source.whole_record/v1")
                                                                    || c.evidence()
                                                                            .meaning()
                                                                            .equals(
                                                                                    "source.opaque_text/v1"))
                            .toList();
            String status;
            if (canonical && ledger.source().entry() == null) {
                status = "UNSUPPORTED_VERSION";
            } else if (classified.classification().equals("no_catalog")
                    || classified.classification().equals("retained_unclassified")) {
                status = "UNCHECKED";
            } else if (classified.classification().equals("unclassified")
                    || classified.classification().equals("unresolved")) {
                status = "UNRESOLVED";
            } else if (classified.classification().equals("candidate")
                    || classified.classification().equals("common_capacity_gap")) {
                status = "UNIMPLEMENTED";
            } else if (matches.isEmpty()) {
                status = "UNCHECKED";
            } else if (matches.stream().anyMatch(c -> c.outcome() != Check.Outcome.VERIFIED)) {
                status = "NON_PASSING";
            } else {
                status = "VERIFIED";
            }
            out.add(
                    new Outcome(
                            occurrence.record(),
                            occurrence.pointer(),
                            classified.classification(),
                            status,
                            matches.stream()
                                    .map(c -> c.owner() + "|" + c.evidence().fact())
                                    .distinct()
                                    .toList()));
        }
        return out;
    }

    private static FieldDispositionTest.Entry obligationEntry(
            CanonicalObligations.Obligation obligation, String nativePath) {
        String attribute;
        if ("attribute".equals(obligation.kind())) {
            attribute = obligation.attribute();
        } else if ("cost".equals(obligation.kind())) {
            attribute = "cost_observations[].total_cost";
        } else {
            String relative =
                    nativePath.equals(obligation.payload())
                            ? ""
                            : nativePath.substring(
                                    Math.min(
                                            nativePath.length(),
                                            obligation.payload().length() + 1));
            String component =
                    obligation.components().entrySet().stream()
                            .filter(e -> e.getValue().equals(relative))
                            .map(Map.Entry::getKey)
                            .findFirst()
                            .orElse(null);
            attribute =
                    component == null ? "usage_observations" : "usage_observations[]." + component;
            if (obligation.tiers() != null && relative.startsWith(obligation.tiers() + ".")) {
                attribute = "usage_observations[].cache_write_tiers[].token_count";
            }
        }
        return new FieldDispositionTest.Entry(
                obligation.variant(),
                nativePath,
                true,
                "normalized",
                List.of(obligation.destinationClass() + "." + attribute),
                null,
                null,
                List.of(),
                null,
                null);
    }

    private static boolean destination(
            FieldDispositionTest.Entry entry,
            Check check,
            Map<String, List<AssertionAccounting.Assertion>> byEvent) {
        String checked = check.evidence().assertion();
        if (checked == null
                || check.property().endsWith("population")
                || check.evidence().meaning().equals("source.whole_record/v1")) {
            return false;
        }
        List<AssertionAccounting.Assertion> assertions =
                byEvent.getOrDefault(checked.split("#", 2)[0], List.of());
        for (String destination : entry.destinations()) {
            if (destination.equals(checked) && destination.startsWith("declaration:")) {
                return true;
            }
            if (destination.startsWith("declaration:") || destination.indexOf('.') < 0) {
                continue;
            }
            String cls = destination.substring(0, destination.indexOf('.'));
            String attribute = destination.substring(destination.indexOf('.') + 1);
            String regex =
                    "/"
                            + Pattern.quote(attribute)
                                    .replace("[]", "\\E/[0-9]+\\Q")
                                    .replace(".", "\\E/\\Q")
                            + "(?:/.*)?";
            String pointer = checked.substring(checked.indexOf('#') + 1);
            if (!assertions.isEmpty()
                    && (cls.equals("*") || assertions.get(0).className().equals(cls))
                    && pointer.matches(regex)) {
                return true;
            }
            if (assertions.stream()
                    .anyMatch(
                            a ->
                                    (cls.equals("*") || a.className().equals(cls))
                                            && (a.address().equals(checked)
                                                    || a.address().startsWith(checked + "/"))
                                            && a.path().matches(regex))) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Long> counts(List<Outcome> outcomes) {
        Map<String, Long> counts = new TreeMap<>();
        outcomes.forEach(o -> counts.merge(o.classification() + ":" + o.status(), 1L, Long::sum));
        return counts;
    }
}
