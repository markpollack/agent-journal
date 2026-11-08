package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.ClaudeSdkFamily;

import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration;
import java.io.IOException;
import java.nio.file.Path;

/** Opt-in qualifier for a sealed fresh-transcript-start generation ({@value FreshRetainedGeneration#KIND}) under an explicitly
 * named terminal profile: the frozen two-turn {@value FreshRetainedGeneration#PROFILE}, the ordinary
 * {@value FreshRetainedGeneration#ORDINARY_PROFILE} for any sealed turn count, or the native {@value FreshRetainedGeneration#NATIVE_PROFILE}
 * whose text-turn model admits the record classes and parent chains the real CLI writes.
 * It never launches, awaits or repairs anything; absence, emitted identity, file continuity and exhaustive
 * membership are checked from the caller's ordered observations and original bytes. Deterministic
 * qualification is fixture evidence only; native/provider admission remains separate. The frozen
 * {@link RetainedClaudeAcquisition} profile is unchanged.
 */
public final class FreshRetainedClaudeAcquisition {
    private FreshRetainedClaudeAcquisition() { }

    /** The original signature: qualifies under the frozen profile {@value FreshRetainedGeneration#PROFILE}, its default before the ordinary profile existed. */
    public static AcquisitionQualifier.Qualified qualify(Path generationManifest, String executionId, Path newDirectory) throws IOException {
        return qualify(generationManifest, executionId, newDirectory, ClaudeSdkFamily.FRESH_RETAINED_TERMINAL_PROFILE);
    }

    /** Copies originals, the observation journal and exact per-turn projections to a new directory. */
    public static AcquisitionQualifier.Qualified qualify(Path generationManifest, String executionId, Path newDirectory, String profile) throws IOException {
        return RetainedClaudeAcquisition.qualify(generationManifest, executionId, newDirectory, java.util.Objects.requireNonNull(profile, "retained profile")); // the validator refuses an unsupported profile
    }

    /** Packaged command; refuses before creating output and never invokes a provider. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3 && args.length != 4) throw new IllegalArgumentException("usage: FreshRetainedClaudeAcquisition <generation-manifest> <execution-id> <new-directory> [<retained-profile>, default " + ClaudeSdkFamily.FRESH_RETAINED_TERMINAL_PROFILE + "]");
        qualify(Path.of(args[0]), args[1], Path.of(args[2]), args.length == 4 ? args[3] : ClaudeSdkFamily.FRESH_RETAINED_TERMINAL_PROFILE);
    }
}
