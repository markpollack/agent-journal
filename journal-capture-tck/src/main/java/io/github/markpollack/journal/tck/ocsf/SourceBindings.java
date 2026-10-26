package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The exact specification revisions implemented by the independent Java readers. */
final class SourceBindings {
    private static final Json IMPLEMENTED = read();

    private SourceBindings() {}

    static void check(
            OracleReport report, String owner, String profile, String artifact, Json frozen) {
        List<Json> implemented =
                IMPLEMENTED.member("bindings").elements().stream()
                        .filter(
                                b ->
                                        profile.equals(SourceProfile.text(b, "profile"))
                                                && Json.sameValue(
                                                        b.member("entry_id"),
                                                        frozen.member("entry_id")))
                        .toList();
        Json binding = implemented.size() == 1 ? implemented.get(0) : null;
        for (String member :
                List.of("version", "route", "source_specification", "specification_sha256")) {
            String id = "reader-binding:" + artifact + ":" + member;
            List<String> support = List.of("expected-sources.json#binding/" + member);
            Json expected = frozen.member(member);
            report.expect(
                    new OracleReport.JsonExpectation(
                            owner, id, "source.reader_binding/v1", expected, support));
            FactComparison.compare(
                    report,
                    "source.reader_binding",
                    owner,
                    new Check.Evidence(
                            "source.reader_binding/v1",
                            id,
                            support,
                            "reader:source-meaning-bindings.json#" + profile + "/" + member,
                            expected,
                            binding == null ? null : binding.member(member)));
        }
    }

    private static Json read() {
        try (InputStream in =
                SourceBindings.class.getResourceAsStream("/ocsf/source-meaning-bindings.json")) {
            if (in == null) {
                throw new IllegalStateException("missing exact source meaning bindings");
            }
            return NeutralJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
