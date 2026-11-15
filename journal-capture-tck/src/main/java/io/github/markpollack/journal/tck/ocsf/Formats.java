package io.github.markpollack.journal.tck.ocsf;

/**
 * The identifiers of the package format this reader implements and the writer writes. The reader states them
 * independently of the writer and refuses every other package kind, layout and extension version.
 */
public final class Formats {
    /** The package kind. */
    public static final String PACKAGE_KIND = "agent-journal/ocsf-package";
    /** The standalone event-file layout. */
    public static final String LAYOUT = "1.0.0";
    /** The standalone layout with a terminal binding (a sealed archive delivered by Client). */
    public static final String TERMINAL_LAYOUT = "1.1.0";
    /** The {@code agentic_cli} extension version. */
    public static final String EXTENSION = "1.0.0";
    /** The final-assembly mapping contract. */
    public static final String FINAL_ASSEMBLY = "agent-journal/final-assembly/1.0.0";
    /** The Codex exec assembly profile version. */
    public static final String CODEX_PROFILE = "1.0.0";

    private Formats() {
    }
}
