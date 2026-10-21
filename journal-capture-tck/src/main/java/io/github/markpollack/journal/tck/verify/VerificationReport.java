package io.github.markpollack.journal.tck.verify;

import java.util.ArrayList;
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
public final class VerificationReport extends CheckReport {

    void oracle(String key, Object value) {
        oracle.put(key, value);
    }

    void candidate(String key, Object value) {
        candidate.put(key, value);
    }

    void denominator(String owner, Map<String, Object> counts) {
        denominator.put(owner, counts);
    }

    /** The report as plain maps and lists, for writing as JSON. */
    @Override
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
        out.put("checks", checks.rows());
        out.put("summary", summary());
        out.put("notVerified", checks.notVerified());
        return out;
    }

}
