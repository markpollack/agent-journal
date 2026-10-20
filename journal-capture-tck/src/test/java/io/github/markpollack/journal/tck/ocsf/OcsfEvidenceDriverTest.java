package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence driver, gated by {@code -Docsf.evidence=<dir>}: for every {@code <dir>/<name>/} holding a self-describing
 * {@code package/} and its {@code native/} artifacts, runs the oracle, the analysis views and the measurements and
 * writes {@code oracle.json}, {@code views.json} and {@code measure.json} beside them. Reports hold counts, ids,
 * paths and kinds; never record content. Used on the private prototype packages held in the steward.
 */
class OcsfEvidenceDriverTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void verifyEveryPackage() throws Exception {
        String root = System.getProperty("ocsf.evidence");
        Assumptions.assumeTrue(root != null && !root.isBlank(), "no -Docsf.evidence");
        List<Path> cases;
        try (Stream<Path> s = Files.list(Path.of(root))) {
            cases = s.filter(p -> Files.isRegularFile(p.resolve("package/run.json")) && Files.isDirectory(p.resolve("native"))).sorted().toList();
        }
        assertThat(cases).as("cases under " + root).isNotEmpty();
        for (Path c : cases) {
            OcsfPackage pkg = PackageReader.open(c.resolve("package/events.jsonl"));
            OracleReport report = OcsfOracle.verify(pkg, c.resolve("native"));
            JSON.writeValue(c.resolve("oracle.json").toFile(), report.toMap());
            Files.writeString(c.resolve("views.json"), JSON.writeValueAsString(JSON.readTree(NeutralJson.write(AnalysisViews.all(pkg)))), StandardCharsets.UTF_8);
            StringBuilder measures = new StringBuilder("[");
            for (OcsfPackage core : pkg.all()) {
                measures.append(measures.length() > 1 ? "," : "").append(NeutralJson.write(PackageMeasure.measure(core)));
            }
            Files.writeString(c.resolve("measure.json"), JSON.writeValueAsString(JSON.readTree(measures.append("]").toString())), StandardCharsets.UTF_8);
            System.out.println("OCSF " + c.getFileName() + " " + report.summary());
            for (Check k : report.checks()) {
                if (k.outcome() != Check.Outcome.VERIFIED && k.outcome() != Check.Outcome.NOT_APPLICABLE) {
                    System.out.println("  " + k);
                }
            }
        }
    }
}
