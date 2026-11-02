package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Labelled synthetic denial arrays supplement a sanitized recording; no new live evidence. */
class MultiplePermissionDenialsTest {
    @TempDir Path work;

    private Path write(List<String> ids) throws IOException {
        Path nativeDir = Files.createDirectory(work.resolve("native"));
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(nativeOf(LIVE_DENIAL, "stream.jsonl"))) {
            Jv.Obj record = (Jv.Obj) Jv.parse(line);
            String type = record.member("type").text();
            if (!"result".equals(type) && !("system".equals(type) && "init".equals(record.member("subtype").text()))) continue;
            if ("result".equals(type)) {
                Jv.Arr denials = new Jv.Arr();
                for (String id : ids) {
                    Jv.Obj denial = new Jv.Obj().put("tool_name", "Read").put("tool_input", new Jv.Obj().put("file_path", "synthetic.txt"));
                    if (!id.isEmpty()) denial.put("tool_use_id", id);
                    denials.add(denial);
                }
                record.put("permission_denials", denials);
            }
            lines.add(Jv.write(record));
        }
        Path input = Files.write(nativeDir.resolve("stream.jsonl"), lines);
        Path out = work.resolve("output");
        try (OcsfRunWriter writer = OcsfRunWriter.open(out, run(LIVE_DENIAL), SchemaPin.bundled(), options())) {
            Replay.feedBytes(writer.source(claudeStreamStdout()), input, 1024, options().clock());
            writer.complete();
        }
        freeze(nativeDir, run(LIVE_DENIAL).runUid(), true, "stream.jsonl", "claude_stream");
        return out;
    }

    private OracleReport check(Path out) {
        return verify(out, work.resolve("native"));
    }

    @Test void distinctDenialsKeepTheirOwnValuesAndSourceOccurrence() throws IOException {
        Path out = write(List.of("synthetic-a", "synthetic-b", "synthetic-c", "synthetic-d"));
        assertThat(notVerified(check(out))).isEmpty();
        assertThat(check(out).checks().stream().filter(c -> c.property().equals("fact.population")
                && c.evidence() != null && c.evidence().meaning().equals("source.permission/v1"))).hasSize(4);
        for (Consumer<List<Jv>> mutation : List.<Consumer<List<Jv>>>of(
                ds -> ds.remove(1),
                ds -> ds.add(Jv.copy(ds.get(0))),
                ds -> Collections.swap(ds, 0, 1),
                ds -> ((Jv.Obj) ds.get(1).member("attribution")).put("candidates", new Jv.Arr().add(new Jv.Str("forged"))),
                ds -> ((Jv.Obj) ds.get(1).member("source_ref")).put("record_index", 1),
                ds -> ((Jv.Obj) ds.get(1).member("source_ref")).put("artifact", "wrong-parent.jsonl"))) {
            Path changed = mutate(out, mutation);
            assertThat(check(changed).defects()).as(check(changed).toString()).isNotEmpty();
        }
    }

    @Test void repeatedOrMissingToolIdentifiersDoNotCollapseNativeArrayOccurrences() throws IOException {
        Path out = write(List.of("synthetic-repeat", "synthetic-repeat", "", ""));
        assertThat(notVerified(check(out))).isEmpty();
        var result = open(out).events.stream().filter(e -> !e.elements("permission_decisions").isEmpty()).findFirst().orElseThrow();
        assertThat(result.elements("permission_decisions")).hasSize(4);
        assertThat(result.elements("permission_decisions").get(2).path("attribution", "candidates").elements()).isEmpty();
        Path changed = mutate(out, ds -> ((Jv.Obj) ds.get(2).member("attribution")).put("candidates", new Jv.Arr().add(new Jv.Str("invented"))));
        assertThat(check(changed).defects()).extracting(Check::property).contains("decision.values");
    }

    private Path mutate(Path out, Consumer<List<Jv>> edit) throws IOException {
        return FinalAssemblyClaudeTest.mutate(work, out, line -> {
            Jv.Obj event = (Jv.Obj) Jv.parse(line);
            if (event.member("permission_decisions") instanceof Jv.Arr denials) edit.accept(denials.elements());
            return Jv.write(event);
        });
    }
}
