package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks assembly explanations against already compared native-supported observations, at their
 * exact fields.
 */
final class AssemblyEvidence {

    private static final Map<Integer, String> RELATION =
            Map.of(
                    1,
                    "Identical",
                    2,
                    "Representation only",
                    3,
                    "One richer",
                    4,
                    "Each richer",
                    5,
                    "Conflicting",
                    6,
                    "Ambiguous",
                    7,
                    "Single source",
                    8,
                    "Superseded");

    private record Contribution(Event event, String pointer, Json value, boolean checked) {}

    private AssemblyEvidence() {}

    static void check(
            OracleReport report,
            OcsfPackage pkg,
            Event target,
            List<Event> group,
            List<AssertionAccounting.Assertion> assertions,
            int relation) {
        List<String> support =
                group.stream().map(e -> e.artifact() + ":record=" + e.recordIndex() + "#").toList();
        new Comparison(report, pkg, target, group, assertions, support).check(relation);
    }

    private record Comparison(
            OracleReport report,
            OcsfPackage pkg,
            Event target,
            List<Event> group,
            List<AssertionAccounting.Assertion> assertions,
            List<String> support) {
        private void check(int relation) {
            compare("/assembly/relation_class_id", FactComparison.value(relation));
            compare("/assembly/relation_class", FactComparison.value(RELATION.get(relation)));
            List<Json> entries = target.elements("assembly", "field_evidence");
            for (int i = 0; i < entries.size(); i++) {
                field(i, entries.get(i));
            }
            List<Json> conflicts = target.elements("assembly", "assembly_conflicts");
            for (int i = 0; i < conflicts.size(); i++) {
                conflict(i, conflicts.get(i));
            }
        }

        private void field(int i, Json entry) {
            String pointer = SourceProfile.text(entry, "field_pointer");
            List<Contribution> values = contributions(pkg, target, group, assertions, pointer);
            int id = Event.integer(entry.member("relation_class_id"));
            List<Contribution> expected = selected(values, id);
            boolean meanings =
                    switch (id) {
                        case 2 ->
                                values.size() > 1
                                        && values.stream()
                                                .allMatch(
                                                        v ->
                                                                Json.sameValue(
                                                                        v.value(),
                                                                        values.get(0).value()));
                        case 3 ->
                                pointer.endsWith("/missing_required")
                                        && FactComparison.at(target.json, pointer) == null
                                        && values.stream().anyMatch(v -> v.value() != null);
                        case 7 -> expected.size() == 1;
                        case 8 -> !expected.isEmpty() && expected.size() < values.size();
                        default -> false;
                    };
            List<String> wanted =
                    expected.stream()
                            .map(v -> v.event().text("assembly", "occurrence_uid"))
                            .distinct()
                            .sorted()
                            .toList();
            List<String> actual =
                    entry.member("occurrence_uids") == null
                            ? List.of()
                            : entry.member("occurrence_uids").elements().stream()
                                    .map(Json::text)
                                    .sorted()
                                    .toList();
            boolean pass =
                    meanings
                            && wanted.equals(actual)
                            && expected.stream().allMatch(Contribution::checked)
                            && RELATION.get(id).equals(SourceProfile.text(entry, "relation_class"));
            predicate(
                    "/assembly/field_evidence/" + i,
                    entry,
                    pass,
                    "relation="
                            + id
                            + " valid="
                            + meanings
                            + "; expected refs="
                            + wanted
                            + "; actual refs="
                            + actual
                            + "; contributions="
                            + values);
        }

        private void conflict(int i, Json conflict) {
            List<Contribution> values =
                    contributions(
                            pkg,
                            target,
                            group,
                            assertions,
                            SourceProfile.text(conflict, "field_pointer"));
            List<Json> expected =
                    values.stream()
                            .filter(v -> v.value() != null)
                            .map(
                                    v ->
                                            (Json)
                                                    new Json.JObject(
                                                            Map.of(
                                                                    "occurrence_uid",
                                                                    FactComparison.value(
                                                                            v.event()
                                                                                    .text(
                                                                                            "assembly",
                                                                                            "occurrence_uid")),
                                                                    "value",
                                                                    v.value())))
                            .toList();
            List<Json> actual =
                    conflict.member("observed_values") == null
                            ? List.of()
                            : conflict.member("observed_values").elements();
            boolean different =
                    values.stream()
                            .anyMatch(v -> !Json.sameValue(values.get(0).value(), v.value()));
            boolean pass =
                    different
                            && values.stream().allMatch(Contribution::checked)
                            && actual.size() == expected.size()
                            && expected.stream()
                                    .allMatch(
                                            e ->
                                                    actual.stream()
                                                                    .filter(
                                                                            a ->
                                                                                    Json.sameValue(
                                                                                            a, e))
                                                                    .count()
                                                            == 1);
            predicate(
                    "/assembly/assembly_conflicts/" + i,
                    conflict,
                    pass,
                    "expected conflict values="
                            + expected
                            + "; actual="
                            + actual
                            + "; contributions="
                            + values);
        }

        private void compare(String pointer, Json expected) {
            String owner = pkg.label() + "/" + target.artifact();
            FactComparison.compare(
                    report,
                    "assembly.evidence.value",
                    owner,
                    new Check.Evidence(
                            "assembly.checked_native_evidence/v1",
                            target.line + pointer,
                            support,
                            owner + ":line=" + target.line + "#" + pointer,
                            expected,
                            FactComparison.at(target.json, pointer)));
        }

        private void predicate(String pointer, Json value, boolean pass, String detail) {
            String owner = pkg.label() + "/" + target.artifact();
            report.add(
                    new Check(
                            Check.Category.EXPLANATION,
                            "assembly.evidence.association",
                            owner,
                            pass ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE,
                            "field evidence must name the independently checked native-supported"
                                    + " values and their actual relationship: "
                                    + detail,
                            1,
                            new Check.Evidence(
                                    "assembly.checked_native_evidence/v1",
                                    target.line + pointer,
                                    support,
                                    owner + ":line=" + target.line + "#" + pointer,
                                    FactComparison.value(true),
                                    FactComparison.value(pass),
                                    value)));
        }
    }

    private static List<Contribution> selected(List<Contribution> values, int relation) {
        if (!values.isEmpty() && values.get(0).pointer().contains("/model_ref/provider_basis")) {
            List<Contribution> reported =
                    values.stream()
                            .filter(
                                    v ->
                                            v.value() != null
                                                    && ("1".equals(v.value().scalarText())
                                                            || "Reported".equals(v.value().text())))
                            .toList();
            if (!reported.isEmpty()) {
                values = reported;
            }
        }
        int best = values.stream().mapToInt(AssemblyEvidence::finality).max().orElse(0);
        return values.stream()
                .filter(
                        v ->
                                relation == 8
                                        ? finality(v) < best
                                        : relation == 7
                                                ? finality(v) == best && v.value() != null
                                                : true)
                .toList();
    }

    private static int finality(Contribution contribution) {
        String path = contribution.pointer();
        if (path.startsWith("/usage_observations/")) {
            String root = "/usage_observations/" + path.split("/", 4)[2];
            Integer status =
                    Event.integer(
                            FactComparison.at(
                                    contribution.event().json, root + "/usage_status_id"));
            return status == null ? 0 : status;
        }
        return 0;
    }

    private static List<Contribution> contributions(
            OcsfPackage pkg,
            Event target,
            List<Event> group,
            List<AssertionAccounting.Assertion> assertions,
            String pointer) {
        List<Contribution> out = new ArrayList<>();
        if (pointer == null) {
            return out;
        }
        for (Event event : group) {
            String own = corresponding(target, event, pointer);
            if (own == null) {
                continue;
            }
            Json value = FactComparison.at(event.json, own);
            String prefix =
                    pkg.label() + "/" + event.artifact() + ":line=" + event.line + "#" + own;
            List<AssertionAccounting.Assertion> covered =
                    assertions.stream()
                            .filter(
                                    a ->
                                            a.address().equals(prefix)
                                                    || a.address().startsWith(prefix + "/"))
                            .toList();
            boolean checked =
                    value == null
                            || (!covered.isEmpty()
                                    && covered.stream()
                                            .allMatch(
                                                    a ->
                                                            "VERIFIED".equals(a.status())
                                                                    || "EXPLANATORY_TEXT"
                                                                            .equals(a.status())));
            if (value != null
                    || (pointer.endsWith("/missing_required")
                            && FactComparison.at(event.json, own.substring(0, own.lastIndexOf('/')))
                                    != null)) {
                out.add(new Contribution(event, own, value, checked));
            }
        }
        return out;
    }

    /**
     * Locate array members by the same declared member identity used by the source/final
     * comparisons.
     */
    private static String corresponding(Event target, Event source, String pointer) {
        String[] parts = pointer.substring(1).split("/");
        String own = "";
        String targetPath = "";
        for (String part : parts) {
            Json parent = FactComparison.at(source.json, own);
            targetPath += "/" + part;
            if (parent instanceof Json.JArray array) {
                String wanted = FinalComparisons.key(target.json, targetPath);
                String found = member(source, array, own, wanted);
                if (found == null) {
                    return null;
                }
                own = found;
            } else {
                own += "/" + part;
            }
        }
        return own;
    }

    private static String member(Event source, Json.JArray array, String own, String wanted) {
        String found = null;
        for (int i = 0; i < array.elements().size(); i++) {
            if (wanted.equals(FinalComparisons.key(source.json, own + "/" + i))) {
                if (found != null) {
                    return null;
                }
                found = own + "/" + i;
            }
        }
        return found;
    }
}
