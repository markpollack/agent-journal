package io.github.markpollack.journal.tck.verify;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * The checks of one report in the order they were added, with the lookups, the per-category summary and the plain-map
 * rows every report writes: shared by {@code OracleReport} (the OCSF oracle) and {@link VerificationReport} (the Claude
 * stream verifier), which differ only in what they say about their verifier and their denominator. There is no single
 * pass flag: a category is {@code noDefects} only when it has neither {@link Check.Outcome#DEFECTIVE} nor
 * {@link Check.Outcome#UNRESOLVED} checks; unsupported and representation-differing checks are counted beside it.
 */
public final class CheckLedger implements Iterable<Check> {

    private final List<Check> checks = new ArrayList<>();

    public Check add(Check.Category category, String property, String owner, Check.Outcome outcome, String detail) {
        return add(category, property, owner, outcome, detail, null);
    }

    /** A check that states how many native occurrences of its property's trigger it establishes for the owner. */
    public Check add(Check.Category category, String property, String owner, Check.Outcome outcome, String detail, Integer occurrences) {
        Check check = new Check(category, property, owner, outcome, detail, occurrences);
        checks.add(check);
        return check;
    }

    public void add(Check check) {
        checks.add(check);
    }

    public void replaceAll(UnaryOperator<Check> operator) {
        checks.replaceAll(operator);
    }

    @Override
    public Iterator<Check> iterator() {
        return List.copyOf(checks).iterator();
    }

    public List<Check> checks() {
        return List.copyOf(checks);
    }

    /** The check of a property for an owner, or {@code null}. */
    public Check check(String property, String owner) {
        for (Check c : checks) {
            if (c.property().equals(property) && c.owner().equals(owner)) {
                return c;
            }
        }
        return null;
    }

    public Check.Outcome outcome(String property, String owner) {
        Check c = check(property, owner);
        return c == null ? null : c.outcome();
    }

    public List<Check> withOutcome(Check.Outcome outcome) {
        List<Check> out = new ArrayList<>();
        for (Check c : checks) {
            if (c.outcome() == outcome) {
                out.add(c);
            }
        }
        return out;
    }

    public List<Check> defects() {
        return withOutcome(Check.Outcome.DEFECTIVE);
    }

    /** Per category: counts by outcome and whether the category has no defective or unresolved check. */
    public Map<String, Map<String, Object>> summary() {
        Map<Check.Category, Map<Check.Outcome, Integer>> counts = new EnumMap<>(Check.Category.class);
        for (Check.Category category : Check.Category.values()) {
            counts.put(category, new EnumMap<>(Check.Outcome.class));
        }
        for (Check c : checks) {
            counts.get(c.category()).merge(c.outcome(), 1, Integer::sum);
        }
        Map<String, Map<String, Object>> summary = new LinkedHashMap<>();
        for (Check.Category category : Check.Category.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Check.Outcome outcome : Check.Outcome.values()) {
                row.put(outcome.name(), counts.get(category).getOrDefault(outcome, 0));
            }
            row.put("noDefects", counts.get(category).getOrDefault(Check.Outcome.DEFECTIVE, 0) == 0
                    && counts.get(category).getOrDefault(Check.Outcome.UNRESOLVED, 0) == 0);
            summary.put(category.name(), row);
        }
        return summary;
    }

    /** Every check as a plain map (category, property, owner, outcome, detail and, when counted, occurrences), for writing as JSON. */
    public List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Check c : checks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.category().name());
            row.put("property", c.property());
            row.put("owner", c.owner());
            row.put("outcome", c.outcome().name());
            row.put("detail", c.detail());
            if (c.occurrences() != null) {
                row.put("occurrences", c.occurrences());
            }
            if (c.evidence() != null) {
                Check.Evidence e = c.evidence();
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("meaning", e.meaning());
                evidence.put("fact", e.fact());
                evidence.put("nativeAddresses", e.nativeAddresses());
                evidence.put("assertion", e.assertion());
                evidence.put("expectedKind", Json.kindOf(e.expected()));
                evidence.put("actualKind", Json.kindOf(e.actual()));
                evidence.put("expectedJson", e.expected() == null ? null : NeutralJson.write(e.expected()));
                evidence.put("actualJson", e.actual() == null ? null : NeutralJson.write(e.actual()));
                evidence.put("assertionValueJson", e.assertionValue() == null ? null : NeutralJson.write(e.assertionValue()));
                row.put("evidence", evidence);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The checks that are not verified (defective, unresolved or representation-differing), one line each. */
    public List<String> notVerified() {
        List<String> out = new ArrayList<>();
        for (Check c : checks) {
            if (c.outcome() == Check.Outcome.DEFECTIVE || c.outcome() == Check.Outcome.UNRESOLVED
                    || c.outcome() == Check.Outcome.REPRESENTATION_DIFFERS) {
                out.add(c.toString());
            }
        }
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Check c : checks) {
            sb.append(c).append('\n');
        }
        return sb.toString();
    }
}
