package io.github.markpollack.journal.tck.ocsf;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CLI version as an agent CLI reports it, and the version a versioned profile name carries, shared by the version-neutral
 * acquisition families ({@link ClaudeSdkFamily}, {@link CodexExecFamily}): dotted numbers with an optional pre-release or
 * build suffix. A well-formed version is recorded, never compared with a pinned one.
 */
final class CliVersions {
    private static final String VERSION = "[0-9]+(?:\\.[0-9]+)+(?:[-+][0-9A-Za-z.-]+)?";

    private CliVersions() { }

    static boolean wellFormed(String version) {
        return version != null && version.matches(VERSION);
    }

    /** The names {@code prefix + version + suffix}, as a pattern capturing the version. */
    static Pattern named(String prefix, String suffix) {
        return Pattern.compile(Pattern.quote(prefix) + "(" + VERSION + ")" + Pattern.quote(suffix));
    }

    /** The version {@code text} names under {@code pattern}, or null when it is not such a name. */
    static String version(Pattern pattern, String text) {
        if (text == null) { return null; }
        Matcher m = pattern.matcher(text);
        return m.matches() ? m.group(1) : null;
    }
}
