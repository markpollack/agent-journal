package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.FinalComparisons.Supported;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native-backed final observation selection. */
final class FinalSelection {
    private FinalSelection() {}

    static List<Supported> selected(List<Supported> values, Map<String, List<Supported>> all) {
        if (values.isEmpty()) {
            return values;
        }
        int finality = values.stream().mapToInt(Supported::finality).max().orElse(0);
        values = values.stream().filter(s -> s.finality() == finality).toList();
        String key = values.get(0).key();
        if (key.contains("/model_ref/provider_basis")) {
            List<Supported> reported =
                    values.stream()
                            .filter(
                                    v ->
                                            v.value() != null
                                                    && ("1".equals(v.value().scalarText())
                                                            || "Reported".equals(v.value().text())))
                            .toList();
            if (!reported.isEmpty()) {
                return reported;
            }
        }
        int origin = origin(key);
        if (origin >= 0) {
            String parent = key.substring(0, origin);
            List<Supported> sources = all.getOrDefault(parent + "/source_ref/artifact", List.of());
            int best = sources.stream().mapToInt(Supported::finality).max().orElse(0);
            Event source =
                    sources.stream()
                            .filter(v -> v.finality() == best)
                            .map(Supported::event)
                            .findFirst()
                            .orElse(null);
            if (source != null) {
                return values.stream().filter(v -> v.event() == source).toList();
            }
            if (key.endsWith("/data") && based(values.get(0))) {
                return List.of(values.get(0));
            }
        }
        return values;
    }

    private static int origin(String key) {
        int index = key.indexOf("/source_ref/");
        if (index < 0) {
            index = key.indexOf("/components/");
        }
        if (index < 0) {
            index = key.indexOf("/native_pointers/");
        }
        return index < 0 && key.endsWith("/data") ? key.length() - 5 : index;
    }

    private static boolean based(Supported first) {
        String pointer = first.pointer().substring(0, first.pointer().length() - 5);
        Json object = FactComparison.at(first.event().json, pointer);
        return object != null && object.member("basis") != null;
    }

    static List<Supported> authority(
            List<Supported> values, Event event, Json specification, Map<String, String> inputs) {
        if (values.isEmpty()) {
            return values;
        }
        for (Json rule :
                specification.member("authorities") == null
                        ? List.<Json>of()
                        : specification.member("authorities").elements()) {
            if (event.className.equals(SourceProfile.text(rule, "class"))
                    && values.get(0).pointer().equals(SourceProfile.text(rule, "pointer"))) {
                Set<String> artifacts = new java.util.HashSet<>();
                inputs.forEach(
                        (name, role) -> {
                            if (role.equals(SourceProfile.text(rule, "role"))) artifacts.add(name);
                        });
                List<Supported> chosen =
                        values.stream()
                                .filter(v -> artifacts.contains(v.event().artifact()))
                                .toList();
                if (!chosen.isEmpty()) {
                    return chosen;
                }
            }
        }
        return values;
    }

    static boolean suppliedProtocolMember(
            List<Supported> missing, Map<String, List<Supported>> expected) {
        String name = missing.get(0).value().text();
        String protocol = missing.get(0).key().split("/missing_required/", 2)[0];
        return expected.entrySet().stream()
                .filter(e -> e.getKey().startsWith(protocol + "/"))
                .flatMap(e -> e.getValue().stream())
                .anyMatch(
                        s ->
                                s.check().property().equals("fact.mcp.value")
                                        && s.check().evidence().expected().member(name) != null);
    }

    static Json merge(List<Json> values) {
        if (values.isEmpty()) {
            return null;
        }
        Json first = values.get(0);
        if (values.stream().allMatch(v -> Json.sameValue(first, v))) {
            return first;
        }
        if (values.stream().allMatch(v -> v instanceof Json.JObject)) {
            Map<String, List<Json>> members = new LinkedHashMap<>();
            values.forEach(
                    v ->
                            ((Json.JObject) v)
                                    .members()
                                    .forEach(
                                            (name, member) ->
                                                    members.computeIfAbsent(
                                                                    name, k -> new ArrayList<>())
                                                            .add(member)));
            Map<String, Json> merged = new LinkedHashMap<>();
            members.forEach(
                    (name, contributions) -> {
                        Json value = merge(contributions);
                        if (value != null) {
                            merged.put(name, value);
                        }
                    });
            return new Json.JObject(merged);
        }
        return null;
    }
}
