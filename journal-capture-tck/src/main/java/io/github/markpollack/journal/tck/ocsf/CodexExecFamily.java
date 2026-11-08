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

    /** Whether a recording delivered under {@code terminalProfile} admits {@code version}: any version under the neutral profile, 0.166.0 alone otherwise. */
    public static boolean admittedUnder(String terminalProfile, String version) {
        return TERMINAL_PROFILE.equals(terminalProfile) ? wellFormed(version) : LEGACY_VERSION.equals(version);
    }

}
