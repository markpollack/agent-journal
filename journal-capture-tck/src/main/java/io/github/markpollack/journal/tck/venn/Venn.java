package io.github.markpollack.journal.tck.venn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the values in a vendor's own records (the native side) with the values in the journal's record
 * (the canonical side). A native value lands if the same value is found on the canonical side, whatever
 * the path; the paths it was found at are the mapping, read from the data and not from parser code.
 *
 * <p>Matching consumes: each canonical value, or each stretch of a canonical string, satisfies at most one
 * native value, so one stored value cannot cover two native ones. Strings of at least
 * {@value #MIN_STRING_LENGTH} characters match whole or as part of a longer canonical string (text that the
 * journal joined still matches). A number matches exactly once normalized, but only if it has three or more
 * digits or a fraction. A typed field is preferred to a {@code vendor_event}.
 *
 * <p>A short string, a small integer or a boolean proves nothing by value alone. Such a value is looked for
 * only inside the canonical record that the rest of its native record landed in (records are the values that
 * share a {@code location}; a native record is aligned to the canonical record that received most of its
 * matchable values). Found there, it lands; not found there, it is {@link Landing#NEITHER}, to be explained by a
 * rule or an allow-list entry. A native record with no matchable value cannot be aligned, so its small values stay
 * {@link Landing#LOW_INFORMATION}.
 */
public final class Venn {

    /** Strings shorter than this are too common to match by value. */
    public static final int MIN_STRING_LENGTH = 8;

    private Venn() {
    }

    public static VennReport compare(List<Leaf> nativeLeaves, List<Leaf> canonicalLeaves) {
        Pool typed = new Pool();
        Pool kept = new Pool();
        for (Leaf leaf : canonicalLeaves) {
            (leaf.keptWhole() ? kept : typed).add(leaf);
        }

        Map<String, Counts> byPath = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> alignment = new HashMap<>();
        List<Leaf> deferred = new ArrayList<>();
        for (Leaf leaf : nativeLeaves) {
            Counts counts = counts(byPath, leaf);
            if (!matchable(leaf.value())) {
                deferred.add(leaf);
                continue;
            }
            Leaf hit = typed.consume(leaf.value());
            boolean wasKept = false;
            if (hit == null) {
                hit = kept.consume(leaf.value());
                wasKept = hit != null;
            }
            if (hit == null) {
                counts.neither++;
                continue;
            }
            if (wasKept) {
                counts.kept++;
            }
            else {
                counts.landed++;
            }
            counts.landedAt.merge(hit.kind() + " " + hit.path(), 1, Integer::sum);
            alignment.computeIfAbsent(leaf.location(), k -> new LinkedHashMap<>()).merge(hit.location(), 1, Integer::sum);
        }

        // second pass: small values are looked for inside the canonical record their native record landed in
        for (Leaf leaf : deferred) {
            Counts counts = counts(byPath, leaf);
            String target = best(alignment.get(leaf.location()));
            if (target == null) {
                counts.low++;
                continue;
            }
            Leaf hit = typed.consumeInRecord(target, leaf.value());
            boolean wasKept = false;
            if (hit == null) {
                hit = kept.consumeInRecord(target, leaf.value());
                wasKept = hit != null;
            }
            if (hit == null) {
                counts.neither++;
                continue;
            }
            if (wasKept) {
                counts.kept++;
            }
            else {
                counts.landed++;
            }
            counts.landedAt.merge(hit.kind() + " " + hit.path(), 1, Integer::sum);
        }

        List<PathResult> paths = new ArrayList<>();
        for (Counts c : byPath.values()) {
            paths.add(new PathResult(c.kind, c.path, c.landed, c.kept, c.low, c.neither,
                    Map.copyOf(c.landedAt)));
        }
        paths.sort(Comparator.comparingInt(PathResult::neither).reversed()
                .thenComparing(Comparator.comparingInt(PathResult::total).reversed())
                .thenComparing(PathResult::kind).thenComparing(PathResult::path));

        Map<String, Integer> unexplained = new LinkedHashMap<>();
        for (Leaf leaf : typed.unconsumed()) {
            if (matchable(leaf.value())) {
                unexplained.merge(leaf.kind() + " " + leaf.path(), 1, Integer::sum);
            }
        }
        return new VennReport(List.copyOf(paths), Map.copyOf(unexplained));
    }

    private static Counts counts(Map<String, Counts> byPath, Leaf leaf) {
        return byPath.computeIfAbsent(leaf.kind() + "\u0000" + leaf.path(), k -> new Counts(leaf.kind(), leaf.path()));
    }

    /** The key with the highest count; the first inserted wins a tie. */
    private static String best(Map<String, Integer> counts) {
        if (counts == null) {
            return null;
        }
        String best = null;
        int most = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > most) {
                best = e.getKey();
                most = e.getValue();
            }
        }
        return best;
    }

    static boolean matchable(Object value) {
        if (value instanceof String s) {
            return s.length() >= MIN_STRING_LENGTH;
        }
        if (value instanceof BigDecimal n) {
            return n.scale() > 0 || n.abs().compareTo(BigDecimal.valueOf(100)) >= 0;
        }
        return false;
    }

    private static final class Counts {
        final String kind;
        final String path;
        int landed;
        int kept;
        int low;
        int neither;
        final Map<String, Integer> landedAt = new HashMap<>();

        Counts(String kind, String path) {
            this.kind = kind;
            this.path = path;
        }
    }

    /** Canonical values that can each be used once; strings can be used piece by piece. */
    private static final class Pool {
        private final List<Leaf> all = new ArrayList<>();
        private final Map<Object, List<Leaf>> exact = new HashMap<>();
        private final List<Leaf> strings = new ArrayList<>();
        private final IdentityHashMap<Leaf, List<int[]>> used = new IdentityHashMap<>();
        private final IdentityHashMap<Leaf, Boolean> touched = new IdentityHashMap<>();
        private final Map<String, List<Leaf>> byRecord = new HashMap<>();

        void add(Leaf leaf) {
            all.add(leaf);
            byRecord.computeIfAbsent(leaf.location(), k -> new ArrayList<>()).add(leaf);
            if (!matchable(leaf.value())) {
                return;
            }
            exact.computeIfAbsent(leaf.value(), k -> new ArrayList<>()).add(leaf);
            if (leaf.value() instanceof String) {
                strings.add(leaf);
            }
        }

        Leaf consume(Object value) {
            List<Leaf> same = exact.get(value);
            if (same != null) {
                for (Leaf candidate : same) {
                    if (!touched.containsKey(candidate)) {
                        touched.put(candidate, Boolean.TRUE);
                        if (candidate.value() instanceof String s) {
                            used.computeIfAbsent(candidate, k -> new ArrayList<>()).add(new int[] { 0, s.length() });
                        }
                        return candidate;
                    }
                }
            }
            if (value instanceof String needle) {
                for (Leaf candidate : strings) {
                    String haystack = (String) candidate.value();
                    if (haystack.length() < needle.length()) {
                        continue;
                    }
                    int from = 0;
                    while (true) {
                        int at = haystack.indexOf(needle, from);
                        if (at < 0) {
                            break;
                        }
                        if (free(candidate, at, at + needle.length())) {
                            used.computeIfAbsent(candidate, k -> new ArrayList<>()).add(new int[] { at, at + needle.length() });
                            touched.put(candidate, Boolean.TRUE);
                            return candidate;
                        }
                        from = at + 1;
                    }
                }
            }
            return null;
        }

        /** Takes an unused leaf of the given canonical record that holds exactly this value. */
        Leaf consumeInRecord(String record, Object value) {
            for (Leaf candidate : byRecord.getOrDefault(record, List.of())) {
                if (!touched.containsKey(candidate) && candidate.value().equals(value)) {
                    touched.put(candidate, Boolean.TRUE);
                    return candidate;
                }
            }
            return null;
        }

        private boolean free(Leaf candidate, int start, int end) {
            for (int[] range : used.getOrDefault(candidate, List.of())) {
                if (start < range[1] && range[0] < end) {
                    return false;
                }
            }
            return true;
        }

        List<Leaf> unconsumed() {
            List<Leaf> out = new ArrayList<>();
            for (Leaf leaf : all) {
                if (!touched.containsKey(leaf)) {
                    out.add(leaf);
                }
            }
            return out;
        }
    }
}
