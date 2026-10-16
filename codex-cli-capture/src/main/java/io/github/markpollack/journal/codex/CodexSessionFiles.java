package io.github.markpollack.journal.codex;

import io.github.markpollack.journal.raw.RawArtifacts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Archives Codex's own rollout files for a run into the run's raw directory, as the third level-0
 * artifact (see {@link RawArtifacts}): the root thread's rollout and, for sub-agents, each child
 * thread's. They go into one archive, {@code session/codex-rollouts.zip}, each under its original
 * file name, with its thread id (and its parent's) in the manifest. A rollout whose path is not known is recorded as not
 * captured. Take the copy when the run ends.
 */
public final class CodexSessionFiles {

    private CodexSessionFiles() {
    }

    /**
     * Archives every rollout file of a run.
     *
     * @param raw the run's raw artifacts
     * @param rollouts the run's rollouts, as located by the caller
     * @return {@code true} if every rollout was archived; otherwise the manifest records which were not
     */
    public static boolean archive(RawArtifacts raw, CodexRollouts rollouts) {
        boolean ok = true;
        List<RawArtifacts.ZipSource> sources = new ArrayList<>();
        Map<String, String> archiveRefs = new LinkedHashMap<>();
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
            else {
                archiveRefs.put("rootThreadId", rollout.threadId());
            }
            Path source = Path.of(rollout.sourcePath());
            sources.add(new RawArtifacts.ZipSource(source, source.getFileName().toString(), refs));
        }
        if (sources.isEmpty()) {
            return false;
        }
        return raw.zipSessionFiles("codex-rollouts.zip", "codex-rollouts", archiveRefs, sources) && ok;
    }

}
