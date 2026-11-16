package io.github.markpollack.journal.ocsf.writer;

import java.util.regex.Pattern;

/**
 * The Junie ACP one-shot acquisition family the writer admits: one fresh synchronous Junie ACP session, read from its
 * native session directory's {@code summary.json} and {@code events.jsonl}. Both profile names carry the Junie build
 * number. The version is gated: a version is assembled only with its own bundled session-primary assembly profile
 * ({@code junie-<version>-session-primary}), stated under the version's ACP profile id; a version without one is
 * refused. The independent reader keeps its own rule.
 */
final class JunieAcpFamily {
    /** The terminal profile a qualified Junie ACP one-shot is delivered under. */
    static final String TERMINAL_PROFILE = "client-terminal-junie-acp/1";
    private static final Pattern PROFILE = CliVersions.named("junie-", "-acp-session-files");
    private static final Pattern PROFILE_ID = CliVersions.named("junie/", "/acp+summary+session-events");

    private JunieAcpFamily() { }

    static String profile(String version) {
        return "junie-" + version + "-acp-session-files";
    }

    static String profileId(String version) {
        return "junie/" + version + "/acp+summary+session-events";
    }

    /** The version an acquisition profile of this family names, or null when it is not one. */
    static String versionOfProfile(String profile) {
        return CliVersions.version(PROFILE, profile);
    }

    /** Whether {@code profileId} is a profile id of this family. */
    static boolean admits(String profileId) {
        return CliVersions.version(PROFILE_ID, profileId) != null;
    }

    /** The bundled final-assembly profile a version is assembled with. */
    static String assemblyResource(String version) {
        return "junie-" + version + "-session-primary";
    }
}
