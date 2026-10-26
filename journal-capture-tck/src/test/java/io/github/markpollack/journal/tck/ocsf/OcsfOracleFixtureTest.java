package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.SPAWN;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controls: the committed fixtures are judged with no defect, and the report shows the populations it examined, so
 * a mutation test's detection is measured against a known-good baseline rather than against an empty population.
 */
class OcsfOracleFixtureTest {

    @Test
    void syntheticCodexRolloutIsJudgedWithoutDefectAndWithNonEmptyPopulations(@TempDir Path work) {
        OracleReport report = verify(copy(CODEX, work));
        assertThat(report.defects()).isEmpty();
        assertThat(report.withOutcome(Outcome.UNRESOLVED)).hasSize(3).allMatch(c -> c.property().equals("source.meaning"));
        String owner = "main/rollout.jsonl";
        assertThat(report.check("population.requests", owner).detail()).isEqualTo("3 expected, 3 observed");
        assertThat(report.check("population.results", owner).detail()).isEqualTo("3 expected, 3 observed");
        assertThat(report.check("population.responses", owner).detail()).isEqualTo("6 expected, 6 observed");
        assertThat(report.check("population.prompts", owner).detail()).isEqualTo("5 expected, 5 observed");
        assertThat(report.check("population.usage", owner).detail()).isEqualTo("9 expected, 9 observed");
        assertThat(report.check("population.contexts", owner).detail()).isEqualTo("7 expected, 7 observed");
        assertThat(report.check("population.operations", owner).detail()).isEqualTo("4 expected, 4 observed");
        assertThat(report.check("population.turn_completed", owner).detail()).isEqualTo("2 expected, 2 observed");
        assertThat(report.outcome("population.cost", owner)).as("the rollout reports no cost; none is claimed").isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(report.outcome("result.owner", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("operation.values", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("usage.values", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("whole_record.coverage", owner).detail()).startsWith("39 owned record(s)");
        assertThat(report.outcome("whole_record.structure_value", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.representation", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.bytes", owner)).as("the one opaque line is byte-equal").isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("whole_record.bytes", owner).detail()).isEqualTo("1 opaque record(s) byte-equal");
        assertThat(report.outcome("reconstruction.structure_value", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("reconstruction.bytes", owner)).as("observed byte equality on compact native JSON; not promised for parsed records").isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("reconstruction.bytes", owner).detail()).contains("38 parsed, observed");
        assertThat(report.outcome("time.basis", owner)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("event.order", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("package.readable", "main")).isEqualTo(Outcome.VERIFIED);
        Map<String, Map<String, Object>> summary = report.summary();
        assertThat(summary.get("NORMALIZATION").get("noDefects")).as("unknown synthetic meanings remain non-passing").isEqualTo(false);
        assertThat(summary.get("PRESERVATION").get("noDefects")).isEqualTo(true);
    }

    @Test
    void sanitizedClaudeStreamWithChildCoreIsJudgedPerCoreWithoutDefect(@TempDir Path work) {
        OracleReport report = verify(copy(CHILD, work));
        assertThat(report.defects()).isEmpty();
        String main = "main/stream.jsonl";
        String child = "child:" + SPAWN + "/stream.jsonl";
        assertThat(report.check("whole_record.coverage", main).detail()).startsWith("24 owned record(s)");
        assertThat(report.check("whole_record.coverage", child).detail()).startsWith("3 owned record(s)");
        assertThat(report.check("population.requests", main).detail()).isEqualTo("2 expected, 2 observed");
        assertThat(report.check("population.requests", child).detail()).isEqualTo("1 expected, 1 observed");
        assertThat(report.check("population.usage", main).detail()).isEqualTo("13 expected, 13 observed");
        assertThat(report.check("population.usage", child).detail()).isEqualTo("1 expected, 1 observed");
        assertThat(report.check("population.cost", main).detail()).as("the run total and the one model's running cost (ACCT-1)").isEqualTo("2 expected, 2 observed");
        assertThat(report.outcome("population.cost", child)).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(report.outcome("cost.values", main)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("decision.values", main)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("decision.values", child)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("event.order", "child:" + SPAWN)).isEqualTo(Outcome.VERIFIED);
        for (Check c : report.checks()) {
            assertThat(c.outcome()).as(c.toString()).isNotEqualTo(Outcome.UNRESOLVED);
        }
    }
}
