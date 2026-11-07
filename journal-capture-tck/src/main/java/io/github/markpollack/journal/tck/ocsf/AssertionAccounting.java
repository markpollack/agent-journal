package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every concrete output assertion, credited only by its own executed comparison and exact candidate
 * value.
 */
public final class AssertionAccounting {

    /** Members asserted as one whole value: the native residuals, plus every declared payload slot ({@link PayloadBoundary}). */
    private static final Set<String> RESIDUALS = Set.of("data", "raw_data", "raw_bytes_b64");
    /** The payload slots used when a package declares no definition closure to read them from. */
    private static final Set<String> UNDECLARED_PAYLOADS =
            Set.of("arguments", "structured_result", "schema_document", "mcp_arguments", "mcp_structured_content");
    private static final Set<String> EXPLANATORY =
            Set.of(
                    "basis",
                    "derivation",
                    "tool_kind_basis",
                    "projection_basis",
                    "selection_basis",
                    "resolution");

    public record Assertion(
            String address,
            String path,
            boolean canonical,
            String className,
            Json value,
            String status,
            List<String> checks) {
        public Assertion {
            checks = List.copyOf(checks);
        }
    }

    private AssertionAccounting() {}

    public static List<Assertion> inspect(OcsfPackage pkg, OracleReport report) {
        List<Assertion> out = new ArrayList<>();
        Map<String, List<Check>> attributed = new java.util.HashMap<>();
        report.checks().forEach(check -> index(check, attributed));
        PayloadBoundary payloads = PayloadBoundary.of(pkg.catalog);
        Set<String> whole = new java.util.HashSet<>(RESIDUALS);
        whole.addAll(payloads.names().isEmpty() ? UNDECLARED_PAYLOADS : payloads.names());
        for (OcsfPackage core : pkg.all()) {
            for (Event event : core.events) {
                if (event.className != null) {
                    String prefix =
                            core.label() + "/" + event.artifact() + ":line=" + event.line + "#";
                    new Walker(
                                    prefix,
                                    event.canonical(),
                                    event.className,
                                    attributed.getOrDefault(prefix, List.of()),
                                    out,
                                    whole)
                            .walk(event.json, "");
                }
            }
        }
        return out;
    }

    private static void index(Check check, Map<String, List<Check>> attributed) {
        if (check.evidence() != null
                && check.evidence().assertion() != null
                && !check.property().endsWith("population")
                && !check.property().endsWith("unsupported")) {
            String address = check.evidence().assertion();
            int boundary = address.indexOf('#');
            if (boundary >= 0) {
                attributed
                        .computeIfAbsent(
                                address.substring(0, boundary + 1), key -> new ArrayList<>())
                        .add(check);
            }
        }
    }

    private record Walker(
            String prefix,
            boolean canonical,
            String className,
            List<Check> checks,
            List<Assertion> out,
            Set<String> whole) {
        /** One assertion per leaf; a declared payload slot or a native residual is one whole-value assertion, never descended into. */
        private void walk(Json value, String pointer) {
            String member = pointer.substring(pointer.lastIndexOf('/') + 1);
            if (EXPLANATORY.contains(member)) {
                out.add(
                        new Assertion(
                                prefix + pointer,
                                pointer,
                                canonical,
                                className,
                                value,
                                "EXPLANATORY_TEXT",
                                List.of()));
                return;
            }
            if (!whole.contains(member) && children(value, pointer)) {
                return;
            }
            String address = prefix + pointer;
            List<Check> matching = matching(address, value);
            String status =
                    matching.isEmpty()
                            ? "UNCHECKED"
                            : matching.stream().anyMatch(c -> c.outcome() != Check.Outcome.VERIFIED)
                                    ? "NON_PASSING"
                                    : "VERIFIED";
            out.add(
                    new Assertion(
                            address,
                            pointer,
                            canonical,
                            className,
                            value,
                            status,
                            matching.stream()
                                    .map(c -> c.owner() + "|" + c.evidence().fact())
                                    .toList()));
        }

        private List<Check> matching(String address, Json value) {
            List<Check> matching = new ArrayList<>();
            for (Check check : checks) {
                String checked = check.evidence().assertion();
                if (address.equals(checked) || address.startsWith(checked + "/")) {
                    String suffix = address.substring(checked.length());
                    if (Json.sameValue(
                            FactComparison.at(check.evidence().assertionValue(), suffix), value)) {
                        matching.add(check);
                    }
                }
            }
            return matching;
        }

        private boolean children(Json value, String pointer) {
            if (value instanceof Json.JObject object && !object.members().isEmpty()) {
                object.members()
                        .forEach(
                                (key, child) ->
                                        walk(child, pointer + "/" + FactComparison.escape(key)));
                return true;
            }
            if (value instanceof Json.JArray array && !array.elements().isEmpty()) {
                for (int i = 0; i < array.elements().size(); i++) {
                    walk(array.elements().get(i), pointer + "/" + i);
                }
                return true;
            }
            return false;
        }
    }

    public static Map<String, Long> counts(List<Assertion> assertions, boolean canonical) {
        Map<String, Long> counts = new java.util.TreeMap<>();
        assertions.stream()
                .filter(a -> a.canonical() == canonical)
                .forEach(a -> counts.merge(a.status(), 1L, Long::sum));
        return counts;
    }
}
