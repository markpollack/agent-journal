package io.github.markpollack.journal.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records a pull request action during a run: a pull request created, updated, merged or closed,
 * with its number, URL, title and branches. Nothing in the library logs pull requests: create one
 * with {@link #created}, {@link #updated}, {@link #merged} or {@link #closed} and log it with
 * {@link io.github.markpollack.journal.Run#logEvent(JournalEvent)}. File storage writes it with
 * {@code @type} {@code "git_pr"}, and
 * {@link io.github.markpollack.journal.storage.JournalStorage#loadEvents(String, String)} reads it
 * back as a {@code GitPullRequestEvent}. It is one of the {@link GitEvent}s.
 *
 * <p>Unlike {@link GitCommitEvent}, it records no SHA or files: it names the pull request, its
 * source and target branches, and the {@link PullRequestAction}. Its {@link #type()} is
 * {@code "git_pull_request"}, which differs from the {@code @type} that file storage writes; a
 * JSON line with {@code "@type":"git_pull_request"} cannot be read back. The record is immutable.
 *
 * @param timestamp when the action happened
 * @param prNumber the pull request's number, as text, such as {@code "123"}
 * @param prUrl the pull request's URL
 * @param title the pull request's title
 * @param sourceBranch the branch with the changes (the head)
 * @param targetBranch the branch the changes go into (the base)
 * @param action what was done to the pull request
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

    /** What was done to a pull request. */
    public enum PullRequestAction {
        /** The pull request was opened. */
        CREATED,
        /** The pull request was changed, for example by new commits or a new title. */
        UPDATED,
        /** The pull request was merged. */
        MERGED,
        /** The pull request was closed without being merged. */
        CLOSED
    }

    /**
     * Returns {@code "git_pull_request"}. Unlike the other built-in events, this is not the
     * {@code @type} that file storage writes, which is {@code "git_pr"}.
     *
     * @return {@code "git_pull_request"}
     */
    @Override
    public String type() {
        return "git_pull_request";
    }

    /**
     * Creates an event for an opened pull request, with the current time.
     *
     * @param prNumber the pull request's number
     * @param prUrl the pull request's URL
     * @param title the pull request's title
     * @param sourceBranch the branch with the changes
     * @param targetBranch the branch the changes go into
     * @return the new event
     */
    public static GitPullRequestEvent created(String prNumber, String prUrl, String title,
                                               String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.CREATED);
    }

    /**
     * Creates an event for an updated pull request, with the current time.
     *
     * @param prNumber the pull request's number
     * @param prUrl the pull request's URL
     * @param title the pull request's title
     * @param sourceBranch the branch with the changes
     * @param targetBranch the branch the changes go into
     * @return the new event
     */
    public static GitPullRequestEvent updated(String prNumber, String prUrl, String title,
                                               String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.UPDATED);
    }

    /**
     * Creates an event for a merged pull request, with the current time.
     *
     * @param prNumber the pull request's number
     * @param prUrl the pull request's URL
     * @param title the pull request's title
     * @param sourceBranch the branch with the changes
     * @param targetBranch the branch the changes go into
     * @return the new event
     */
    public static GitPullRequestEvent merged(String prNumber, String prUrl, String title,
                                              String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.MERGED);
    }

    /**
     * Creates an event for a closed pull request, with the current time.
     *
     * @param prNumber the pull request's number
     * @param prUrl the pull request's URL
     * @param title the pull request's title
     * @param sourceBranch the branch with the changes
     * @param targetBranch the branch the changes go into
     * @return the new event
     */
    public static GitPullRequestEvent closed(String prNumber, String prUrl, String title,
                                              String sourceBranch, String targetBranch) {
        return new GitPullRequestEvent(Instant.now(), prNumber, prUrl, title,
                sourceBranch, targetBranch, PullRequestAction.CLOSED);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The keys are {@code pr_number}, {@code pr_url}, {@code title}, {@code source_branch},
     * {@code target_branch} and {@code action} (in lower case, such as {@code "merged"}).
     *
     * @throws NullPointerException if {@link #action()} is {@code null}
     */
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
