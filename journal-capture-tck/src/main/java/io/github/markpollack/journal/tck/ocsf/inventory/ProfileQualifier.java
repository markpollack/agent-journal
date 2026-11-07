package io.github.markpollack.journal.tck.ocsf.inventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/** A registered qualifier: its two profile identifiers and the existing static qualification, which it runs unchanged. */
record ProfileQualifier(String acquisitionProfile, String terminalProfile, Qualification qualification) implements AcquisitionQualifier {

    /** One of the existing static {@code qualify(Path, Path)} entry points. */
    @FunctionalInterface
    interface Qualification {
        Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException;
    }

    /**
     * Runs the qualification, keeping the SPI's contract that a refusal is an {@link IllegalArgumentException}: a
     * numeric member that is not an exact integer, and a source the sealed manifest declares acquired but whose file is
     * absent from the sealed directory, are refusals of the acquisition, not arithmetic or I/O failures. An unreadable
     * sealed manifest and an unwritable new directory stay {@link IOException}s.
     */
    @Override
    public Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException {
        try {
            return this.qualification.qualify(sealedManifest, newDirectory);
        }
        catch (ArithmeticException e) {
            throw new IllegalArgumentException("a numeric member is not an exact integer (" + e.getMessage() + ")", e);
        }
        catch (NoSuchFileException e) {
            if (absentSource(e, sealedManifest, newDirectory)) {
                Path sealedDirectory = sealedManifest.toAbsolutePath().getParent();
                throw new IllegalArgumentException("declared source absent from the sealed acquisition: " + sealedDirectory.relativize(Path.of(e.getFile()).toAbsolutePath()), e);
            }
            throw e;
        }
    }

    /** Whether the missing file is a source inside a readable sealed acquisition, not the manifest or the new directory. */
    private static boolean absentSource(NoSuchFileException e, Path sealedManifest, Path newDirectory) {
        if (e.getFile() == null || !Files.isRegularFile(sealedManifest)) {
            return false;
        }
        Path missing = Path.of(e.getFile()).toAbsolutePath();
        return missing.startsWith(sealedManifest.toAbsolutePath().getParent()) && !missing.startsWith(newDirectory.toAbsolutePath());
    }

    @Override
    public String toString() {
        return this.acquisitionProfile + " -> " + this.terminalProfile;
    }
}
