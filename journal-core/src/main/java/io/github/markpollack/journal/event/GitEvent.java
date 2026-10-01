package io.github.markpollack.journal.event;

/**
 * A Git operation that happened during a run: a patch of code changes ({@link GitPatchEvent}), a
 * commit ({@link GitCommitEvent}), a branch created, checked out or deleted
 * ({@link GitBranchEvent}), or a pull request created, updated, merged or closed
 * ({@link GitPullRequestEvent}). Use them to link a run to the code it changed. Nothing in the
 * library logs Git events: create one with its type's factory methods and log it with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}. They are read back with the
 * run's other events; {@link io.github.markpollack.journal.eval.EvalSubjectSources} skips them,
 * because they are not agent behaviour to judge.
 *
 * <p>The interface is sealed, so these four records are the only Git events, and it adds no
 * methods to {@link JournalEvent}. The factory methods of all four use the current time. Their
 * {@link #toMap()} maps have no {@code type} or {@code timestamp} key, unlike those of the other
 * built-in events.
 */
public sealed interface GitEvent extends JournalEvent permits
        GitPatchEvent,
        GitCommitEvent,
        GitBranchEvent,
        GitPullRequestEvent {
}
