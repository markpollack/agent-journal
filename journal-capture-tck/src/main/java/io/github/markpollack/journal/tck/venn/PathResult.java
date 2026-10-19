package io.github.markpollack.journal.tck.venn;

import java.util.Map;

/**
 * The outcome for one native path: how many of its values landed, where the matched ones were found, and how
 * many did not.
 *
 * @param kind the native record kind
 * @param path the native path
 * @param landed values found as typed fields
 * @param keptWhole values found only inside a {@code vendor_event}
 * @param lowInformation values too common to match by value
 * @param neither values found nowhere
 * @param landedAt for matched values, the journal paths they were found at ({@code kind path}) with counts: the mapping,
 *        discovered from the data
 */
public record PathResult(String kind, String path, int landed, int keptWhole, int lowInformation, int neither,
        Map<String, Integer> landedAt) {

    public int total() {
        return landed + keptWhole + lowInformation + neither;
    }
}
