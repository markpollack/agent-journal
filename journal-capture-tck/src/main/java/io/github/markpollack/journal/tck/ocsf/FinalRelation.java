package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.FinalComparisons.Supported;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native-backed final observation relation. */
final class FinalRelation {
    private FinalRelation() {}

    static int relation(Map<String, List<Supported>> expected, int groupSize) {
        Comparison comparison = new Comparison(groupSize);
        for (Map.Entry<String, List<Supported>> entry : expected.entrySet()) {
            if (!comparison.add(
                    entry.getKey(), FinalSelection.selected(entry.getValue(), expected))) {
                return 5;
            }
        }
        if (!comparison.unique.isEmpty()) {
            return comparison.unique.size() > 1 ? 4 : 3;
        }
        return comparison.representation ? 2 : 1;
    }

    private static final class Comparison {
        private final int groupSize;
        private final Set<Event> unique = new java.util.HashSet<>();
        private boolean representation;

        private Comparison(int groupSize) {
            this.groupSize = groupSize;
        }

        private boolean add(String key, List<Supported> selected) {
            if (excluded(key) || selected.isEmpty() || metadata(key, selected.get(0))) {
                return true;
            }
            Json first = selected.get(0).value();
            if (selected.stream().anyMatch(v -> !Json.sameValue(first, v.value()))) {
                return nested(selected);
            }
            if (selected.stream().anyMatch(v -> !java.util.Objects.equals(first, v.value()))) {
                representation = true;
            }
            Set<Event> contributors = new java.util.HashSet<>();
            selected.forEach(v -> contributors.add(v.event()));
            if (contributors.size() == 1 && groupSize > 1) {
                unique.addAll(contributors);
            }
            return true;
        }

        private boolean nested(List<Supported> selected) {
            if (!selected.stream().allMatch(v -> v.value() instanceof Json.JObject)) {
                return false;
            }
            Map<String, List<Supported>> nested = new LinkedHashMap<>();
            selected.forEach(
                    v ->
                            ((Json.JObject) v.value())
                                    .members()
                                    .forEach(
                                            (name, value) ->
                                                    nested.computeIfAbsent(
                                                                    "/payload/" + name,
                                                                    k -> new ArrayList<>())
                                                            .add(
                                                                    new Supported(
                                                                            "/payload/" + name,
                                                                            v.pointer(),
                                                                            value,
                                                                            v.check(),
                                                                            v.finality(),
                                                                            v.event()))));
            int relation = relation(nested, groupSize);
            if (relation == 5) {
                return false;
            }
            if (relation == 3 || relation == 4) {
                nested.values().stream()
                        .filter(v -> v.size() == 1)
                        .forEach(v -> unique.add(v.get(0).event()));
            }
            return true;
        }
    }

    private static boolean excluded(String key) {
        return FinalComparisons.ENVELOPE.contains(key.split("/", 3)[1])
                || key.contains("/source_ref/")
                || key.contains("/components/")
                || key.contains("/native_pointers/")
                || key.contains("/provider_basis")
                || key.contains("/missing_required/");
    }

    private static boolean metadata(String key, Supported selected) {
        if (!key.endsWith("/data")) {
            return false;
        }
        String pointer = selected.pointer();
        Json parent =
                FactComparison.at(
                        selected.event().json, pointer.substring(0, pointer.length() - 5));
        return parent != null
                && (parent.member("basis") != null || parent.member("source_ref") != null);
    }
}
