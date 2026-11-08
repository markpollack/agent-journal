package io.github.markpollack.journal.ocsf.writer;

import java.util.regex.Pattern;

/**
 * The Codex CLI exec acquisition family the writer admits: the original {@code codex exec --json} stdio with the run's
 * own rollout file, one format, one final-assembly profile and one contract at every CLI version. The CLI version is
 * recorded, not gated (Owner, 2026-11-08): any well-formed version is admitted and stays in the manifest, the run and
 * every event; each version names its own acquisition profile and profile id. The independent reader keeps its own rule.
 */
final class CodexExecFamily {
    /** The version-neutral terminal profile a qualified Codex one-shot is delivered under. */
    static final String TERMINAL_PROFILE = "client-terminal-codex-exec/1";
    /** The terminal profile earlier 0.166.0 one-shot recordings carry; it stays readable and admits 0.166.0 only. */
    static final String LEGACY_TERMINAL_PROFILE = "client-terminal-codex-0.166.0/1";
    static final String LEGACY_VERSION = "0.166.0";
    /** The bundled final-assembly profile every version is assembled with, stated under each version's own id. */
    static final String ASSEMBLY_RESOURCE = "codex-cli-0.166.0-exec-stdio-rollout";
    /** Version-neutral retained-thread profiles, each the contract of its versioned 0.166.0 predecessor. */
    static final String RETAINED_TERMINAL_PROFILE = "client-terminal-codex-exec-retained/1";
    static final String RETAINED_ORDINARY_TERMINAL_PROFILE = "client-terminal-codex-exec-retained/2";
    /** Each version-neutral terminal profile and the versioned profile whose contract it names. */
    private static final TerminalProfiles NAMES = new TerminalProfiles(java.util.Map.of(
            TERMINAL_PROFILE, LEGACY_TERMINAL_PROFILE,
            RETAINED_TERMINAL_PROFILE, "client-terminal-codex-retained-0.166.0/1",
            RETAINED_ORDINARY_TERMINAL_PROFILE, "client-terminal-codex-retained-0.166.0/2"), LEGACY_VERSION);
    private static final Pattern PROFILE = CliVersions.named("codex-cli-", "-exec-stdio-rollout");
    private static final Pattern PROFILE_ID = CliVersions.named("codex-cli/", "/exec-stdio+rollout");

    private CodexExecFamily() { }

    static boolean wellFormed(String version) {
        return CliVersions.wellFormed(version);
    }

    static String profile(String version) {
        return "codex-cli-" + version + "-exec-stdio-rollout";
    }

    static String profileId(String version) {
        return "codex-cli/" + version + "/exec-stdio+rollout";
    }

    /** The version an acquisition profile of this family names, or null when it is not one. */
    static String versionOfProfile(String profile) {
        return CliVersions.version(PROFILE, profile);
    }

    /** Whether {@code profileId} is a profile id of this family. */
    static boolean admits(String profileId) {
        return CliVersions.version(PROFILE_ID, profileId) != null;
    }

    /** The contract a terminal profile names: a version-neutral profile names its versioned predecessor's. */
    static String contract(String terminalProfile) {
        return NAMES.contract(terminalProfile);
    }


    /** Whether a recording delivered under {@code terminalProfile} admits {@code version}: any version under a neutral profile, 0.166.0 alone otherwise. */
    static boolean admittedUnder(String terminalProfile, String version) {
        return NAMES.admits(terminalProfile, version);
    }

}
