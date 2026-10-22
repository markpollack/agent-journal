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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reviewed semantic examples ({@code ocsf/mapping-catalog/examples/*.json}) against the oracle's own source
 * profile. The writer's mapper and the oracle's profile are written independently, yet they can share a wrong premise
 * and agree: the oracle then reports the property VERIFIED. A reviewed example, authored from pinned vendor sources,
 * is the third reading; this test translates the profile's facts for each example event into the pointers the profile
 * states (requests, results, operations, responses, usage) and compares them with the example, so a shared misreading
 * is rejected on both sides ({@code SemanticExampleTest} checks the writer). Pointers the profile does not state (the
 * writer's envelope, retained data, provenance) are the writer test's alone.
 */
class SemanticExampleProfileTest {

    static final Path EXAMPLES = Path.of(SemanticExampleProfileTest.class.getResource("/ocsf/mapping-catalog/examples").getPath().replace("%20", " "));

    static List<Path> examples() throws IOException {
        try (Stream<Path> s = Files.list(EXAMPLES)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    static NativeArtifact nativeArtifact(Json nat, Path work) {
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

    static Json num(int v) {
        return new Json.JNumber(Integer.toString(v));
    }

    /** The pointers the profile states for one event of the example (class, activity, record), or none. */
    static Map<String, Json> stated(ExpectedFacts f, String cls, int activity, int record) {
        Map<String, Json> out = new LinkedHashMap<>();
        switch (cls + "/" + activity) {
            case "tool_call/1" -> f.requests.stream().filter(r -> r.recordIndex() == record).findFirst().ifPresent(r -> {
                put(out, "/tool_call_uid", r.id() == null ? null : new Json.JString(r.id()));
                put(out, "/tool_name", r.name() == null ? null : new Json.JString(r.name()));
                put(out, "/tool_namespace", r.namespace() == null ? null : new Json.JString(r.namespace()));
                put(out, "/identity_basis_id", r.identityBasisId() == null ? null : num(r.identityBasisId()));
                put(out, "/arguments", r.arguments());
                put(out, "/reported_state", r.reportedState() == null ? null : new Json.JString(r.reportedState()));
            });
            case "tool_call/2" -> f.results.stream().filter(r -> r.recordIndex() == record).findFirst().ifPresent(r -> {
                put(out, "/tool_call_uid", new Json.JString(r.id()));
                put(out, "/identity_basis_id", r.identityBasisId() == null ? null : num(r.identityBasisId()));
                put(out, "/tool_result/data", r.content());
                put(out, "/tool_result/structured_result", r.structured());
                put(out, "/tool_result/error_reported", r.content() == null ? null : r.isError());
                put(out, "/status_id", num(r.isError() instanceof Json.JBool b ? (b.value() ? 2 : 1) : 0));
                put(out, "/status_basis_id", num(r.isError() instanceof Json.JBool ? 1 : 3));
            });
            case "tool_call/3" -> f.operations.stream().filter(o -> o.recordIndex() == record).findFirst().ifPresent(o -> {
                put(out, "/tool_operation/uid", new Json.JString(o.uid()));
                put(out, "/tool_operation/command_line", o.commandLine() == null ? null : new Json.JString(o.commandLine()));
                put(out, "/tool_operation/identity_basis_id", o.identityBasisId() == null ? null : num(o.identityBasisId()));
                put(out, "/tool_operation/reported_state", o.reportedState() == null ? null : new Json.JString(o.reportedState()));
                put(out, "/tool_operation/attribution/outcome_id", num(o.outcomeId()));
                put(out, "/status_id", num(o.statusId()));
            });
            case "model_response/2", "model_response/1" -> f.responses.stream().filter(r -> r.recordIndex() == record).findFirst().ifPresent(r -> {
                put(out, "/response_uid", new Json.JString(r.id()));
                for (int i = 0; r.blocks() != null && i < r.blocks().size(); i++) {
                    put(out, "/content_blocks/" + i + "/data", r.blocks().get(i));
                    put(out, "/content_blocks/" + i + "/reasoning_form_id", r.reasoningForm() == null ? null : num(r.reasoningForm()));
                }
            });
            case "prompt_turn/2" -> {
                List<ExpectedFacts.Usage> usages = f.usages.stream().filter(u -> u.eventRecordIndex() == record).toList();
                for (int i = 0; i < usages.size(); i++) {
                    ExpectedFacts.Usage u = usages.get(i);
                    String at = "/usage_observations/" + i + "/";
                    put(out, at + "scope_id", num(u.scopeId()));
                    put(out, at + "usage_status_id", num(u.statusId()));
                    put(out, at + "measurement_status_id", u.measurementStatusId() == null ? null : num(u.measurementStatusId()));
                    u.components().forEach((k, v) -> put(out, at + k, v));
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static void put(Map<String, Json> out, String pointer, Json value) {
        out.put(pointer, value);
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
                Map<String, Json> stated = stated(facts, SchemaCatalog.text(exp, "class"), Event.integer(exp.member("activity_id")), record);
                for (Map.Entry<String, Json> v : ((Json.JObject) exp.member("values")).members().entrySet()) {
                    if (!stated.containsKey(v.getKey())) {
                        continue; // not a fact the profile states: the writer test's alone
                    }
                    compared++;
                    Json s = stated.get(v.getKey());
                    if (s == null || !Json.differences(v.getValue(), s).isEmpty()) {
                        problems.add(id + " record " + record + " " + v.getKey() + ": the profile states " + (s == null ? "nothing" : NeutralJson.write(s)) + ", the reviewed example " + NeutralJson.write(v.getValue()));
                    }
                }
                for (Json absent : exp.member("absent") == null ? List.<Json>of() : exp.member("absent").elements()) {
                    if (stated.get(absent.text()) != null) {
                        compared++;
                        problems.add(id + " record " + record + " " + absent.text() + ": the profile states " + NeutralJson.write(stated.get(absent.text())) + ", the reviewed example says absent");
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
