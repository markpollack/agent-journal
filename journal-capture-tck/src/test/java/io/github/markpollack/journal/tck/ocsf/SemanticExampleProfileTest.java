package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewed semantic examples ({@code ocsf/mapping-catalog/examples/*.json}) against the oracle's own source
 * profile. The writer's mapper and the oracle's profile are written independently, yet they can share a wrong premise
 * and agree: the oracle then reports the property VERIFIED. A reviewed example, authored from pinned vendor sources,
 * is the third reading; this test compares the profile's facts with it at every pointer the profile states (usage
 * observations by event record), so a shared misreading is rejected on both sides ({@code SemanticExampleTest} checks
 * the writer).
 */
class SemanticExampleProfileTest {

    static final Path EXAMPLES = Path.of(SemanticExampleProfileTest.class.getResource("/ocsf/mapping-catalog/examples").getPath().replace("%20", " "));

    static List<Path> examples() throws IOException {
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    static NativeArtifact nativeArtifact(Json nat, Path work) throws IOException {
        if (nat.member("fixture") != null) {
            Path fixture = OcsfFixtures.copy(SchemaCatalog.text(nat, "fixture"), work.resolve(SchemaCatalog.text(nat, "fixture")));
            return NativeArtifact.read(OcsfFixtures.natives(fixture).resolve(SchemaCatalog.text(nat, "artifact")));
        }
        StringBuilder sb = new StringBuilder();
        for (Json line : nat.member("lines").elements()) {
            sb.append(line.text()).append('\n');
        }
        return NativeArtifact.ofBytes(Path.of(SchemaCatalog.text(nat, "artifact")), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void theOracleProfileStatesWhatEachReviewedExampleExpects(@TempDir Path work) throws IOException {
        List<String> problems = new ArrayList<>();
        int compared = 0;
        for (Path file : examples()) {
            Json ex = NeutralJson.readDocument(file);
            String id = SchemaCatalog.text(ex, "id");
            Json nat = ex.member("native");
            String profile = SchemaCatalog.text(nat, "profile");
            ExpectedFacts facts = SourceProfile.forName(profile).expected(nativeArtifact(nat, work.resolve(id)),
                    new Json.JObject(Map.of("artifact", new Json.JString(SchemaCatalog.text(nat, "artifact")), "profile", new Json.JString(profile))), null);
            for (Json exp : ex.member("expected").elements()) {
                int record = Event.integer(exp.member("record"));
                List<ExpectedFacts.Usage> usages = facts.usages.stream().filter(u -> u.eventRecordIndex() == record).toList();
                for (Map.Entry<String, Json> v : ((Json.JObject) exp.member("values")).members().entrySet()) {
                    String[] p = v.getKey().substring(1).split("/");
                    if (!"usage_observations".equals(p[0]) || p.length != 3) {
                        continue; // the profile states usage facts by observation; other pointers are the writer test's
                    }
                    int i = Integer.parseInt(p[1]);
                    if (i >= usages.size()) {
                        problems.add(id + " record " + record + ": the profile states " + usages.size() + " usage observation(s); the example expects index " + i);
                        continue;
                    }
                    ExpectedFacts.Usage u = usages.get(i);
                    Json stated = switch (p[2]) {
                        case "scope_id" -> new Json.JNumber(Integer.toString(u.scopeId()));
                        case "usage_status_id" -> new Json.JNumber(Integer.toString(u.statusId()));
                        default -> u.components().get(p[2]);
                    };
                    if (stated == null && !"data".equals(p[2]) && !"record_index".equals(p[2])) {
                        problems.add(id + " record " + record + " " + v.getKey() + ": the profile states nothing; the example expects " + v.getValue().scalarText());
                        continue;
                    }
                    if (stated == null) {
                        continue;
                    }
                    compared++;
                    if (!stated.scalarText().equals(v.getValue().scalarText())) {
                        problems.add(id + " record " + record + " " + v.getKey() + ": the profile states " + stated.scalarText() + ", the reviewed example " + v.getValue().scalarText());
                    }
                }
            }
        }
        assertThat(compared).as("profile facts compared with reviewed examples").isPositive();
        assertThat(problems).isEmpty();
    }

    @Test
    void theOracleAloneCannotRevealAMeaningTheMapperAndTheProfileShare(@TempDir Path work) {
        // the live MCP recording: the writer's package and the profile agree on the usage observation
        Path fixture = OcsfFixtures.copy("codex-live-0.160.1-mcp", work.resolve("mcp"));
        OracleReport report = OcsfFixtures.verify(fixture);
        assertThat(report.check("population.usage", "main/stream.jsonl").outcome()).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("usage.values", "main/stream.jsonl").outcome()).isEqualTo(Outcome.VERIFIED);
    }
}
