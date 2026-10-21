package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The OCSF oracle's result: what it judged (native artifacts with hashes, the candidate package and its reader
 * diagnostics), the expected population per artifact and family, every {@link Check} with its outcome, and
 * per-category counts. There is no single pass flag; defects, unresolved, unsupported and representation differences
 * are counted beside each other, never inside a pass.
 */
public final class OracleReport {

    private final Map<String, Object> oracle = new LinkedHashMap<>();
    private final Map<String, Object> candidate = new LinkedHashMap<>();
    private final Map<String, Object> denominator = new LinkedHashMap<>();
    private final List<Check> checks = new ArrayList<>();
    private final List<String> required = new ArrayList<>();

    /** Registers a property the oracle is obliged to report for an owner; acceptance refuses a report that lacks it. */
    void require(String owner, String... properties) {
        for (String p : properties) {
            String key = p + " [" + owner + "]";
            if (!required.contains(key)) {
                required.add(key);
            }
        }
    }

    /** The required {@code property [owner]} keys that no check in this report carries. */
    public List<String> missingRequired() {
        List<String> present = new ArrayList<>();
        for (Check c : checks) {
            present.add(c.property() + " [" + c.owner() + "]");
        }
        List<String> missing = new ArrayList<>();
        for (String r : required) {
            if (!present.contains(r)) {
                missing.add(r);
            }
        }
        return missing;
    }

    public List<String> required() {
        return List.copyOf(required);
    }

    /** A copy of this report without one check (the required registry is kept): for testing that acceptance notices. */
    OracleReport without(String property, String owner) {
        return filtered(c -> !(c.property().equals(property) && c.owner().equals(owner)));
    }

    /**
     * A copy of this report keeping only the checks {@code keep} accepts (the required registry is kept): for
     * testing that a consumer of the report notices a check that is absent or altered.
     */
    public OracleReport filtered(Predicate<Check> keep) {
        OracleReport copy = new OracleReport();
        copy.oracle.putAll(oracle);
        copy.candidate.putAll(candidate);
        copy.denominator.putAll(denominator);
        copy.required.addAll(required);
        for (Check c : checks) {
            if (keep.test(c)) {
                copy.checks.add(c);
            }
        }
        return copy;
    }

    /** A copy of this report with one check replaced by {@code replacement} where {@code match} accepts it. */
    public OracleReport replaced(Predicate<Check> match, UnaryOperator<Check> replacement) {
        OracleReport copy = filtered(c -> true);
        copy.checks.replaceAll(c -> match.test(c) ? replacement.apply(c) : c);
        return copy;
    }

    void oracle(String key, Object value) {
        oracle.put(key, value);
    }

    void candidate(String key, Object value) {
        candidate.put(key, value);
    }

    void denominator(String key, Object value) {
        denominator.put(key, value);
    }

    Check add(Check.Category category, String property, String owner, Check.Outcome outcome, String detail) {
        Check check = new Check(category, property, owner, outcome, detail);
        checks.add(check);
        return check;
    }

    public List<Check> checks() {
        return List.copyOf(checks);
    }

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

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> verifier = new LinkedHashMap<>();
        verifier.put("name", "journal-capture-tck " + OcsfOracle.class.getSimpleName());
        verifier.put("readerContract", PackageReader.READER_CONTRACT + " " + PackageReader.READER_CONTRACT_VERSION);
        verifier.put("semanticOperations", SemanticOperation.VERSION);
        out.put("verifier", verifier);
        out.put("oracle", oracle);
        out.put("candidate", candidate);
        out.put("denominator", denominator);
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
        List<String> notVerified = new ArrayList<>();
        for (Check c : checks) {
            if (c.outcome() == Check.Outcome.DEFECTIVE || c.outcome() == Check.Outcome.UNRESOLVED
                    || c.outcome() == Check.Outcome.REPRESENTATION_DIFFERS) {
                notVerified.add(c.toString());
            }
        }
        out.put("notVerified", notVerified);
        out.put("required", List.copyOf(required));
        out.put("missingRequired", missingRequired());
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
