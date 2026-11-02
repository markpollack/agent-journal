package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;

/** A positively detected immutable artifact or inventory integrity defect. Retrying cannot repair it.
 * Other I/O failures remain ambiguous; this type does not imply that publication never happened.
 */
public final class TerminalIntegrityException extends IOException {
    private static final long serialVersionUID = 1L;

    /** @param message the verified integrity defect, without changing an accepted Client outcome */
    public TerminalIntegrityException(String message) { super(message); }
}
