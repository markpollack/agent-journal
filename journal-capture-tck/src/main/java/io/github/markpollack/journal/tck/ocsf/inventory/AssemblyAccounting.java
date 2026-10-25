package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle.Supplied;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceLocator.Located;
import io.github.markpollack.journal.tck.verify.Check.Category;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accounting of an assembled file read the way a consumer reads it, from canonical events only: no canonical event
 * carries two observations of one quantity (usage of one scope, aggregation and model; cost of one quantity kind,
 * scope, currency and unit), and each declared total equals the sum the native records give when every quantity is
 * counted once per its native identity (a message's usage once however many records repeat it, a run's multiplier sum
 * apart from its premium requests). An absent native value is absent, never zero.
 */
final class AssemblyAccounting {

    private AssemblyAccounting() {
    }

    static void check(OracleReport report, OcsfPackage pkg, Json spec, List<Supplied> accepted) {
        List<String> duplicates = new ArrayList<>();
        for (Event e : pkg.canonical()) {
            duplicates.addAll(repeated(e, "usage_observations", List.of("scope_id", "aggregation_id", "model_ref")));
            duplicates.addAll(repeated(e, "cost_observations", List.of("quantity_kind_id", "scope_id", "currency", "quantity_unit", "model_ref")));
        }
        report.add(Category.NORMALIZATION, "assembly.accounting.distinct", AssemblyOracle.OWNER, duplicates.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                duplicates.isEmpty() ? "no canonical event carries one quantity twice" : AssemblyConsolidation.limit(duplicates));
        for (Json total : spec.member("totals") == null ? List.<Json>of() : spec.member("totals").elements()) {
            total(report, pkg, total, accepted);
        }
    }

    private static List<String> repeated(Event e, String array, List<String> identity) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Json el : e.elements(array)) {
            StringBuilder k = new StringBuilder();
            for (String m : identity) {
                Json v = "model_ref".equals(m) ? el.path("model_ref", "name") : el.member(m);
                k.append(v == null ? "-" : v.scalarText()).append('|');
            }
            if (!seen.add(k.toString())) {
                out.add(e + " carries two " + array + " for " + k);
            }
        }
        return out;
    }

    private static void total(OracleReport report, OcsfPackage pkg, Json total, List<Supplied> accepted) {
        String name = RawInventory.text(total, "name");
        Map<String, BigDecimal> natives = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Set<String> copies = new LinkedHashSet<>();
        for (Supplied s : accepted) {
            Json n = total.path("native", s.role());
            if (n != null && copies.add(s.bytes().sha256())) {
                collect(natives, AssemblyOracle.located(s), n.member("locator"), RawInventory.text(n, "value"), problems);
            }
        }
        Map<String, BigDecimal> canonical = canonical(pkg, total.member("canonical"), problems);
        BigDecimal expected = natives.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = canonical.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (natives.isEmpty()) {
            report.add(Category.NORMALIZATION, "assembly.accounting." + name, AssemblyOracle.OWNER, Outcome.NOT_APPLICABLE, "no native record reports this quantity: absent, not zero");
            return;
        }
        boolean equal = expected.compareTo(actual) == 0 && natives.keySet().equals(canonical.keySet());
        if (!equal) {
            problems.add("native " + expected.toPlainString() + " over " + natives.keySet() + "; canonical " + actual.toPlainString() + " over " + canonical.keySet());
        }
        report.add(Category.NORMALIZATION, "assembly.accounting." + name, AssemblyOracle.OWNER, problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? expected.toPlainString() + " over " + natives.size() + " native identities, counted once each" : AssemblyConsolidation.limit(problems));
    }

    /** Each selected record's (or, under {@code each}, each element's) value at {@code pointer}, once per identity; an absent value is absent, not 0. */
    private static void collect(Map<String, BigDecimal> out, List<Located> records, Json locator, String pointer, List<String> problems) {
        for (Located r : records) {
            List<Json> holders = SourceLocator.selects(r.json(), locator) ? SourceLocator.holders(r.json(), locator) : List.of();
            holders.forEach(holder -> put(out, SourceLocator.identityAt(holder, locator), SourceLocator.pointer(holder, pointer), problems));
        }
    }

    private static void put(Map<String, BigDecimal> out, String key, Json v, List<String> problems) {
        BigDecimal value = v instanceof Json.JNumber n ? n.value() : null;
        BigDecimal before = key == null || value == null ? null : out.putIfAbsent(key, value);
        if (before != null && before.compareTo(value) != 0) {
            problems.add("native records disagree on " + key + ": " + before + " and " + value);
        }
    }

    /**
     * One value per canonical identity: the event's key (or the declared constant), or with {@code element_key} each
     * matching element's own key (a per-model quantity); the matching elements' member, taken once per identity.
     */
    private static Map<String, BigDecimal> canonical(OcsfPackage pkg, Json spec, List<String> problems) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        String className = RawInventory.text(spec, "class");
        String elementKey = RawInventory.text(spec, "element_key");
        for (Event e : pkg.canonical()) {
            Json key = !className.equals(e.className) ? null : spec.member("constant") != null ? spec.member("constant") : SourceLocator.pointer(e.json, RawInventory.text(spec, "key"));
            if (key == null) {
                continue;
            }
            Map<String, BigDecimal> values = elementKey == null ? sum(e, spec, key.scalarText()) : perElement(e, spec, elementKey);
            values.forEach((k, value) -> {
                BigDecimal before = out.putIfAbsent(k, value);
                if (before != null && before.compareTo(value) != 0) {
                    problems.add("canonical events disagree on " + k + ": " + before + " and " + value);
                }
            });
        }
        return out;
    }

    /** The member of the event's matching array elements, summed under the event's key; empty when no element reports it. */
    private static Map<String, BigDecimal> sum(Event e, Json spec, String key) {
        BigDecimal value = null;
        for (Json el : e.elements(RawInventory.text(spec, "array"))) {
            if (matches(el, spec.member("where")) && el.member(RawInventory.text(spec, "member")) instanceof Json.JNumber n) {
                value = value == null ? n.value() : value.add(n.value());
            }
        }
        return value == null ? Map.of() : Map.of(key, value);
    }

    /** Each matching element's member under the element's own key. */
    private static Map<String, BigDecimal> perElement(Event e, Json spec, String elementKey) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Json el : e.elements(RawInventory.text(spec, "array"))) {
            Json k = SourceLocator.pointer(el, elementKey);
            if (k != null && matches(el, spec.member("where")) && el.member(RawInventory.text(spec, "member")) instanceof Json.JNumber n) {
                out.merge(k.scalarText(), n.value(), BigDecimal::add);
            }
        }
        return out;
    }

    /**
     * Whether an element has every member value {@code where} names; a {@code null} value requires the member to be
     * absent (or null), so a declared total can name its measurement window: a run's total states no aggregation, while
     * one unidentified response of the same run at the same scope states 3 Single response and is not part of it.
     */
    private static boolean matches(Json element, Json where) {
        if (where == null) {
            return true;
        }
        for (Map.Entry<String, Json> w : ((Json.JObject) where).members().entrySet()) {
            Json v = element.member(w.getKey());
            boolean absent = v == null || v.isNull();
            if (w.getValue().isNull() ? !absent : absent || !Json.sameValue(v, w.getValue())) {
                return false;
            }
        }
        return true;
    }
}
