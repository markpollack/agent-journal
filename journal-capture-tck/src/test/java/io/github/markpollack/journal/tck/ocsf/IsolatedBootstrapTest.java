package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reader bootstrap from a core copied alone into an empty directory: no native artifacts, no Level 0, no optional
 * files, no schema cache, no registry. Starting at {@code events.jsonl}, the reader discovers the manifest, verifies
 * the pins, enumerates the saved model, answers the agreed queries and reconstructs each source in the scope the
 * core promises. (The network-disabled run of the same steps is the {@code bwrap --unshare-net} evidence step.)
 */
class IsolatedBootstrapTest {

    @Test
    void aCopiedCoreWithItsChildBootstrapsWithoutAnythingElse(@TempDir Path work) throws Exception {
        Path fixture = copy(CHILD, work.resolve("src"));
        Path empty = work.resolve("empty");
        Files.createDirectories(empty);
        try (Stream<Path> s = Files.walk(pkg(fixture))) {
            for (Path p : s.toList()) {
                Path t = empty.resolve(pkg(fixture).relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                }
                else {
                    Files.copy(p, t);
                }
            }
        }
        try (Stream<Path> s = Files.walk(empty)) {
            assertThat(s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).distinct().toList())
                    .as("only the two-file cores are present").containsExactlyInAnyOrder("run.json", "events.jsonl");
        }
        OcsfPackage p = PackageReader.open(empty.resolve("events.jsonl"));
        assertThat(p.refused()).isFalse();
        assertThat(p.diagnostics(Diagnostic.Severity.DEFECT)).isEmpty();
        assertThat(p.children).hasSize(1);
        assertThat(p.catalog.enumerate()).contains("class agentic_cli/tool_call").contains("object agentic_cli/source_ref");
        assertThat(SemanticContract.validate(p.manifest.semanticContract(), p.catalog)).isEmpty();
        Json views = AnalysisViews.all(p);
        assertThat(views.path("usage", "group_count").scalarText()).as("the task figure and the Agent result's one-response usage of the child run are two groups").isEqualTo("7");
        assertThat(views.member("children").elements()).hasSize(1);
        assertThat(views.path("tool_exchanges", "calls").elements()).hasSize(2);
        List<Event> stream = p.events.stream().filter(e -> "stream.jsonl".equals(e.artifact())).toList();
        Reconstruction.Result r = Reconstruction.reconstruct(stream, 27);
        assertThat(r.lines()).as("the parent core owns 24 of the 27 records; the child owns the other 3").hasSize(24);
        Reconstruction.Result child = Reconstruction.reconstruct(p.children.get(0).events, 27);
        assertThat(child.lines().keySet()).containsExactly(18, 20, 21);
        assertThat(PackageMeasure.measure(p).member("definitions_closure_bytes")).isNotNull();
    }
}
