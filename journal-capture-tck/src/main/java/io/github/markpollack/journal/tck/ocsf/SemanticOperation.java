package io.github.markpollack.journal.tck.ocsf;

/**
 * The named deterministic Java operations a semantic contract may declare. A declaration names one of these and the
 * schema paths it reads; {@link AnalysisViews} implements them. There is no expression language: a new rule is a new
 * named operation with tests, not a new string in a package.
 */
public enum SemanticOperation {
    /** One user submission despite repeated representations; identical text in a new turn is a new submission. */
    SUBMISSION_IDENTITY,
    /** One model response despite several source records, under the declared update semantics per contract. */
    RESPONSE_ASSEMBLY,
    /** Applicable final usage per (scope, owner, model, source); repeats counted once; nothing summed across groups. */
    FINAL_USAGE_SELECTION,
    /** Candidate count and evidence method reported separately; derived uniqueness is never a reported parent. */
    ASSOCIATION_EVIDENCE,
    /** Tool exchanges grouped by the owner id of their own record; equal content never pairs results. */
    RESULT_OWNERSHIP,
    /** Settings, injected context and accounting records listed at their reported scope; nothing merged. */
    SETTINGS_AND_CONTEXT,
    /** In an assembled file, the canonical events (no assembly, role 1 or 3) every other operation reads; supporting observations (role 2) are evidence only. */
    CANONICAL_PROJECTION;

    /** The version of these operations' semantics, pinned by packages separately from schema and contracts. */
    public static final String VERSION = "0.3";

    public static boolean known(String name) {
        for (SemanticOperation op : values()) {
            if (op.name().equals(name)) {
                return true;
            }
        }
        return false;
    }
}
