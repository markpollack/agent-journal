package io.github.markpollack.journal.tck.ocsf;

import java.util.regex.Pattern;

/**
 * The Codex CLI exec acquisition family: the original {@code codex exec --json} stdio with the run's own rollout file,
 * one format read the same way at every CLI version. The CLI version is recorded, not gated (Owner, 2026-11-08): any
 * well-formed version is admitted, the route, format, identity, completeness and integrity checks decide acceptance,
 * and each version names its own acquisition profile and profile id. This is the TCK's own rule: the writer keeps an
 * independent one.
 */
public final class CodexExecFamily {
    /** The version-neutral terminal binding profile, as its wire identifier, a qualified Codex one-shot is delivered under. */
    public static final String TERMINAL_PROFILE = "client-terminal-codex-exec/1";
    /** The terminal binding profile earlier 0.166.0 one-shot recordings carry; it stays readable and admits {@link #LEGACY_VERSION} only. */
    public static final String LEGACY_TERMINAL_PROFILE = "client-terminal-codex-0.166.0/1";
    /** The only version the versioned 0.166.0 terminal profiles admit. */
    public static final String LEGACY_VERSION = "0.166.0";
    /** Version-neutral retained-thread profiles, each the contract of its versioned 0.166.0 predecessor. */
    public static final String RETAINED_TERMINAL_PROFILE = "client-terminal-codex-exec-retained/1";
    public static final String RETAINED_ORDINARY_TERMINAL_PROFILE = "client-terminal-codex-exec-retained/2";
    /** Each version-neutral terminal profile and the versioned profile whose contract it names. */
    private static final TerminalProfiles NAMES = new TerminalProfiles(java.util.Map.of(
            TERMINAL_PROFILE, LEGACY_TERMINAL_PROFILE,
            RETAINED_TERMINAL_PROFILE, "client-terminal-codex-retained-0.166.0/1",
            RETAINED_ORDINARY_TERMINAL_PROFILE, "client-terminal-codex-retained-0.166.0/2"), LEGACY_VERSION);
    private static final Pattern PROFILE = CliVersions.named("codex-cli-", "-exec-stdio-rollout");
    private static final Pattern PROFILE_ID = CliVersions.named("codex-cli/", "/exec-stdio+rollout");

    private CodexExecFamily() { }

    /** Whether {@code version} is a well-formed Codex CLI version. */
    public static boolean wellFormed(String version) {
        return CliVersions.wellFormed(version);
    }

    /** The acquisition profile a sealed manifest of {@code version} declares. */
    public static String profile(String version) {
        return "codex-cli-" + version + "-exec-stdio-rollout";
    }

    /** The profile id a sealed manifest of {@code version} declares. */
    public static String profileId(String version) {
        return "codex-cli/" + version + "/exec-stdio+rollout";
    }

    /** The version an acquisition profile of this family names, or null when it is not one. */
    public static String versionOfProfile(String profile) {
        return CliVersions.version(PROFILE, profile);
    }

    /** The version a profile id of this family names, or null when it is not one. */
    public static String versionOfProfileId(String profileId) {
        return CliVersions.version(PROFILE_ID, profileId);
    }

    /** Whether {@code profileId} is a profile id of this family. */
    public static boolean admits(String profileId) {
        return versionOfProfileId(profileId) != null;
    }

    /** The contract a terminal profile names: a version-neutral profile names its versioned predecessor's. */
    public static String contract(String terminalProfile) {
        return NAMES.contract(terminalProfile);
    }


    /** Whether a recording delivered under {@code terminalProfile} admits {@code version}: any version under a neutral profile, 0.166.0 alone otherwise. */
    public static boolean admittedUnder(String terminalProfile, String version) {
        return NAMES.admits(terminalProfile, version);
    }

}
