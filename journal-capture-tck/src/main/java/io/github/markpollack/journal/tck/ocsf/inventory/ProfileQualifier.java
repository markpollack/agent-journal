package io.github.markpollack.journal.tck.ocsf.inventory;

import java.io.IOException;
import java.nio.file.Path;

/** A registered qualifier: its two profile identifiers and the existing static qualification, which it runs unchanged. */
record ProfileQualifier(String acquisitionProfile, String terminalProfile, Qualification qualification) implements AcquisitionQualifier {

    /** One of the existing static {@code qualify(Path, Path)} entry points. */
    @FunctionalInterface
    interface Qualification {
        Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException;
    }

    @Override
    public Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException {
        return this.qualification.qualify(sealedManifest, newDirectory);
    }

    @Override
    public String toString() {
        return this.acquisitionProfile + " -> " + this.terminalProfile;
    }
}
