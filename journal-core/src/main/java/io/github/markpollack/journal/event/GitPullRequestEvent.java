package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records pull request creation/update by an agent.
 *
 * @param timestamp when the PR action occurred
 * @param prNumber the PR number (e.g., "123")
 * @param prUrl the full PR URL
 * @param title the PR title
 * @param sourceBranch the source branch (head)
 * @param targetBranch the target branch (base)
 * @param action the PR action (CREATED, UPDATED, MERGED, CLOSED)
 */
public record GitPullRequestEvent(
        Instant timestamp,
        String prNumber,
        String prUrl,
        String title,
        String sourceBranch,
        String targetBranch,
        PullRequestAction action
) implements GitEvent {

    /** The type of pull request action. */
    public enum PullRequestAction {
        CREATED,
        UPDATED,
        MERGED,
        CLOSED
    }

    @Override
    public String type() {
        return "git_pull_request";
    }

    /**
     * Creates a PR created event.
     *
     * @param prNumber the PR number
     * @param prUrl the full PR URL
     * @param title the PR title
     * @param sourceBranch the source branch
     * @param targetBranch the target branch
     * @return a new GitPullRequestEvent
     */
    public static GitPullRequestEvent created(String prNumber, String prUrl, String title,
                                               String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.CREATED);
    }

    /**
     * Creates a PR updated event.
     *
     * @param prNumber the PR number
     * @param prUrl the full PR URL
     * @param title the PR title
     * @param sourceBranch the source branch
     * @param targetBranch the target branch
     * @return a new GitPullRequestEvent
     */
    public static GitPullRequestEvent updated(String prNumber, String prUrl, String title,
                                               String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.UPDATED);
    }

    /**
     * Creates a PR merged event.
     *
     * @param prNumber the PR number
     * @param prUrl the full PR URL
     * @param title the PR title
     * @param sourceBranch the source branch
     * @param targetBranch the target branch
     * @return a new GitPullRequestEvent
     */
    public static GitPullRequestEvent merged(String prNumber, String prUrl, String title,
                                              String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.MERGED);
    }

    /**
     * Creates a PR closed event.
     *
     * @param prNumber the PR number
     * @param prUrl the full PR URL
     * @param title the PR title
     * @param sourceBranch the source branch
     * @param targetBranch the target branch
     * @return a new GitPullRequestEvent
     */
    public static GitPullRequestEvent closed(String prNumber, String prUrl, String title,
                                              String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.CLOSED);
    }

    @Override
    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("pr_number", prNumber);
        map.put("pr_url", prUrl);
        map.put("title", title);
        map.put("source_branch", sourceBranch);
        map.put("target_branch", targetBranch);
        map.put("action", action.name().toLowerCase());
        return map;
    }
}
