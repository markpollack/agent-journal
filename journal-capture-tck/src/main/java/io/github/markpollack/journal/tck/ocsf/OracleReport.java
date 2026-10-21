package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;

import io.github.markpollack.journal.tck.verify.CheckReport;
import java.util.ArrayList;
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
public final class OracleReport extends CheckReport {

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

    @Override
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
        out.put("checks", checks.rows());
        out.put("summary", summary());
        out.put("notVerified", checks.notVerified());
        out.put("required", List.copyOf(required));
        out.put("missingRequired", missingRequired());
        return out;
    }

}
