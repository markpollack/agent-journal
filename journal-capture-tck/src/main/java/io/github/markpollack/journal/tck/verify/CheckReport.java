package io.github.markpollack.journal.tck.verify;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What every verifier's report has in common: the exact oracle and candidate it judged, the source denominator, and
 * its checks in a {@link CheckLedger} with the lookups and the per-category summary. A concrete report such as
 * {@code OracleReport} (the OCSF oracle) adds what it says about its verifier, its coverage or its required
 * properties, and writes its own {@link #toMap()} order.
 */
public abstract class CheckReport {

    protected final Map<String, Object> oracle = new LinkedHashMap<>();
    protected final Map<String, Object> candidate = new LinkedHashMap<>();
    protected final Map<String, Object> denominator = new LinkedHashMap<>();
    protected final CheckLedger checks = new CheckLedger();

    public Check add(Check.Category category, String property, String owner, Check.Outcome outcome, String detail) {
        return checks.add(category, property, owner, outcome, detail);
    }

    /** A check that counts the native occurrences it establishes ({@link Check#occurrences()}). */
    public Check add(Check.Category category, String property, String owner, Check.Outcome outcome, String detail, Integer occurrences) {
        return checks.add(category, property, owner, outcome, detail, occurrences);
    }

    public List<Check> checks() {
        return checks.checks();
    }

    /** The check of a property for an owner, or {@code null}. */
    public Check check(String property, String owner) {
        return checks.check(property, owner);
    }

    public Check.Outcome outcome(String property, String owner) {
        return checks.outcome(property, owner);
    }

    public List<Check> withOutcome(Check.Outcome outcome) {
        return checks.withOutcome(outcome);
    }

    public List<Check> defects() {
        return checks.defects();
    }

    /** Per category: counts by outcome and whether the category has no defective or unresolved check. */
    public Map<String, Map<String, Object>> summary() {
        return checks.summary();
    }

    /** The report as plain maps and lists, for writing as JSON. */
    public abstract Map<String, Object> toMap();

    @Override
    public String toString() {
        return checks.toString();
    }
}
