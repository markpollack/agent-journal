package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.verify.Check;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.github.markpollack.journal.ocsf.writer.FinalAssemblyClaudeTest.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Synthetic control records supplement the retained sanitized pair; they are not new native evidence. */
class AssemblyRecordSessionTest {
    @TempDir Path work;

    private List<In> inputs(boolean identity) throws IOException {
        Jv.Obj control = new Jv.Obj().put("type", "control_response")
                .put("response", new Jv.Obj().put("subtype", "success").put("request_id", "synthetic-init"));
        if (identity) control.put("session_id", RUN_SESSION);
        Path stream = work.resolve("with-control.jsonl");
        String original = Files.readString(stream().file());
        // Identity normalization is only expected once the init has selected the tested source version.
        int offset = identity ? original.indexOf('\n') + 1 : 0;
        Files.writeString(stream, original.substring(0, offset) + Jv.write(control) + "\n" + original.substring(offset));
        return List.of(new In("stream.jsonl", "stream", stream, "synthetic control plus sanitized live stream"), transcript());
    }

    @Test void sourceMembershipDoesNotInventIdentityOnAnUnidentifiedControlRecord() throws IOException {
        List<In> inputs = inputs(false);
        Path out = assemble(work.resolve("assembled"), inputs);
        var control = PackageReader.open(out.resolve("events.jsonl")).events.stream()
                .filter(e -> "control_response".equals(e.text("record_type"))).findFirst().orElseThrow();
        assertThat(control.get("session_uid")).isNull();
        assertThat(oracle(out, inputs).defects()).isEmpty();

        Path forged = mutate(work, out, firstMatching(l -> l.contains("\"record_type\":\"control_response\""),
                l -> session(l, RUN_SESSION)));
        assertThat(oracle(forged, inputs).defects()).extracting(Check::property).contains("assembly.session.value");

        Path missingMapped = mutate(work, out, firstMatching(l -> Jv.parse(l).member("source_ref") != null && Jv.parse(l).member("session_uid") != null,
                l -> session(l, null)));
        assertThat(oracle(missingMapped, inputs).defects()).extracting(Check::property).contains("assembly.session.value");
    }

    @Test void aControlRecordsReportedIdentityCannotBeDroppedOrChanged() throws IOException {
        List<In> inputs = inputs(true);
        Path out = assemble(work.resolve("assembled"), inputs);
        assertThat(oracle(out, inputs).defects()).isEmpty();
        for (String value : List.of("missing", OTHER_SESSION)) {
            Path changed = mutate(work, out, firstMatching(l -> l.contains("\"record_type\":\"control_response\""),
                    l -> session(l, value.equals("missing") ? null : value)));
            assertThat(oracle(changed, inputs).defects()).extracting(Check::property).contains("assembly.session.value");
        }
    }

    private static String session(String line, String value) {
        Jv.Obj event = (Jv.Obj) Jv.parse(line);
        if (value == null) event.members().remove("session_uid");
        else event.put("session_uid", value);
        return Jv.write(event);
    }
}
