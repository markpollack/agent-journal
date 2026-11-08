package io.github.markpollack.journal.ocsf.writer;

import java.util.Map;

/**
 * A family's terminal profile names: each version-neutral name with the versioned predecessor whose contract (the same
 * checks) it names, and the one CLI version the versioned names admit. Under a version-neutral name the CLI version is
 * recorded, not gated (Owner, 2026-11-08).
 */
record TerminalProfiles(Map<String, String> predecessors, String legacyVersion) {

    /** The contract {@code profile} names, as the versioned profile's wire identifier. */
    String contract(String profile) {
        return profile == null ? null : predecessors.getOrDefault(profile, profile);
    }

    boolean neutral(String profile) {
        return profile != null && predecessors.containsKey(profile);
    }

    /** Any well-formed version under a version-neutral name; the legacy version alone under a versioned one. */
    boolean admits(String profile, String version) {
        return neutral(profile) ? CliVersions.wellFormed(version) : legacyVersion.equals(version);
    }
}
