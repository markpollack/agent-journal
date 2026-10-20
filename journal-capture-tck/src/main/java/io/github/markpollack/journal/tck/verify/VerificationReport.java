package io.github.markpollack.journal.tck.verify;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The verifier's result: the exact oracle and candidate it judged, the source denominator, what it covers and does
 * not, every check with its outcome, and per-category counts. There is no single pass flag: a category is
 * {@code noDefects} only when it has neither {@link Check.Outcome#DEFECTIVE} nor {@link Check.Outcome#UNRESOLVED}
 * checks, and its unsupported and representation-differing checks are counted beside it, never inside it.
 */
public final class VerificationReport {

    private final Map<String, Object> oracle = new LinkedHashMap<>();
    private final Map<String, Object> candidate = new LinkedHashMap<>();
    private final Map<String, Object> denominator = new LinkedHashMap<>();
    private final List<Check> checks = new ArrayList<>();

    void oracle(String key, Object value) {
        oracle.put(key, value);
    }

    void candidate(String key, Object value) {
        candidate.put(key, value);
    }

    void denominator(String owner, Map<String, Object> counts) {
        denominator.put(owner, counts);
    }

    Check add(Check.Category category, String property, String owner, Check.Outcome outcome, String detail) {
        Check check = new Check(category, property, owner, outcome, detail);
        checks.add(check);
        return check;
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

    /** The report as plain maps and lists, for writing as JSON. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> verifier = new LinkedHashMap<>();
        verifier.put("name", "journal-capture-tck " + ClaudeStreamVerifier.class.getSimpleName());
        verifier.put("contract", ClaudeStreamContract.ID);
        verifier.put("supportedSchemaVersion", ClaudeStreamContract.SUPPORTED_SCHEMA_VERSION);
        out.put("verifier", verifier);
        out.put("oracle", oracle);
        out.put("candidate", candidate);
        out.put("denominator", denominator);
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("supported", new TreeMap<>(ClaudeStreamContract.SUPPORTED_FAMILIES));
        coverage.put("unsupported", ClaudeStreamContract.UNSUPPORTED_FAMILIES);
        out.put("coverage", coverage);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Check c : checks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", c.category().name());
            row.put("property", c.property());
            row.put("owner", c.owner());
            row.put("outcome", c.outcome().name());
            row.put("detail", c.detail());
            rows.add(row);
        }
        out.put("checks", rows);
        out.put("summary", summary());
        List<String> defects = new ArrayList<>();
        for (Check c : checks) {
            if (c.outcome() == Check.Outcome.DEFECTIVE || c.outcome() == Check.Outcome.UNRESOLVED
                    || c.outcome() == Check.Outcome.REPRESENTATION_DIFFERS) {
                defects.add(c.toString());
            }
        }
        out.put("notVerified", defects);
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
