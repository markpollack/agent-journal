package io.github.markpollack.journal.tck.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence driver, gated by {@code -Dverify.pairs=<dir>}: for every {@code <dir>/<pair>/} holding the native
 * {@code stream.jsonl} and a persisted {@code journal/} storage root, runs {@link ClaudeStreamVerifier} and writes
 * {@code verification.json} beside them, then repeats the run on a copy of the package from which {@code raw/},
 * {@code analysis.jsonl} and {@code feedback.jsonl} are removed (the oracle stays where it is) and writes
 * {@code verification-core-only.json}. The two reports must agree check for check: the candidate reader never
 * opens the optional components, and this proves it on the real packages.
 *
 * <p>Reports hold counts, ids, paths, kinds and line numbers; never message content.
 */
class ClaudePairsVerificationDriverTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void verifyEveryPair(@TempDir Path work) throws Exception {
        String root = System.getProperty("verify.pairs");
        Assumptions.assumeTrue(root != null && !root.isBlank(), "no -Dverify.pairs");
        List<Path> pairs;
        try (Stream<Path> s = Files.list(Path.of(root))) {
            pairs = s.filter(p -> Files.isRegularFile(p.resolve("stream.jsonl")) && Files.isDirectory(p.resolve("journal")))
                    .sorted().toList();
        }
        assertThat(pairs).as("pair directories under " + root).isNotEmpty();
        for (Path pair : pairs) {
            ClaudeStreamOracle oracle = ClaudeStreamOracle.read(pair.resolve("stream.jsonl"));
            Path rootRun = CandidatePackage.findRootRun(pair.resolve("journal"));
            VerificationReport full = ClaudeStreamVerifier.verify(oracle, CandidatePackage.read(rootRun));
            JSON.writeValue(pair.resolve("verification.json").toFile(), full.toMap());

            Path copy = copyCore(pair.resolve("journal"), work.resolve(pair.getFileName().toString()));
            VerificationReport core = ClaudeStreamVerifier.verify(oracle, CandidatePackage.read(CandidatePackage.findRootRun(copy)));
            JSON.writeValue(pair.resolve("verification-core-only.json").toFile(), core.toMap());

            List<String> fullChecks = full.checks().stream().map(c -> c.property() + " " + c.owner() + " " + c.outcome()).toList();
            List<String> coreChecks = core.checks().stream().map(c -> c.property() + " " + c.owner() + " " + c.outcome()).toList();
            assertThat(coreChecks).as("core-only package judged identically").containsExactlyElementsOf(fullChecks);
            System.out.println("VERIFY " + pair.getFileName() + " " + full.summary());
            for (Check c : full.checks()) {
                if (c.outcome() != Check.Outcome.VERIFIED && c.outcome() != Check.Outcome.NOT_APPLICABLE) {
                    System.out.println("  " + c);
                }
            }
        }
    }

    /** Copies a storage root without {@code raw/} directories, {@code analysis.jsonl} and {@code feedback.jsonl}. */
    static Path copyCore(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path rel = from.relativize(p);
                boolean optional = false;
                for (Path part : rel) {
                    String name = part.toString();
                    if (name.equals("raw") || name.equals("analysis.jsonl") || name.equals("feedback.jsonl")) {
                        optional = true;
                    }
                }
                if (optional) {
                    continue;
                }
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                }
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        List<String> left = new ArrayList<>();
        try (Stream<Path> s = Files.walk(to)) {
            s.filter(p -> p.getFileName().toString().equals("raw") || p.getFileName().toString().endsWith("analysis.jsonl")
                    || p.getFileName().toString().endsWith("feedback.jsonl")).forEach(p -> left.add(p.toString()));
        }
        assertThat(left).as("optional components removed from the candidate copy").isEmpty();
        return to;
    }
}
