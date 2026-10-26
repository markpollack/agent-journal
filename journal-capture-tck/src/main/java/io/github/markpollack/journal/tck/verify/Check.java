package io.github.markpollack.journal.tck.verify;

/**
 * The outcome of one property for one owner. The outcomes are deliberately distinct: a known defect is reported as
 * {@link Outcome#DEFECTIVE} and never folded into a pass; a property the verifier cannot judge is
 * {@link Outcome#UNSUPPORTED} or {@link Outcome#UNRESOLVED} and stays visible.
 *
 * @param category which of the four separate results the property belongs to
 * @param property a stable name, {@code family.property}
 * @param owner {@code main}, or {@code child:<spawn tool-use id>}
 * @param outcome the outcome
 * @param detail counts, ids, paths, line numbers and kinds; never message content
 * @param occurrences how many native occurrences of the property's trigger the result establishes for its owner, for
 *        the checks that count them (populations, values, children, opaque bytes); {@code null} when the check does not
 *        count occurrences. The coverage summary compares it with the native population it derives independently.
 */
public record Check(Category category, String property, String owner, Outcome outcome, String detail, Integer occurrences, Evidence evidence) {

    /** Exact inputs and outputs of an executed comparison; null JSON means missing, not JSON null. */
    public record Evidence(String meaning, String fact, java.util.List<String> nativeAddresses,
            String assertion, Json expected, Json actual, Json assertionValue) {
        public Evidence(String meaning, String fact, java.util.List<String> nativeAddresses, String assertion, Json expected, Json actual) {
            this(meaning, fact, nativeAddresses, assertion, expected, actual, actual);
        }
        public Evidence {
            nativeAddresses = java.util.List.copyOf(nativeAddresses);
        }
    }

    public Check(Category category, String property, String owner, Outcome outcome, String detail, Integer occurrences) {
        this(category, property, owner, outcome, detail, occurrences, null);
    }

    /** A check that does not count occurrences. */
    public Check(Category category, String property, String owner, Outcome outcome, String detail) {
        this(category, property, owner, outcome, detail, null);
    }

    /** The four results DESIGN keeps apart. */
    public enum Category {
        /** Capture completeness against the declared source inventory. */
        COMPLETENESS,
        /** Whole-record retention at the right occurrence and owner. */
        PRESERVATION,
        /** Normalized values: owner, scope, order, cardinality, exact values. */
        NORMALIZATION,
        /** Generated values explained as copies or declared transformations of source values. */
        EXPLANATION
    }

    public enum Outcome {
        /** The property holds on every occurrence. */
        VERIFIED,
        /**
         * Every value is equal in value, but at least one number is spelled differently from the source ({@code 1}
         * as {@code 1.0}). Not a value loss, not a pass of representation fidelity; reported on its own.
         */
        REPRESENTATION_DIFFERS,
        /** The property fails on at least one occurrence. */
        DEFECTIVE,
        /** The verifier has no rule for this property or the capture declares the data out of Level 1. */
        UNSUPPORTED,
        /** The verifier could not resolve an identity, a reference or a representation needed to judge. */
        UNRESOLVED,
        /** The fixture has no occurrence of the property. */
        NOT_APPLICABLE
    }

    @Override
    public String toString() {
        return category + " " + property + " [" + owner + "] " + outcome + (detail == null ? "" : ": " + detail);
    }
}
