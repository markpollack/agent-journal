package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The independent qualifier for one sealed acquisition profile, selected by the profile string a producer wrote into its
 * sealed manifest. A producer never interprets the manifest itself: it looks the qualifier up with
 * {@link #forProfile(String)}, calls {@link #qualify(Path, Path)}, binds the returned sources, and delivers the binding under
 * {@link #terminalProfile()}.
 *
 * <p>Implementations stay producer-independent: they read the original bytes with the TCK's neutral tokenizer and never call
 * the writer, a capture parser or an SDK. {@link #terminalProfile()} is therefore a wire identifier, not a reference to a
 * writer constant.
 *
 * <p>Registered qualifiers are fixed, not discovered. Each one qualifies a fresh one-shot execution only:
 * <ul>
 * <li>{@link ClaudeAcquisition#qualifier(String)}: the acquisition profile of any Claude Code version on the SDK route
 * ({@link ClaudeSdkFamily}; the version is recorded, not gated) to {@value ClaudeSdkFamily#TERMINAL_PROFILE};</li>
 * <li>{@link CodexAcquisition#QUALIFIER}: {@value CodexAcquisition#PROFILE} to {@value CodexAcquisition#TERMINAL_PROFILE}.</li>
 * </ul>
 * Selection uses the acquisition profile alone. A Codex 0.166.0 turn manifest from a retained thread may declare the same
 * acquisition profile as a fresh turn, and the one-shot qualifier refuses its resumed history; select a retained qualifier
 * before reaching this lookup for a retained generation.
 */
public interface AcquisitionQualifier {

    /** The acquisition profile a sealed manifest declares in its {@code profile} member; it selects this qualifier. */
    String acquisitionProfile();

    /** The terminal binding profile, as its wire identifier, under which this qualifier's output is delivered. */
    String terminalProfile();

    /**
     * Validates the sealed manifest and the original sources it declares, then writes new immutable derivative inputs into a
     * directory that must not exist. The caller's files are never changed.
     *
     * @throws IllegalArgumentException the qualifier refused the acquisition; the message is its verdict, and the new
     *         directory was not created
     * @throws IOException a sealed input could not be read, or the new directory exists or could not be written
     */
    Qualified qualify(Path sealedManifest, Path newDirectory) throws IOException;

    /**
     * The registered qualifier for an acquisition profile, or empty when none exists for it.
     *
     * @throws NullPointerException if {@code acquisitionProfile} is null
     */
    static Optional<AcquisitionQualifier> forProfile(String acquisitionProfile) {
        Objects.requireNonNull(acquisitionProfile, "acquisitionProfile");
        String claude = ClaudeSdkFamily.versionOfProfile(acquisitionProfile);
        if (claude != null) { return Optional.of(ClaudeAcquisition.qualifier(claude)); }
        return java.util.stream.Stream.of(CodexAcquisition.QUALIFIER)
                .filter(qualifier -> qualifier.acquisitionProfile().equals(acquisitionProfile)).findFirst();
    }

    /**
     * New immutable derivative inputs: the qualified manifest for final assembly and the assembly expectation for the
     * independent packaged verifier. Every qualifier in this package returns this one record.
     */
    record Qualified(Path manifest, Path assemblyExpectation) { }
}
