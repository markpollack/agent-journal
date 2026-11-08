package io.github.markpollack.journal.tck.ocsf;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Claude Code SDK acquisition family: the original SDK stdio stream with the run's own native session file, one
 * format read the same way at every CLI version. The CLI version is recorded, not gated (Owner, 2026-11-08): any
 * well-formed version is admitted, the route, format, identity, completeness and integrity checks decide acceptance,
 * and each version names its own acquisition profile and profile id so the actual version stays in every record.
 * This is the TCK's own rule: the writer keeps an independent one.
 */
public final class ClaudeSdkFamily {
    /** The version-neutral terminal binding profile, as its wire identifier, a qualified Claude SDK one-shot is delivered under. */
    public static final String TERMINAL_PROFILE = "client-terminal-claude-sdk/1";
    /** The terminal binding profile earlier 2.1.316 one-shot recordings carry; it stays readable and admits {@link #LEGACY_VERSION} only. */
    public static final String LEGACY_TERMINAL_PROFILE = "client-terminal-claude-2.1.316/1";
    /** The only version the versioned 2.1.316 terminal profiles admit. */
    public static final String LEGACY_VERSION = "2.1.316";
    /** A Claude Code version as the CLI reports it: dotted numbers with an optional pre-release or build suffix. */
    private static final String VERSION = "[0-9]+(?:\\.[0-9]+)+(?:[-+][0-9A-Za-z.-]+)?";
    private static final Pattern PROFILE = Pattern.compile("claude-code-(" + VERSION + ")-sdk-stdio-session-file");
    private static final Pattern PROFILE_ID = Pattern.compile("claude-code/(" + VERSION + ")/sdk-stdio\\+session-file");

    private ClaudeSdkFamily() { }

    /** Whether {@code version} is a well-formed Claude Code version. */
    public static boolean wellFormed(String version) {
        return version != null && version.matches(VERSION);
    }

    /** The acquisition profile a sealed manifest of {@code version} declares. */
    public static String profile(String version) {
        return "claude-code-" + version + "-sdk-stdio-session-file";
    }

    /** The profile id a sealed manifest of {@code version} declares. */
    public static String profileId(String version) {
        return "claude-code/" + version + "/sdk-stdio+session-file";
    }

    /** The version an acquisition profile of this family names, or null when it is not one. */
    public static String versionOfProfile(String profile) {
        return group(PROFILE, profile);
    }

    /** The version a profile id of this family names, or null when it is not one. */
    public static String versionOfProfileId(String profileId) {
        return group(PROFILE_ID, profileId);
    }

    /** Whether {@code profileId} is a profile id of this family. */
    public static boolean admits(String profileId) {
        return versionOfProfileId(profileId) != null;
    }

    /** Whether a recording delivered under {@code terminalProfile} admits {@code version}: any version under the neutral profile, 2.1.316 alone otherwise. */
    public static boolean admittedUnder(String terminalProfile, String version) {
        return TERMINAL_PROFILE.equals(terminalProfile) ? wellFormed(version) : LEGACY_VERSION.equals(version);
    }

    private static String group(Pattern pattern, String text) {
        if (text == null) { return null; }
        Matcher m = pattern.matcher(text);
        return m.matches() ? m.group(1) : null;
    }
}
