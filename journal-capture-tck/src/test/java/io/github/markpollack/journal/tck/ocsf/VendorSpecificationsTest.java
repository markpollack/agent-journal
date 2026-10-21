package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.SourceSpecification;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed Journal-owned native source specifications under {@code schema/vendor/}: each parses as an accepted
 * specification with provenance, applicability and limits, names non-empty variants with evidence, keeps its
 * data-keyed subtrees collapsed, and its committed derived JSON Schema is exactly what the tool derives from it (a
 * hand edit or a stale regeneration is caught).
 */
class VendorSpecificationsTest {

    static final Path VENDOR = Path.of("..", "schema", "vendor").toAbsolutePath().normalize();

    static final Map<String, String> SPECS = Map.of(
            "claude-code/stream-json/2.1.303", "claude_stream",
            "claude-code/session-transcript/2.1.303", "claude_transcript",
            "codex-cli/exec-json-stream/0.160.1", "codex_stream",
            "codex-cli/rollout/0.160.1", "codex_rollout");

    @Test
    void everyAcceptedSpecificationParsesCarriesProvenanceAndMatchesItsDerivedSchema() {
        for (Map.Entry<String, String> e : SPECS.entrySet()) {
            Json spec = SourceSpecification.read(VENDOR.resolve(e.getKey() + ".json"));
            assertThat(SchemaCatalog.text(spec, "profile")).isEqualTo(e.getValue());
            assertThat(SchemaCatalog.text(spec, "status")).startsWith("ACCEPTED 0.1.0");
            assertThat(SchemaCatalog.text(spec, "specification_version")).isEqualTo("0.1.0");
            assertThat(SchemaCatalog.text(spec.member("applies_to"), "version")).isIn("2.1.303", "0.160.1");
            assertThat(SchemaCatalog.text(spec.member("applies_to"), "route_limit")).isNotBlank();
            List<Json> observed = spec.path("provenance", "observed_on").elements();
            assertThat(observed).hasSize(1);
            assertThat(SchemaCatalog.text(observed.get(0), "sha256")).hasSize(64);
            assertThat(spec.member("limits").elements().size()).isGreaterThanOrEqualTo(5);
            Json variants = spec.path("inventory", "variants");
            assertThat(((Json.JObject) variants).members()).isNotEmpty();
            for (Map.Entry<String, Json> v : ((Json.JObject) variants).members().entrySet()) {
                assertThat(v.getValue().member("evidence").elements()).as(e.getKey() + " " + v.getKey()).isNotEmpty();
                for (String p : ((Json.JObject) v.getValue().member("paths")).members().keySet()) {
                    assertThat(p).as("no tool-defined key under a data-keyed path").doesNotMatch(".*\\.input\\.[A-Za-z_]+.*");
                }
            }
            Json committed = NeutralJson.readDocument(VENDOR.resolve(e.getKey() + ".schema.json"));
            assertThat(Json.sameValue(SourceSpecification.jsonSchema(spec), committed)).as(e.getKey() + ".schema.json is the derived schema").isTrue();
            assertThat(SchemaCatalog.text(committed, "x-journal-derived-from")).startsWith(SourceSpecification.KIND);
        }
        Json transcript = SourceSpecification.read(VENDOR.resolve("claude-code/session-transcript/2.1.303.json"));
        assertThat(((Json.JObject) transcript.path("inventory", "variants")).members().keySet()).contains("attachment/environment", "cost-state", "atis-latch", "api-request-blob");
        assertThat(transcript.path("inventory", "variants", "assistant", "paths", "wireToolInputs", "distinct_data_keys")).isNotNull();
        assertThat(transcript.path("inventory", "variants", "api-request-shape", "paths", "shape.tools[].input_schema.{*}")).isNotNull();
    }
}
