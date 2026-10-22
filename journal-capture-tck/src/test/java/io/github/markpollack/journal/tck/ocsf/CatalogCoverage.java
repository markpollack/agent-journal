package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The mapping catalog's coverage state for one concept on one committed fixture, computed from obligations that are
 * known before the oracle's report is read:
 *
 * <ul>
 * <li><b>Owners</b> come from the fixture's frozen {@code expected-sources.json}: the root core ({@code main}) and one
 * {@code child:<spawn id>} core per expected child, each with the expected artifacts of the route's profile. A
 * property is owed once per source owner ({@code <core>/<artifact>}), once per core, or once for the root, as the
 * catalog's {@code property_scopes} declare; a declared global invariant is therefore judged once at its scope.</li>
 * <li><b>Properties</b> come from the concept's catalog entry for the route's oracle profile.</li>
 * <li><b>Scenario occurrence</b> comes from the native records ({@link #occurrences}): a broad property passing on a
 * fixture that holds no instance of the concept's scenario does not exercise the concept.</li>
 * <li><b>Availability</b> claims count only when they name the route's compatibility entry and a scenario, and that
 * scenario is checked against the route's recordings by {@link #availabilityRefuted}.</li>
 * </ul>
 *
 * <p>On a recording the frozen manifest marks incomplete, a property the catalog lists under
 * {@code incomplete_run_outcomes} must report exactly that outcome (UNSUPPORTED: a complete capture is not claimed);
 * it then counts as evidence, and any other outcome is a defect.
 *
 * <p>Precedence: a DEFECTIVE result anywhere makes the state {@code defective}; otherwise any owed result absent from
 * the report makes it {@code blocked} and names each missing {@code property [owner]}; otherwise UNSUPPORTED or
 * UNRESOLVED makes it {@code unresolved}. Only then can it be {@code verified} (an exercised scenario with VERIFIED
 * results), {@code retained-only} (retention evidence held), {@code unexercised} (every owed result present but no
 * occurrence) or {@code unavailable-at-source}. A missing result is never read as "no occurrence".
 */
final class CatalogCoverage {

    /** Claude content block types the stream profile reads as known blocks; anything else is an unknown block kind. */
    static final Set<String> CLAUDE_KNOWN_BLOCKS = Set.of("text", "thinking", "redacted_thinking", "tool_use", "tool_result", "image", "document");

    /** The oracle profile class a catalog entry names, to the source profile name the expected-source manifest uses. */
    static final Map<String, String> PROFILE_NAMES = Map.of("ClaudeStreamProfile", "claude_stream", "ClaudeTranscriptProfile", "claude_transcript",
            "CodexStreamProfile", "codex_stream", "CodexRolloutProfile", "codex_rollout", "AntigravityStreamProfile", "agy_stream", "OpaqueTextProfile", "opaque_text");

    private CatalogCoverage() {
    }

    /** The declared scope of an oracle property: {@code root}, {@code core} or {@code source} (the default). */
    static String scope(Json catalog, String property) {
        Json scopes = catalog.member("property_scopes");
        for (String s : List.of("root", "core")) {
            for (Json p : scopes.member(s).elements()) {
                if (property.equals(p.text())) {
                    return s;
                }
            }
        }
        return "source";
    }

    /**
     * The outcome a property honestly reports on a recording whose frozen expected sources are incomplete, as the
     * catalog declares it ({@code incomplete_run_outcomes}), or {@code null} when the property is judged as usual.
     */
    static String incompleteRunOutcome(Json catalog, String property) {
        Json o = catalog.member("incomplete_run_outcomes");
        return o == null ? null : SchemaCatalog.text(o, property);
    }

    /** The owners a property of {@code scope} is owed for on this fixture, from the frozen expected sources only. */
    static List<String> owners(String scope, String profileName, ExpectedSources expected) {
        List<String> cores = new ArrayList<>();
        cores.add("main");
        for (ExpectedSources.Child c : expected.children()) {
            cores.add("child:" + c.spawnToolCallUid());
        }
        if ("root".equals(scope)) {
            return List.of("main");
        }
        if ("core".equals(scope)) {
            return cores;
        }
        List<String> out = new ArrayList<>();
        for (ExpectedSources.Source s : expected.sources()) {
            if (profileName.equals(s.profile())) {
                out.add("main/" + s.artifact());
            }
        }
        for (ExpectedSources.Child c : expected.children()) {
            ExpectedSources.Source s = expected.source(c.artifact());
            if (s != null && profileName.equals(s.profile())) {
                out.add("child:" + c.spawnToolCallUid() + "/" + c.artifact());
            }
        }
        return out;
    }

    /** One owed result and what the report holds for it. */
    record Obligation(String property, String owner, String scenario, List<Check> results) {
        String key() {
            return property + " [" + owner + "]";
        }
    }

    /** Every result the concept owes on this fixture for the route's profile. */
    static List<Obligation> obligations(Json catalog, Json entry, MappingCatalogTest.Route route, ExpectedSources expected, OracleReport report) {
        List<Obligation> out = new ArrayList<>();
        String profileName = PROFILE_NAMES.get(route.profile());
        for (Json o : entry.member("oracle").elements()) {
            if (!route.profile().equals(SchemaCatalog.text(o, "profile"))) {
                continue;
            }
            String scenario = SchemaCatalog.text(o, "scenario");
            for (Json p : o.member("properties").elements()) {
                for (String owner : owners(scope(catalog, p.text()), profileName, expected)) {
                    List<Check> results = new ArrayList<>();
                    for (Check c : report.checks()) {
                        if (c.property().equals(p.text()) && c.owner().equals(owner)) {
                            results.add(c);
                        }
                    }
                    out.add(new Obligation(p.text(), owner, scenario, results));
                }
            }
        }
        return out;
    }

    /** The state of one concept on one fixture; see the class comment for the precedence. */
    static String state(Json catalog, Json entry, MappingCatalogTest.Route route, Path fixture, OracleReport report) {
        Path natives = OcsfFixtures.natives(fixture);
        ExpectedSources expected = ExpectedSources.beside(natives);
        if (expected == null) {
            return "unresolved (no frozen expected-source manifest: the owed population is unknown)";
        }
        String disposition = SchemaCatalog.text(entry, "disposition");
        List<Obligation> owed = obligations(catalog, entry, route, expected, report);
        List<String> missing = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        boolean exercised = false;
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (Obligation ob : owed) {
            if (ob.results().isEmpty()) {
                missing.add(ob.key());
                continue;
            }
            String honest = expected.complete() ? null : incompleteRunOutcome(catalog, ob.property());
            for (Check c : ob.results()) {
                if (c.outcome() == Outcome.DEFECTIVE) {
                    return "defective: " + c;
                }
                if (honest != null) {
                    if (!honest.equals(c.outcome().name())) {
                        return "defective: " + c + " (the frozen run is incomplete; expected " + honest + ")";
                    }
                    exercised |= ob.scenario() == null || occurrences.computeIfAbsent(ob.scenario(), s -> occurrences(s, route, natives, expected)) > 0;
                    continue;
                }
                if (c.outcome() == Outcome.UNSUPPORTED || c.outcome() == Outcome.UNRESOLVED) {
                    unresolved.add(ob.key() + " " + c.outcome());
                }
                if (c.outcome() == Outcome.VERIFIED || c.outcome() == Outcome.REPRESENTATION_DIFFERS) {
                    int n = ob.scenario() == null ? 1 : occurrences.computeIfAbsent(ob.scenario(), s -> occurrences(s, route, natives, expected));
                    exercised |= n > 0;
                }
            }
        }
        if (!missing.isEmpty()) {
            return "blocked (missing: " + String.join(", ", missing) + ")";
        }
        if (!unresolved.isEmpty()) {
            return "unresolved (" + String.join(", ", unresolved) + ")";
        }
        if (disposition.startsWith("retained-only") && !owed.isEmpty()) {
            return "retained-only (" + disposition.substring("retained-only-".length()) + ")";
        }
        if (!owed.isEmpty()) {
            if (exercised) {
                return "verified";
            }
            Set<String> scenarios = new LinkedHashSet<>();
            owed.forEach(o -> scenarios.add(o.scenario() == null ? "the checked properties" : o.scenario()));
            return "unexercised (no occurrence of " + String.join(", ", scenarios) + ")";
        }
        List<Json> claims = new ArrayList<>();
        for (Json n : entry.member("native_evidence").elements()) {
            if (route.entry().equals(SchemaCatalog.text(n, "route"))) {
                claims.add(n);
            }
        }
        if (!claims.isEmpty() && claims.stream().allMatch(n -> "absent".equals(SchemaCatalog.text(n, "availability")))) {
            return "optional-unexercised".equals(disposition) ? "unexercised (no occurrence on the route's sources)" : "unavailable-at-source";
        }
        if ("optional-unexercised".equals(disposition)) {
            return "unexercised (optional; no independent check for " + route.profile() + ")";
        }
        if (disposition.startsWith("retained-only")) {
            return "unresolved (no retention check for " + route.profile() + ")";
        }
        return "unresolved (no independent check for " + route.profile() + "; availability on " + route.entry() + " not established)";
    }

    // ------------------------------------------------------------------------------------------ native scenarios

    /** The scenario names the catalog may use; {@code path:} and {@code block:} take an argument. */
    static boolean knownScenario(String name) {
        if (name.startsWith("path:") || name.startsWith("block:")) {
            return name.indexOf(':') + 1 < name.length();
        }
        if (name.startsWith("value:")) {
            int eq = name.indexOf('=');
            return eq > "value:".length() && eq + 1 < name.length();
        }
        return Set.of("run:incomplete", "run:children", "facts:requests", "facts:results", "facts:results.structured", "facts:results.error",
                "facts:responses", "facts:prompts", "facts:usage", "facts:usage.reasoning", "facts:cost", "facts:decisions", "facts:operations", "facts:turn_completed",
                "facts:turn_started", "facts:contexts", "records:opaque").contains(name);
    }

    /**
     * How often a scenario occurs in the fixture's native artifacts of the route's profile, read from the native
     * records alone: the frozen manifest ({@code run:}), the oracle's own source profile ({@code facts:}), the raw
     * inventory ({@code path:}: records where the path holds a non-null value), records whose dotted path holds a value
     * ({@code value:<path>=<text>}), content-block types anywhere under a
     * {@code content} array ({@code block:<type>}, {@code block:media}, {@code block:unknown}) and opaque records.
     */
    static int occurrences(String scenario, MappingCatalogTest.Route route, Path natives, ExpectedSources expected) {
        if (!knownScenario(scenario)) {
            throw new IllegalArgumentException("unknown scenario " + scenario);
        }
        if ("run:incomplete".equals(scenario)) {
            return expected.complete() ? 0 : 1;
        }
        if ("run:children".equals(scenario)) {
            return expected.children().size();
        }
        String profileName = PROFILE_NAMES.get(route.profile());
        int n = 0;
        for (ExpectedSources.Source s : expected.sources()) {
            Path file = natives.resolve(s.artifact());
            if (!profileName.equals(s.profile()) || !Files.isRegularFile(file)) {
                continue;
            }
            NativeArtifact art = NativeArtifact.read(file);
            if (scenario.startsWith("facts:")) {
                ExpectedFacts f = SourceProfile.forName(profileName).expected(art,
                        new Json.JObject(Map.of("artifact", new Json.JString(s.artifact()), "profile", new Json.JString(profileName))), null);
                n += facts(f, scenario.substring("facts:".length()));
            }
            else if (scenario.startsWith("path:")) {
                n += pathOccurrences(RawInventory.inventory(art, s.artifact(), profileName), scenario.substring("path:".length()));
            }
            else if (scenario.startsWith("value:")) {
                String[] pv = scenario.substring("value:".length()).split("=", 2);
                for (NativeArtifact.Record r : art.records()) {
                    Json v = r.parsed() ? r.json().path(pv[0].split("\\.")) : null;
                    n += v != null && !v.isNull() && pv[1].equals(v.scalarText()) ? 1 : 0;
                }
            }
            else if ("records:opaque".equals(scenario)) {
                n += art.opaqueCount();
            }
            else {
                for (NativeArtifact.Record r : art.records()) {
                    if (r.parsed()) {
                        n += blocks(r.json(), scenario.substring("block:".length()), false);
                    }
                }
            }
        }
        return n;
    }

    private static int facts(ExpectedFacts f, String family) {
        return switch (family) {
            case "results.structured" -> (int) f.results.stream().filter(r -> r.structured() != null && !r.structured().isNull()).count();
            case "results.error" -> (int) f.results.stream().filter(r -> r.isError() instanceof Json.JBool b && b.value()).count();
            case "cost" -> f.costs.size();
            case "usage" -> f.usages.size();
            case "usage.reasoning" -> (int) f.usages.stream().filter(u -> u.components().containsKey("reasoning_tokens")).count();
            default -> f.counts().get(family);
        };
    }

    private static int pathOccurrences(Json inventory, String path) {
        int n = 0;
        if (inventory.member("variants") instanceof Json.JObject vs) {
            for (Json v : vs.members().values()) {
                Json p = v.path("paths", path);
                if (p != null) {
                    n += Event.integer(p.member("occurrences")) - Event.integer(p.member("null"));
                }
            }
        }
        return n;
    }

    /** Content blocks of {@code kind} in arrays named {@code content}, at any depth (tool results nest blocks). */
    private static int blocks(Json json, String kind, boolean inContent) {
        int n = 0;
        if (json instanceof Json.JObject o) {
            if (inContent && o.member("type") instanceof Json.JString t && matches(kind, t.value())) {
                n++;
            }
            for (Map.Entry<String, Json> m : o.members().entrySet()) {
                n += blocks(m.getValue(), kind, "content".equals(m.getKey()));
            }
        }
        else if (json instanceof Json.JArray a) {
            for (Json e : a.elements()) {
                n += blocks(e, kind, inContent);
            }
        }
        return n;
    }

    private static boolean matches(String kind, String type) {
        return switch (kind) {
            case "media" -> "image".equals(type) || "document".equals(type);
            case "unknown" -> !CLAUDE_KNOWN_BLOCKS.contains(type);
            default -> kind.equals(type);
        };
    }

    /**
     * Why a route-scoped availability claim is refuted by that route's committed recordings, or {@code null} when it
     * holds: an {@code absent} claim needs zero occurrences of its scenario on every recording of the route, a
     * {@code present} claim at least one. Claims without a route are history and are not checked or counted.
     */
    static String availabilityRefuted(Json claim, List<MappingCatalogTest.Route> routeFixtures, Path work) {
        String scenario = SchemaCatalog.text(claim, "scenario");
        if (scenario == null || !knownScenario(scenario)) {
            return "a route-scoped availability claim needs a known scenario, has " + scenario;
        }
        if (routeFixtures.isEmpty()) {
            return "no committed recording of route " + SchemaCatalog.text(claim, "route");
        }
        int total = 0;
        for (MappingCatalogTest.Route r : routeFixtures) {
            Path fixture = OcsfFixtures.copy(r.fixture(), work.resolve(r.fixture()));
            total += occurrences(scenario, r, OcsfFixtures.natives(fixture), ExpectedSources.beside(OcsfFixtures.natives(fixture)));
        }
        String availability = SchemaCatalog.text(claim, "availability");
        if ("absent".equals(availability) && total > 0) {
            return scenario + " occurs " + total + " time(s) on the route's recordings";
        }
        if ("present".equals(availability) && total == 0) {
            return scenario + " never occurs on the route's recordings";
        }
        return null;
    }
}
