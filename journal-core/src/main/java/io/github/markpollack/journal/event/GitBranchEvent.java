package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a Git branch operation during a run: a branch created, and from which ref, checked out,
 * or deleted. Nothing in the library logs branch operations: create one with
 * {@link #created(String, String)}, {@link #checkedOut(String)} or {@link #deleted(String)} and
 * log it with {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}. File storage
 * writes it with {@code @type} {@code "git_branch"}, and
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)} reads it
 * back as a {@code GitBranchEvent}. It is one of the {@link GitEvent}s.
 *
 * <p>Unlike {@link GitCommitEvent}, it has no SHA and no file list: it names the branch and the
 * {@link BranchAction}. The factory methods set {@code fromRef} only for {@code CREATED}. The
 * record is immutable.
 *
 * @param timestamp when the operation happened
 * @param branchName the branch's name
 * @param action what was done to the branch
 * @param fromRef the branch or commit a new branch was created from, or {@code null} for other
 *        actions
 */
public record GitBranchEvent(
        Instant timestamp,
        String branchName,
        BranchAction action,
        String fromRef
) implements GitEvent {

    /** What was done to a branch. */
    public enum BranchAction {
        /** The branch was created. */
        CREATED,
        /** The branch was checked out. */
        CHECKED_OUT,
        /** The branch was deleted. */
        DELETED
    }

    @Override
    public String type() {
        return "git_branch";
    }

    /**
     * Creates an event for a new branch, with the current time.
     *
     * @param branchName the new branch's name
     * @param fromRef the branch or commit it was created from
     * @return the new event
     */
    public static GitBranchEvent created(String branchName, String fromRef) {
        return new GitBranchEvent(Instant.now(), branchName, BranchAction.CREATED, fromRef);
    }

    /**
     * Creates an event for a branch checkout, with the current time.
     *
     * @param branchName the branch that was checked out
     * @return the new event
     */
    public static GitBranchEvent checkedOut(String branchName) {
        return new GitBranchEvent(Instant.now(), branchName, BranchAction.CHECKED_OUT, null);
    }

    /**
     * Creates an event for a deleted branch, with the current time.
     *
     * @param branchName the branch that was deleted
     * @return the new event
     */
    public static GitBranchEvent deleted(String branchName) {
        return new GitBranchEvent(Instant.now(), branchName, BranchAction.DELETED, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code branch} and {@code action} (in lower case, such as
     * {@code "checked_out"}), then {@code from_ref} when it is set.
     *
     * @throws NullPointerException if {@link #action()} is {@code null}
     */
    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("branch", branchName);
        map.put("action", action.name().toLowerCase());
        if (fromRef != null) {
            map.put("from_ref", fromRef);
        }
        return map;
    }
}
