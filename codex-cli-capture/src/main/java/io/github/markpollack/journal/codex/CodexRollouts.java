package io.github.markpollack.journal.codex;

import java.util.List;

/**
 * The root rollout of one Codex execution together with the rollouts of its sub-agent threads.
 *
 * @param rollouts the rollouts, root and children in any order
 */
public record CodexRollouts(List<CodexRollout> rollouts) {

    public CodexRollouts {
        rollouts = rollouts == null ? List.of() : List.copyOf(rollouts);
    }

    /**
     * Returns the root rollout: the single rollout without a {@code parentThreadId}.
     *
     * @throws IllegalArgumentException if no rollout or more than one rollout has no parent
     */
    public CodexRollout root() {
        List<CodexRollout> roots = rollouts.stream().filter(r -> r.parentThreadId() == null).toList();
        if (roots.size() != 1) {
            throw new IllegalArgumentException("CodexRollouts needs exactly one rollout without parentThreadId "
                    + "(the root); found " + roots.size() + " of " + rollouts.size());
        }
        return roots.get(0);
    }

    /** Returns the rollouts that have a {@code parentThreadId}, in the supplied order. */
    public List<CodexRollout> children() {
        return rollouts.stream().filter(r -> r.parentThreadId() != null).toList();
    }
}
