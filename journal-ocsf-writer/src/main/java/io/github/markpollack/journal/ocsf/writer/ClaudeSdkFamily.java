package io.github.markpollack.journal.ocsf.writer;

import java.util.regex.Pattern;

/**
 * The Claude Code SDK acquisition family the writer admits: the original SDK stdio stream with the run's own native
 * session file, one format, one final-assembly profile and one contract at every CLI version. The CLI version is
 * recorded, not gated (Owner, 2026-11-08): any well-formed version is admitted and stays in the manifest, the run and
 * every event; each version names its own acquisition profile and profile id. The independent reader keeps its own rule.
 */
final class ClaudeSdkFamily {
    /** The version-neutral terminal profile a qualified Claude SDK one-shot is delivered under. */
    static final String TERMINAL_PROFILE = "client-terminal-claude-sdk/1";
    /** The terminal profile earlier 2.1.316 one-shot recordings carry; it stays readable and admits 2.1.316 only. */
    static final String LEGACY_TERMINAL_PROFILE = "client-terminal-claude-2.1.316/1";
    static final String LEGACY_VERSION = "2.1.316";
    /** The bundled final-assembly profile every version is assembled with, stated under each version's own id. */
    static final String ASSEMBLY_RESOURCE = "claude-code-2.1.316-sdk-stdio-session-file";
    private static final Pattern PROFILE = CliVersions.named("claude-code-", "-sdk-stdio-session-file");

    private ClaudeSdkFamily() { }

    static boolean wellFormed(String version) {
        return CliVersions.wellFormed(version);
    }

    static String profile(String version) {
        return "claude-code-" + version + "-sdk-stdio-session-file";
    }

    static String profileId(String version) {
        return "claude-code/" + version + "/sdk-stdio+session-file";
    }

    /** The version an acquisition profile of this family names, or null when it is not one. */
    static String versionOfProfile(String profile) {
        return CliVersions.version(PROFILE, profile);
    }

    /** Whether a recording delivered under {@code terminalProfile} admits {@code version}: any version under the neutral profile, 2.1.316 alone otherwise. */
    static boolean admittedUnder(String terminalProfile, String version) {
        return TERMINAL_PROFILE.equals(terminalProfile) ? wellFormed(version) : LEGACY_VERSION.equals(version);
    }
}
