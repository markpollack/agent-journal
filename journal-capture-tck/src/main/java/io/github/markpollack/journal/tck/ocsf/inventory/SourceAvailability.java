package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The onboarding gate on a source specification's availability statements (agent-journal/source-availability 0.1),
 * judged against the specification's own inventory and the native artifacts it was observed on:
 * <ul>
 * <li>a fact family claimed absent while any observed path matches its vocabulary is rejected (a wholly-absent usage
 * claim on a source that reports usage);</li>
 * <li>a selector's recorded population must be the population the inventory observed; a selector that matches nothing
 * is unexercised and is rejected when marked exercised, and a claimed-present family needs an exercised selector;</li>
 * <li>vendor usage may not disappear: a data-keyed subtree (tool or user data, whose keys are not inventoried) that
 * holds token-usage keys in a native record is rejected unless the specification lists the path as a reviewed
 * exception, and every token-usage path the inventory of a native artifact observes must be in the specification.</li>
 * </ul>
 * Specifications without an availability section get the last check only (the audit of earlier routes).
 */
public final class SourceAvailability {

    static final Map<String, Pattern> VOCABULARY = Map.of(
            "token_usage", Pattern.compile("(?i)(token|usage)"),
            "cost", Pattern.compile("(?i)(cost|nano_?aiu|premium|credit|usd)"));
    static final Pattern TOKEN_KEY = Pattern.compile("(?i)tokens?($|count|[A-Z_])");

    private SourceAvailability() {
    }

    public static List<String> check(Json spec, List<NativeArtifact> natives) {
        List<String> problems = new ArrayList<>();
        Json availability = spec.member("availability");
        if (availability != null) {
            for (Json family : availability.member("families").elements()) {
                problems.addAll(claim(spec, family));
            }
        }
        problems.addAll(hiddenUsage(spec, natives));
        problems.addAll(missingUsagePaths(spec, natives));
        return problems;
    }

    /** Every observed (variant, path) of the specification's inventory. */
    static Set<String> observed(Json spec) {
        Set<String> out = new TreeSet<>();
        Json variants = spec.path("inventory", "variants");
        if (variants instanceof Json.JObject vs) {
            vs.members().forEach((v, body) -> {
                if (body.member("paths") instanceof Json.JObject ps) {
                    ps.members().keySet().forEach(p -> out.add(v + " " + p));
                }
            });
        }
        return out;
    }

    private static List<String> claim(Json spec, Json family) {
        List<String> problems = new ArrayList<>();
        String name = RawInventory.text(family, "family");
        String claim = RawInventory.text(family, "claim");
        Pattern vocabulary = VOCABULARY.get(name);
        if ("absent".equals(claim) && vocabulary != null) {
            List<String> matching = observed(spec).stream().filter(p -> vocabulary.matcher(p.substring(p.indexOf(' ') + 1)).find()).toList();
            if (!matching.isEmpty()) {
                problems.add(name + " is claimed absent but the inventory observes " + matching.size() + " path(s) of its vocabulary, for example " + matching.get(0));
            }
        }
        boolean exercised = false;
        for (Json s : family.member("selectors").elements()) {
            exercised |= selector(spec, name, s, problems) > 0;
        }
        if ("present".equals(claim) && !exercised) {
            problems.add(name + " is claimed present without an exercised selector");
        }
        return problems;
    }

    /** Checks one selector's recorded population against the inventory; returns the observed population. */
    private static int selector(Json spec, String name, Json s, List<String> problems) {
        String variant = RawInventory.text(s, "variant");
        String path = RawInventory.text(s, "path");
        Json p = spec.path("inventory", "variants", variant, "paths", path);
        int actual = p == null ? 0 : ((Json.JNumber) p.member("present_in_records")).value().intValueExact();
        int recorded = ((Json.JNumber) s.member("matched_records")).value().intValueExact();
        if (recorded != actual) {
            problems.add(name + " selector " + variant + " " + path + " records " + recorded + " match(es); the inventory observes " + actual);
        }
        if (s.member("exercised") instanceof Json.JBool b && b.value() && actual == 0) {
            problems.add(name + " selector " + variant + " " + path + " matches nothing and is marked exercised: zero matches are unexercised, never verified");
        }
        return actual;
    }

    /** Token-usage keys inside a data-keyed subtree of a native record: vendor usage hidden from the inventory. */
    private static List<String> hiddenUsage(Json spec, List<NativeArtifact> natives) {
        Set<String> exceptions = new LinkedHashSet<>();
        for (Json e : spec.path("availability", "data_keyed_exceptions") == null ? List.<Json>of() : spec.path("availability", "data_keyed_exceptions").elements()) {
            exceptions.add(RawInventory.text(e, "path"));
        }
        Walk w = new Walk(AssemblyOracle.texts(spec.path("inventory", "data_keyed")), exceptions, new ArrayList<>());
        for (NativeArtifact art : natives) {
            for (NativeArtifact.Record r : art.records()) {
                if (r.parsed()) {
                    w.walk(r.json(), "", false, art.path().getFileName() + ":" + r.index());
                }
            }
        }
        return w.problems().stream().distinct().toList();
    }

    /** One walk over native records for usage under data-keyed subtrees. */
    private record Walk(List<String> dataKeyed, Set<String> exceptions, List<String> problems) {

        void walk(Json v, String path, boolean inside, String where) {
            boolean collapsed = inside || (dataKeyed.contains(path) && !exceptions.contains(path));
            if (v instanceof Json.JObject o) {
                o.members().forEach((k, x) -> {
                    if (collapsed && TOKEN_KEY.matcher(k).find() && (x instanceof Json.JNumber || x instanceof Json.JObject)) {
                        problems.add("data-keyed subtree under " + containing(path, dataKeyed) + " holds the token-usage key " + k + " (" + where + "): vendor usage is hidden from the inventory");
                    }
                    walk(x, path.isEmpty() ? k : path + "." + k, collapsed, where);
                });
            }
            else if (v instanceof Json.JArray a) {
                a.elements().forEach(x -> walk(x, path + "[]", collapsed, where));
            }
        }
    }

    private static String containing(String path, List<String> dataKeyed) {
        for (String d : dataKeyed) {
            if (path.equals(d) || path.startsWith(d + ".") || path.startsWith(d + "[")) {
                return d;
            }
        }
        return path;
    }

    /** Every token-usage path the inventory of a native artifact observes must be in the specification. */
    private static List<String> missingUsagePaths(Json spec, List<NativeArtifact> natives) {
        List<String> problems = new ArrayList<>();
        Set<String> specified = observed(spec);
        String profile = RawInventory.text(spec, "profile");
        for (NativeArtifact art : natives) {
            Set<String> raw = observed(new Json.JObject(Map.of("inventory", RawInventory.inventory(art, art.path().getFileName().toString(), profile))));
            for (String p : raw) {
                boolean usage = TOKEN_KEY.matcher(p.substring(p.lastIndexOf('.') + 1)).find();
                if (usage && !specified.contains(p)) {
                    problems.add("the inventory of " + art.path().getFileName() + " observes the usage path " + p + ", which the specification lacks");
                }
            }
        }
        return problems;
    }
}
