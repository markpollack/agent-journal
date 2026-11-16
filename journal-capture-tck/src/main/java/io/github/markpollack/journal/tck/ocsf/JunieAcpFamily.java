package io.github.markpollack.journal.tck.ocsf;

import java.util.regex.Pattern;

/**
 * The Junie ACP one-shot acquisition family: one fresh synchronous Junie ACP session, read from its native session
 * directory's {@code summary.json} and {@code events.jsonl} (docs/junie-acquisition.md). Both profile names carry the
 * Junie build number the CLI reports. Unlike the Claude and Codex families the version is gated: a version qualifies
 * only when source meaning bindings exist for both native sources at that version, and any other version is refused,
 * never mapped by guess. This is the TCK's own rule: the writer keeps an independent one.
 */
public final class JunieAcpFamily {
    /** The terminal binding profile, as its wire identifier, a qualified Junie ACP one-shot is delivered under. */
    public static final String TERMINAL_PROFILE = "client-terminal-junie-acp/1";
    /** The source profile of the session directory's {@code summary.json}. */
    public static final String SUMMARY_PROFILE = "junie_summary";
    /** The source profile of the session directory's {@code events.jsonl}. */
    public static final String SESSION_PROFILE = "junie_session_events";
    private static final Pattern PROFILE = CliVersions.named("junie-", "-acp-session-files");
    private static final Pattern PROFILE_ID = CliVersions.named("junie/", "/acp+summary+session-events");

    private JunieAcpFamily() { }

    /** Whether {@code version} is a well-formed Junie build number, such as {@code 3813.7}. */
    public static boolean wellFormed(String version) {
        return CliVersions.wellFormed(version);
    }

    /** The acquisition profile a sealed manifest of {@code version} declares. */
    public static String profile(String version) {
        return "junie-" + version + "-acp-session-files";
    }

    /** The profile id a sealed manifest of {@code version} declares. */
    public static String profileId(String version) {
        return "junie/" + version + "/acp+summary+session-events";
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
}
