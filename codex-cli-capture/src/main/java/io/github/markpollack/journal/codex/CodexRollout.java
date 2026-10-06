package io.github.markpollack.journal.codex;

import java.util.List;

/**
 * The verbatim lines of one Codex rollout file, tagged with the thread they belong to.
 *
 * <p>Codex writes one rollout file per thread. A sub-agent is its own thread with its own file; the
 * caller that located the file supplies its {@code threadId} (the id in the file name) and, for a
 * child, the {@code parent_thread_id} of its first {@code session_meta}. The root rollout has no
 * {@code parentThreadId}. {@code sourcePath} is kept as evidence only; nothing reads the file again.
 *
 * @param threadId the thread id this file belongs to
 * @param parentThreadId the parent thread id, or {@code null} for the root rollout
 * @param sourcePath the path the lines were read from, or {@code null} when unknown
 * @param lines the JSONL lines, in file order
 */
public record CodexRollout(String threadId, String parentThreadId, String sourcePath, List<String> lines) {

    public CodexRollout {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
