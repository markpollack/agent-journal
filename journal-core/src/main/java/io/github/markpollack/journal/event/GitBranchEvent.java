package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records branch creation/switching by an agent.
 *
 * @param timestamp when the branch operation occurred
 * @param branchName the branch name
 * @param action the branch action (CREATED, CHECKED_OUT, DELETED)
 * @param fromRef source ref for CREATED (null for other actions)
 */
public record GitBranchEvent(
        Instant timestamp,
        String branchName,
        BranchAction action,
        String fromRef
) implements GitEvent {

    /** The type of branch operation. */
    public enum BranchAction {
        CREATED,
        CHECKED_OUT,
        DELETED
    }

    @Override
    public String type() {
        return "git_branch";
    }

    /**
     * Creates a branch creation event.
     *
     * @param branchName the new branch name
     * @param fromRef the source ref (branch or commit) the branch was created from
     * @return a new GitBranchEvent
     */
    public static GitBranchEvent created(String branchName, String fromRef) {
        return new GitBranchEvent(Instant.now(), branchName, BranchAction.CREATED, fromRef);
    }

    /**
     * Creates a branch checkout event.
     *
     * @param branchName the branch that was checked out
     * @return a new GitBranchEvent
     */
    public static GitBranchEvent checkedOut(String branchName) {
        return new GitBranchEvent(Instant.now(), branchName, BranchAction.CHECKED_OUT, null);
    }

    /**
     * Creates a branch deletion event.
     *
     * @param branchName the branch that was deleted
     * @return a new GitBranchEvent
     */
    public static GitBranchEvent deleted(String branchName) {
        return new GitBranchEvent(Instant.now(), branchName, BranchAction.DELETED, null);
    }

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
