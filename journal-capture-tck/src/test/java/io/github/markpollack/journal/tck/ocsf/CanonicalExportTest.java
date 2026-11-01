package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class CanonicalExportTest {
    @Test
    void copiedFileExportsExactCanonicalContentAndItsOwnedSupportingEvidence(@TempDir Path work) throws IOException {
        Path fixture = OcsfFixtures.copy("claude-live-2.1.304-mcp-pair", work);
        Path alone = work.resolve("events.jsonl");
        Files.copy(fixture.resolve("package/events.jsonl"), alone);
        OcsfPackage pkg = PackageReader.open(alone);
        StringWriter writer = new StringWriter();
        CanonicalExport.write(pkg, writer);
        List<Json> rows = writer.toString().lines().map(NeutralJson::parse).toList();
        assertThat(rows).hasSize(pkg.canonical().size() + 1);
        List<Json> events = rows.stream().filter(r -> r.member("event") != null).toList();
        for (int i = 0; i < events.size(); i++) {
            Json exported = events.get(i).member("event");
            Event original = pkg.canonical().get(i);
            for (String field : List.of("arguments", "tool_result", "content_blocks", "usage_observations", "cost_observations", "source_ref", "assembly")) {
                Json expected = PackageReader.selfContained(pkg, original).member(field);
                assertThat(NeutralJson.write(exported.member(field))).isEqualTo(NeutralJson.write(expected));
            }
            assertThat(original.assemblyRole()).isIn(1, 3);
            assertThat(events.get(i).member("source_observations").elements()).hasSize(original.elements("assembly", "observation_refs").size());
        }
        assertThat(events.stream().filter(r -> r.path("event", "arguments") != null).toList()).isNotEmpty();
        assertThat(events.stream().filter(r -> r.path("event", "tool_result") != null).toList()).isNotEmpty();
        assertThat(events.stream().filter(r -> r.path("event", "usage_observations") != null).toList()).isNotEmpty();
        assertThat(events.stream().filter(r -> r.path("event", "cost_observations") != null).toList()).isNotEmpty();
    }

    @Test
    void childCoresRemainSeparateTrajectories(@TempDir Path work) throws IOException {
        Path fixture = OcsfFixtures.copy("claude-child-2.1.299", work);
        OcsfPackage pkg = PackageReader.open(fixture.resolve("package/events.jsonl"));
        assertThat(pkg.children).isNotEmpty();
        StringWriter writer = new StringWriter();
        CanonicalExport.write(pkg, writer);
        List<Json> rows = writer.toString().lines().map(NeutralJson::parse).toList();
        assertThat(rows.stream().filter(r -> r.member("event") == null).toList()).hasSize(pkg.all().size());
        assertThat(rows.stream().map(r -> r.member("run_uid").text()).distinct().count()).isEqualTo(pkg.all().size());
    }
}
