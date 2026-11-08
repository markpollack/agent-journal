package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.markpollack.journal.tck.verify.Check;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Experimental onboarding visibility complements, but never weakens or implies, admission. */
class CliOnboardingTest {
    private static final Set<String> LEGACY_DEBT = Set.of(
            "claude-code/stream-json/2.1.299-sdk", "claude-code/stream-json/2.1.303-sdk",
            "claude-code/session-transcript/2.1.299-session-file", "claude-code/session-transcript/2.1.303-session-file",
            "claude-code/session-transcript/2.1.304-session-file", "github-copilot-cli/events/1.0.100-stdout",
            "github-copilot-cli/events/1.0.100-session-file", "github-copilot-cli/otel/1.0.100", "github-copilot-cli/usage-report/1.0.100",
            "junie/session-summary/3813.7", "junie/session-events/3813.7", "junie/json-stream/3813.7-stdout",
            "codex-cli/rollout/0.160.1",
            // reviewed 2026-11-08 (Owner: CLI versions are recorded, not gated; the gate weakening is approved): the 2.1.316
            // SDK routes became version-neutral and keep their debt
            "claude-code/stream-json/sdk-stdio", "claude-code/session-transcript/session-file");

    private static JsonNode read(Path file) { return LiveScenarioTest.readFile(file); }
    private static JsonNode registry() { return read(MappingCatalogTest.COMPATIBILITY); }
    private static JsonNode catalog() { return read(MappingCatalogTest.PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/mapping-catalog/mapping-catalog.json")); }
    private static JsonNode registration(JsonNode catalog, String route) {
        for (JsonNode r : catalog.path("onboarding_obligations")) if (r.path("route").asText().equals(route)) return r;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    static List<String> registrationProblems(JsonNode registry, JsonNode catalog) {
        return registrationProblems(registry, catalog, VendorStreamInventoryTest.registeredProfiles(), LiveScenarioTest.read("roster.json"));
    }
    static List<String> registrationProblems(JsonNode registry, JsonNode catalog, List<VendorStreamInventoryTest.Profile> profiles, JsonNode roster) {
        List<String> problems = new ArrayList<>();
        Set<String> debt = new TreeSet<>();
        for (JsonNode d : registry.path("onboarding_debt")) {
            if (d.path("reason").asText().isBlank()) problems.add("unexplained legacy debt");
            debt.add(d.path("route").asText());
        }
        if (!debt.equals(LEGACY_DEBT)) problems.add("legacy debt roster changed: explicit review required");
        for (JsonNode entry : registry.path("entries")) {
            String id = entry.path("id").asText();
            if ("admitted".equals(entry.path("admission").asText()) || LEGACY_DEBT.contains(id)) continue;
            JsonNode r = registration(catalog, id);
            String inv = entry.path("onboarding_inventory").asText();
            if (inv.isBlank() || r.isMissingNode() || !inv.equals(r.path("inventory").asText())) { problems.add(id + ": missing onboarding registration"); continue; }
            var profile = profiles.stream().filter(p -> p.inventory().equals(inv)).findFirst();
            if (profile.isEmpty() || !Files.isRegularFile(MappingCatalogTest.PROJECT.resolve(inv))) { problems.add(id + ": inventory is absent from executed inventory set"); continue; }
            if (!VendorStreamInventoryTest.inventory(profile.orElseThrow()).path("compatibility_entry").asText().equals(id)) problems.add(id + ": inventory belongs to another route");
            if (!roster.path("applicability").findValuesAsText("route").contains(id)) problems.add(id + ": missing roster registration");
        }
        return problems;
    }
    static List<String> obligationProblems(JsonNode inventory, JsonNode registered) {
        List<String> problems = new ArrayList<>(); Set<String> seen = new TreeSet<>();
        for (JsonNode obligation : inventory.path("coverage_obligations")) {
            String id = obligation.path("id").asText(); if (id.startsWith("LIVE-")) seen.add(id);
            if (!registered.path("obligations").findValuesAsText("id").contains(id)) problems.add("missing known obligation " + id);
            for (JsonNode concept : obligation.path("concepts")) if (!MappingCatalogTest.INITIAL_CONCEPTS.contains(concept.asText())) problems.add("unknown concept " + concept);
            JsonNode family = VendorStreamInventoryTest.families(inventory).get(obligation.path("variant").asText());
            if (family == null || !VendorStreamInventoryTest.paths(family).stream().anyMatch(p -> obligation.path("field").asText().equals(p)
                    || obligation.path("field").asText().startsWith(p + "."))) problems.add(id + ": field has no vendor basis");
            if (obligation.path("basis").asText().isBlank()) problems.add(id + ": missing applicability basis");
        }
        if (seen.size() != 10) problems.add("ten LIVE areas required");
        return problems;
    }
    static List<Integer> nativeRecords(Path nativeFile, String profile, JsonNode selector, String field) throws Exception {
        List<Integer> records = new ArrayList<>(); int index = 0;
        for (String line : Files.readAllLines(nativeFile)) {
            index++;
            if (!selector.path("variant").asText().equals(VendorStreamInventoryTest.variant(profile, line))) continue;
            JsonNode record = LiveScenarioTest.MAPPER.readTree(line); boolean match = true;
            var fields = selector.path("equals").fields();
            while (fields.hasNext()) {
                var condition = fields.next(); JsonNode actual = record;
                for (String part : condition.getKey().split("\\.")) actual = actual.path(part);
                match &= actual.equals(condition.getValue());
            }
            JsonNode nativeField = record;
            for (String part : field.split("\\.")) nativeField = nativeField.path(part);
            if (match && !nativeField.isMissingNode()) records.add(index);
        }
        return records;
    }
    static List<String> witnessProblems(String id, String state, int count, int minimum, List<Check> checks, String property) {
        List<String> problems = new ArrayList<>();
        if (!Set.of("observed", "unexercised", "unsupported", "source-unavailable", "not-applicable").contains(state)) problems.add(id + ": invalid coverage state");
        if ("observed".equals(state)) {
            if (minimum < 1 || count < Math.max(1, minimum)) problems.add(id + ": zero or insufficient native matches (" + count + "/" + minimum + ")");
            if (checks.stream().noneMatch(c -> c.property().equals(property) && c.outcome() == Check.Outcome.VERIFIED
                    && c.occurrences() != null && c.occurrences() > 0)) problems.add(id + ": no executed nonzero independent check " + property);
            if (checks.stream().anyMatch(c -> c.property().equals(property) && c.outcome() == Check.Outcome.DEFECTIVE)) problems.add(id + ": defective independent check " + property);
        }
        return problems;
    }
    static List<Check> attributed(List<Check> checks, JsonNode obligation, List<Integer> records) {
        String pointer = obligation.path("canonical_pointer").asText();
        return checks.stream().filter(c -> c.evidence() != null && c.evidence().assertion() != null
                && c.evidence().assertion().contains(pointer) && c.evidence().nativeAddresses().stream()
                .anyMatch(a -> records.stream().anyMatch(n -> a.contains(":record=" + n + "#")))).toList();
    }
    static List<String> assess(JsonNode obligation, String state, List<Integer> records, List<Check> checks) {
        String id = obligation.path("id").asText(); String property = obligation.path("property").asText();
        List<String> problems = witnessProblems(id, state, records.size(), obligation.path("minimum").asInt(1), attributed(checks, obligation, records), property);
        if ("observed".equals(state)) for (int record : records) {
            if (!witnessProblems(id, state, 1, 1, attributed(checks, obligation, List.of(record)), property).isEmpty()) problems.add(id + ": record " + record + " lacks its own field comparison");
        }
        return problems;
    }
    static Map<String, List<Integer>> denominator(JsonNode inv, Path nativeFile, String profile) throws Exception {
        Map<String, List<Integer>> out = new java.util.LinkedHashMap<>();
        for (JsonNode o : inv.path("coverage_obligations")) out.put(o.path("id").asText(), nativeRecords(nativeFile, profile, o.path("selector"), o.path("field").asText()));
        return out;
    }
    @Test void experimentalRoutesCannotDisappearAndKnownObligationsHaveNativeWitnessAccounting(@TempDir Path work) throws Exception {
        assertThat(registrationProblems(registry(), catalog())).isEmpty();
        List<Object> rows = new ArrayList<>(); List<String> allProblems = new ArrayList<>();
        // Execute every registered inventory, rather than a second, narrower hard-coded route roster.
        for (var profile : VendorStreamInventoryTest.registeredProfiles()) {
            JsonNode inv = VendorStreamInventoryTest.inventory(profile); JsonNode reg = registration(catalog(), inv.path("compatibility_entry").asText());
            if (reg.isMissingNode()) continue;
            assertThat(obligationProblems(inv, reg)).isEmpty();
            for (String fixture : profile.all()) {
                Path copy = OcsfFixtures.copy(fixture, work.resolve(profile.profile()));
                // No candidate rules, package or acceptance has been examined at this point.
                Map<String, List<Integer>> nativeOccurrences = denominator(inv, OcsfFixtures.natives(copy).resolve("stream.jsonl"), profile.profile());
                var report = OcsfFixtures.verify(copy);
                for (JsonNode obligation : inv.path("coverage_obligations")) {
                    String id = obligation.path("id").asText();
                    String state = reg.path("obligations").findParents("id").stream().filter(n -> n.path("id").asText().equals(id)).findFirst().orElseThrow().path("state").asText();
                    List<Integer> records = nativeOccurrences.get(id);
                    assess(obligation, state, records, report.checks()).forEach(p -> allProblems.add(profile.profile() + ": " + p));
                    rows.add(Map.of("route", inv.path("compatibility_entry").asText(), "obligation", obligation, "state", state,
                            "fixture", fixture, "evidence_class", LiveScenarioTest.fixtureClass(fixture), "native_occurrences", records.size(), "native_records", records,
                            "checks", attributed(report.checks(), obligation, records).stream().filter(c -> c.property().equals(obligation.path("property").asText())).toList()));
                }
            }
        }
        Files.writeString(MappingCatalogTest.outputDirectory().resolve("cli-onboarding.json"), MappingCatalogTest.PRETTY.writeValueAsString(Map.of("kind", "agent-journal/cli-onboarding", "rows", rows, "legacy_debt", registry().path("onboarding_debt"))) + "\n");
        assertThat(allProblems).isEmpty();
    }
    @Test void firstTimeRouteNeedsNoPreviousMappingToExposeMissingRegistrationObligationAndZeroMatch(@TempDir Path work) throws Exception {
        ObjectNode first = registry().deepCopy(); String id = "first-cli/new-native-route/1";
        ObjectNode entry = ((ArrayNode) first.path("entries")).addObject().put("id", id).put("admission", "experimental");
        assertThat(registrationProblems(first, catalog())).contains(id + ": missing onboarding registration");
        ObjectNode inv = VendorStreamInventoryTest.inventory(VendorStreamInventoryTest.CURRENT.get(0)).deepCopy(); inv.put("compatibility_entry", id);
        Path inventory = work.resolve("first-vendor-inventory.json"); Files.writeString(inventory, inv.toString());
        ObjectNode catalogs = catalog().deepCopy(); ObjectNode declared = registration(catalog(), "codex-cli/exec-json-stream/0.166.0").deepCopy();
        declared.put("route", id).put("inventory", inventory.toString()); ((ArrayNode) catalogs.path("onboarding_obligations")).add(declared);
        entry.put("onboarding_inventory", inventory.toString());
        assertThat(registrationProblems(first, catalogs)).contains(id + ": inventory is absent from executed inventory set");
        var original = VendorStreamInventoryTest.CURRENT.get(0);
        var isolated = new VendorStreamInventoryTest.Profile(original.profile(), inventory.toString(), "unused", List.of(), original.synthetic(), Set.of());
        List<VendorStreamInventoryTest.Profile> profiles = new ArrayList<>(VendorStreamInventoryTest.registeredProfiles()); profiles.add(isolated);
        ObjectNode roster = LiveScenarioTest.read("roster.json").deepCopy(); ((ArrayNode) roster.path("applicability")).addObject().put("route", id);
        assertThat(registrationProblems(first, catalogs, profiles, roster)).isEmpty();
        assertThat(obligationProblems(inv, declared)).isEmpty();
        ((ArrayNode) declared.path("obligations")).remove(1);
        assertThat(obligationProblems(inv, declared)).contains("missing known obligation LIVE-02");
        Path raw = LiveScenarioTest.FIXTURES.resolve(original.synthetic().get(0)).resolve("native/stream.jsonl");
        Map<String, List<Integer>> occurrences = denominator(inv, raw, original.profile());
        JsonNode output = inv.path("coverage_obligations").findParents("id").stream().filter(o -> o.path("id").asText().equals("command-combined-output")).findFirst().orElseThrow();
        assertThat(occurrences.get("command-combined-output")).hasSize(2);
        // No mapper/package/checks exist for the isolated route: visibility can pass, support cannot.
        assertThat(assess(output, "observed", occurrences.get("command-combined-output"), List.of())).anyMatch(p -> p.contains("no executed"));
        assertThat(assess(output, "unexercised", occurrences.get("command-combined-output"), List.of())).isEmpty();
        ObjectNode absent = output.deepCopy(); ((ObjectNode) absent.path("selector")).put("variant", "absent/native-family"); absent.put("minimum", 0);
        List<Integer> zero = nativeRecords(raw, original.profile(), absent.path("selector"), absent.path("field").asText());
        assertThat(assess(absent, "observed", zero, List.of())).anyMatch(p -> p.contains("zero or insufficient"));
        var unrelated = new Check(Check.Category.NORMALIZATION, "fact.value", "main/stream.jsonl", Check.Outcome.VERIFIED, "another occurrence", 1,
                new Check.Evidence("native", "other", List.of("stream.jsonl:record=999#"), "main:line=999#/tool_operation/combined_output", null, null));
        assertThat(assess(output, "observed", occurrences.get("command-combined-output"), List.of(unrelated))).anyMatch(p -> p.contains("lacks its own"));
    }
}
