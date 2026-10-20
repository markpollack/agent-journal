package io.github.markpollack.journal.tck.ocsf;

/**
 * One explicit reader outcome. A reader never silently drops data: a refusal still returns the raw lines, a defect
 * names what did not match, and a warning names what it could not confirm.
 *
 * @param severity how the reader classifies the outcome
 * @param code a stable code such as {@code PIN_MISMATCH} or {@code UNKNOWN_ATTRIBUTE}
 * @param detail what was found, with counts, paths and pins; never record content
 */
public record Diagnostic(Severity severity, String code, String detail) {

    public enum Severity {
        /** The reader does not implement this interpretation; events are available raw only. */
        REFUSED,
        /** Saved data contradicts its own pins or definitions. */
        DEFECT,
        /** Something the reader could not confirm or that is version-scoped. */
        WARNING,
        INFO
    }

    @Override
    public String toString() {
        return severity + " " + code + ": " + detail;
    }
}
