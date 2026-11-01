package io.github.markpollack.journal.tck.ocsf;

import java.io.IOException;
import java.nio.file.Path;

/** Service boundary for combined verification, keeping the reader independent of assembly tooling. */
public interface AcquisitionVerifier {
    /** Judges acquired native bytes and assembly expectations without consulting producer code. */
    OracleReport verify(OcsfPackage pkg, Path directory, Path acquisition, Path assembly) throws IOException;
}
