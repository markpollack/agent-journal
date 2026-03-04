/*
 * Copyright (c) 2026 Mark Pollack
 *
 * Licensed under the Business Source License 1.1 (BSL).
 * See the LICENSE file in the repository root for the full license text.
 */

package io.github.markpollack.journal.event;

/**
 * Sealed interface for git-related tracking events.
 *
 * <p>Essential for provenance chain: Task → Runs → Code Changes → PR → Merge
 *
 * <p>Use cases:
 * <ul>
 *   <li>Track patches/diffs generated during agent runs</li>
 *   <li>Record commits made by agents</li>
 *   <li>Link runs to branches and pull requests</li>
 * </ul>
 */
public sealed interface GitEvent extends JournalEvent permits
        GitPatchEvent,
        GitCommitEvent,
        GitBranchEvent,
        GitPullRequestEvent {
}
