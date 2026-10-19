package io.github.markpollack.journal.tck.venn;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The result of comparing a vendor's own records with the journal's record. Counts and paths only.
 *
 * @param paths one entry per native path, most missing first
 * @param unexplained journal paths holding a matchable value that no native value accounted for, with counts
 *        (placeholders, invented values, allocations: each needs a rule or an explanation)
 */
public record VennReport(List<PathResult> paths, Map<String, Integer> unexplained) {

    public Optional<PathResult> path(String kind, String path) {
        return paths.stream().filter(p -> p.kind().equals(kind) && p.path().equals(path)).findFirst();
    }

    /** The native paths with at least one value found nowhere, most missing first. */
    public List<PathResult> gaps() {
        return paths.stream().filter(p -> p.neither() > 0).toList();
    }

    public int count(Landing landing) {
        return paths.stream().mapToInt(p -> switch (landing) {
            case LANDED -> p.landed();
            case KEPT_WHOLE -> p.keptWhole();
            case LOW_INFORMATION -> p.lowInformation();
            case NEITHER -> p.neither();
        }).sum();
    }
}
