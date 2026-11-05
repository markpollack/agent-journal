package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.FreshRetainedGeneration;
import java.io.IOException;
import java.nio.file.Path;

/** Opt-in qualifier for a sealed fresh-transcript-start generation ({@value FreshRetainedGeneration#KIND}) under an explicitly
 * named terminal profile: the frozen two-turn {@value FreshRetainedGeneration#PROFILE} or the ordinary
 * {@value FreshRetainedGeneration#ORDINARY_PROFILE} for any sealed turn count.
 * It never launches, awaits or repairs anything; absence, emitted identity, file continuity and exhaustive
 * membership are checked from the caller's ordered observations and original bytes. Deterministic
 * qualification is fixture evidence only; native/provider admission remains separate. The frozen
 * {@link RetainedClaudeAcquisition} profile is unchanged.
 */
public final class FreshRetainedClaudeAcquisition {
    private FreshRetainedClaudeAcquisition() { }

    /** Copies originals, the observation journal and exact per-turn projections to a new directory. */
    public static ClaudeAcquisition.Qualified qualify(Path generationManifest, String executionId, Path newDirectory, String profile) throws IOException {
        return RetainedClaudeAcquisition.qualify(generationManifest, executionId, newDirectory, java.util.Objects.requireNonNull(profile, "retained profile")); // the validator refuses an unsupported profile
    }

    /** Packaged command; refuses before creating output and never invokes a provider. */
    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("usage: FreshRetainedClaudeAcquisition <generation-manifest> <execution-id> <new-directory> <retained-profile>");
        qualify(Path.of(args[0]), args[1], Path.of(args[2]), args[3]);
    }
}
