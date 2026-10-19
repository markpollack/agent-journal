package io.github.markpollack.journal.tck.venn;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** Random leaves, fixed seeds: comparing a set with itself loses nothing; removing one value loses exactly one. */
class VennPropertyTest {

    private static List<Leaf> random(Random random, int count) {
        List<Leaf> leaves = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = "p" + random.nextInt(12) + (random.nextBoolean() ? "[]" : "");
            if (random.nextInt(4) == 0) {
                leaves.add(new Leaf("k", path, Flattener.normalize(new java.math.BigDecimal(100_000 + i * 7919L)), "f:" + i, false));
            }
            else {
                leaves.add(new Leaf("k", path, "unique-value-" + i + "-" + Long.toHexString(random.nextLong()), "f:" + i, false));
            }
        }
        return leaves;
    }

    @Test
    void anyRandomSetComparedWithItselfLosesNothing() {
        for (long seed = 1; seed <= 50; seed++) {
            List<Leaf> leaves = random(new Random(seed), 200);

            assertThat(Venn.compare(leaves, leaves).count(Landing.NEITHER)).as("seed %d", seed).isZero();
        }
    }

    @Test
    void removingOneRandomValueLosesExactlyOne() {
        for (long seed = 1; seed <= 50; seed++) {
            Random random = new Random(seed);
            List<Leaf> leaves = random(random, 200);
            List<Leaf> without = new ArrayList<>(leaves);
            without.remove(random.nextInt(without.size()));

            VennReport r = Venn.compare(leaves, without);

            assertThat(r.count(Landing.NEITHER)).as("seed %d", seed).isEqualTo(1);
            assertThat(r.count(Landing.LANDED)).as("seed %d", seed).isEqualTo(199);
        }
    }

    @Test
    void shufflingTheCanonicalSideChangesNothingInTheCounts() {
        List<Leaf> leaves = random(new Random(7), 300);
        List<Leaf> shuffled = new ArrayList<>(leaves);
        java.util.Collections.shuffle(shuffled, new Random(11));

        VennReport a = Venn.compare(leaves, leaves);
        VennReport b = Venn.compare(leaves, shuffled);

        assertThat(b.count(Landing.LANDED)).isEqualTo(a.count(Landing.LANDED));
        assertThat(b.count(Landing.NEITHER)).isEqualTo(a.count(Landing.NEITHER));
    }
}
