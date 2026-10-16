package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Copies Codex's own rollout files for a run into the run's raw directory, as the third level-0
 * artifact (see {@link RawArtifacts}): the root thread's rollout and, for sub-agents, each child
 * thread's. Each is stored under its original file name in {@code session/}, with its thread id
 * (and its parent's) in the manifest. A rollout whose path is not known is recorded as not
 * captured. Take the copy when the run ends.
 */
public final class CodexSessionFiles {

    private CodexSessionFiles() {
    }

    /**
     * Copies every rollout file of a run.
     *
     * @param raw the run's raw artifacts
     * @param rollouts the run's rollouts, as located by the caller
     * @return {@code true} if every rollout was copied; otherwise the manifest records which were not
     */
    public static boolean archive(RawArtifacts raw, CodexRollouts rollouts) {
        boolean ok = true;
        for (CodexRollout rollout : rollouts.rollouts()) {
            String role = rollout.parentThreadId() == null ? "rollout" : "child-rollout";
            if (rollout.sourcePath() == null) {
                raw.recordSessionFileNotCaptured(role, "the path of the rollout for thread " + rollout.threadId() + " is not known");
                ok = false;
                continue;
            }
            Map<String, String> refs = new LinkedHashMap<>();
            refs.put("threadId", rollout.threadId());
            if (rollout.parentThreadId() != null) {
                refs.put("parentThreadId", rollout.parentThreadId());
            }
            Path source = Path.of(rollout.sourcePath());
            ok &= raw.copySessionFile(source, source.getFileName().toString(), role, refs);
        }
        return ok;
    }

}
