package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a source reports an identity, as a {@link SourceJoin} specification declares it: an optional {@code select}
 * (RFC 6901 pointers that must resolve to the stated strings) choosing the records, and the {@code pointer} to the
 * identity value inside each selected record, or {@code pointers} for a composite identity whose every part must be
 * reported (for example an interaction id and a turn id that repeats across interactions); {@code each} names an array
 * whose elements each hold an identity; {@code constant} declares an execution singleton (every selected record is the
 * execution's one occurrence of the fact, for a source that names no identity of its own, such as a usage report). Only a
 * non-empty string or a number is an identity value.
 */
final class SourceLocator {

    private SourceLocator() {
    }

    /** One parsed record of a side, labelled with its artifact so evidence stays unambiguous across several artifacts. */
    record Located(String artifact, int index, Json json) {
        String where() {
            return artifact + ":" + index;
        }
    }

    /** How many records the locator selects. */
    static int selected(List<Located> records, Json locator) {
        int n = 0;
        for (Located r : records) {
            if (selects(r.json(), locator)) {
                n++;
            }
        }
        return n;
    }

    /**
     * The selected records grouped by the identity value they report; records without one are left out. With
     * {@code each} (a pointer to an array in the record) every element is an identity holder: {@code element_select}
     * chooses elements and {@code pointer} resolves inside the element, so one record may report several identities
     * (several tool requests of one message) and is listed under each.
     */
    static Map<String, List<Located>> keyed(List<Located> records, Json locator) {
        Map<String, List<Located>> out = new LinkedHashMap<>();
        String each = RawInventory.text(locator, "each");
        for (Located r : records) {
            if (!selects(r.json(), locator)) {
                continue;
            }
            for (Json holder : each == null ? List.of(r.json()) : elements(pointer(r.json(), each), locator.member("element_select"))) {
                String v = identityAt(holder, locator);
                if (v != null) {
                    out.computeIfAbsent(v, x -> new ArrayList<>()).add(r);
                }
            }
        }
        return out;
    }

    /** The identity-holding elements of a selected record under an {@code each} locator (the record itself without one). */
    static List<Json> holders(Json record, Json locator) {
        String each = RawInventory.text(locator, "each");
        return each == null ? List.of(record) : elements(pointer(record, each), locator.member("element_select"));
    }

    private static List<Json> elements(Json array, Json elementSelect) {
        List<Json> out = new ArrayList<>();
        if (array instanceof Json.JArray a) {
            for (Json el : a.elements()) {
                if (elementSelect == null || selectsAll(el, elementSelect)) {
                    out.add(el);
                }
            }
        }
        return out;
    }

    /** The distinct identity values a list of locators finds; a session may be reported in more than one place. */
    static Set<String> values(List<Located> records, Json locators) {
        Set<String> out = new LinkedHashSet<>();
        for (Json locator : locators.elements()) {
            out.addAll(keyed(records, locator).keySet());
        }
        return out;
    }

    static boolean selects(Json record, Json locator) {
        Json select = locator.member("select");
        return select == null || selectsAll(record, select);
    }

    private static boolean selectsAll(Json record, Json select) {
        for (Map.Entry<String, Json> e : ((Json.JObject) select).members().entrySet()) {
            if (!(pointer(record, e.getKey()) instanceof Json.JString s) || !s.value().equals(e.getValue().text())) {
                return false;
            }
        }
        return true;
    }

    /** The record's identity under the locator: one value, or the parts of a composite identity as a JSON array. */
    static String identityAt(Json record, Json locator) {
        if (locator.member("constant") != null) {
            // an execution singleton: every selected record is the execution's one occurrence of this fact (declared, never inferred)
            return locator.member("constant").text();
        }
        Json parts = locator.member("pointers");
        if (parts == null) {
            return identity(pointer(record, RawInventory.text(locator, "pointer")));
        }
        List<Json> values = new ArrayList<>();
        for (Json p : parts.elements()) {
            String v = identity(pointer(record, p.text()));
            if (v == null) {
                return null;
            }
            values.add(new Json.JString(v));
        }
        return NeutralJson.write(new Json.JArray(values));
    }

    static String identity(Json v) {
        if (v instanceof Json.JString s) {
            return s.value().isEmpty() ? null : s.value();
        }
        return v instanceof Json.JNumber n ? n.text() : null;
    }

    /** RFC 6901 resolution over the neutral model; {@code null} when the pointer does not resolve. */
    static Json pointer(Json root, String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return root;
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("not an RFC 6901 pointer: " + pointer);
        }
        Json at = root;
        for (String raw : pointer.substring(1).split("/", -1)) {
            at = step(at, raw.replace("~1", "/").replace("~0", "~"));
            if (at == null) {
                return null;
            }
        }
        return at;
    }

    private static Json step(Json at, String token) {
        if (at instanceof Json.JObject o) {
            return o.members().get(token);
        }
        if (at instanceof Json.JArray a && token.matches("0|[1-9][0-9]{0,8}")) {
            int i = Integer.parseInt(token);
            return i < a.elements().size() ? a.elements().get(i) : null;
        }
        return null;
    }
}
