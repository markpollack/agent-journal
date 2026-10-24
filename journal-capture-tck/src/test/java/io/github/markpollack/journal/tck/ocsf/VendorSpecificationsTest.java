package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.InventoryComparison;
import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
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
    static final Path FIXTURES = Path.of("src", "test", "resources", "ocsf", "fixtures").toAbsolutePath().normalize();

    static final Map<String, String> SPECS = Map.ofEntries(
            Map.entry("claude-code/stream-json/2.1.303", "claude_stream"),
            Map.entry("claude-code/stream-json/2.1.304", "claude_stream"),
            Map.entry("claude-code/session-transcript/2.1.303", "claude_transcript"),
            Map.entry("claude-code/session-transcript/2.1.304", "claude_transcript"),
            Map.entry("codex-cli/exec-json-stream/0.160.1", "codex_stream"),
            Map.entry("codex-cli/rollout/0.160.1", "codex_rollout"),
            Map.entry("antigravity-cli/stream-json/1.4.6", "agy_stream"),
            Map.entry("github-copilot-cli/stdout-json/1.0.100", "copilot_events"),
            Map.entry("github-copilot-cli/session-events/1.0.100", "copilot_events"),
            Map.entry("github-copilot-cli/otel-file/1.0.100", "copilot_otel"),
            Map.entry("github-copilot-cli/usage-report/1.0.100", "copilot_usage_report"),
            Map.entry("junie/session-summary/3813.7", "junie_summary"),
            Map.entry("junie/session-events/3813.7", "junie_session_events"),
            Map.entry("junie/json-stream/3813.7", "junie_stdout"));

    /** The accepted revision of each specification; a revision keeps the earlier ones under {@code revisions}. */
    static final Map<String, String> ACCEPTED = Map.ofEntries(
            Map.entry("claude-code/stream-json/2.1.303", "0.2.0"),
            Map.entry("claude-code/stream-json/2.1.304", "0.1.0"),
            Map.entry("claude-code/session-transcript/2.1.303", "0.1.0"),
            Map.entry("claude-code/session-transcript/2.1.304", "0.1.0"),
            Map.entry("codex-cli/exec-json-stream/0.160.1", "0.1.0"),
            Map.entry("codex-cli/rollout/0.160.1", "0.1.0"),
            Map.entry("antigravity-cli/stream-json/1.4.6", "0.2.0"),
            Map.entry("github-copilot-cli/stdout-json/1.0.100", "0.1.0"),
            Map.entry("github-copilot-cli/session-events/1.0.100", "0.1.0"),
            Map.entry("github-copilot-cli/otel-file/1.0.100", "0.1.0"),
            Map.entry("github-copilot-cli/usage-report/1.0.100", "0.1.0"),
            Map.entry("junie/session-summary/3813.7", "0.1.0"),
            Map.entry("junie/session-events/3813.7", "0.1.0"),
            Map.entry("junie/json-stream/3813.7", "0.1.0"));

    /** Every registered recording of the Antigravity 1.4.6 route; the accepted specification must admit each one. */
    static final List<String> AGY_FIXTURES = List.of(OcsfFixtures.LIVE_AGY_FEATURES, OcsfFixtures.LIVE_AGY_DENIAL,
            OcsfFixtures.LIVE_AGY_INTERRUPT_OPEN, OcsfFixtures.LIVE_AGY_SUBAGENT, OcsfFixtures.LIVE_AGY_MCP_DENIED, OcsfFixtures.LIVE_AGY_MCP);

    @Test
    void everyAcceptedSpecificationParsesCarriesProvenanceAndMatchesItsDerivedSchema() {
        for (Map.Entry<String, String> e : SPECS.entrySet()) {
            Json spec = SourceSpecification.read(VENDOR.resolve(e.getKey() + ".json"));
            assertThat(SchemaCatalog.text(spec, "profile")).isEqualTo(e.getValue());
            assertThat(SchemaCatalog.text(spec, "status")).startsWith("ACCEPTED " + ACCEPTED.get(e.getKey()));
            assertThat(SchemaCatalog.text(spec, "specification_version")).isEqualTo(ACCEPTED.get(e.getKey()));
            assertThat(SchemaCatalog.text(spec.member("applies_to"), "version")).isIn("2.1.299, 2.1.303", "2.1.303", "2.1.304", "0.160.1", "1.4.6", "1.0.100", "3813.7");
            assertThat(SchemaCatalog.text(spec.member("applies_to"), "route_limit")).isNotBlank();
            // one frozen complete source, or several when no single recording carries every variant (Antigravity 1.4.6): each hash-pinned
            List<Json> observed = spec.path("provenance", "observed_on").elements();
            assertThat(observed).isNotEmpty();
            for (Json o : observed) {
                assertThat(SchemaCatalog.text(o, "sha256")).hasSize(64);
            }
            assertThat(observed).hasSameSizeAs(spec.path("inventory", "observed_on").elements());
            assertThat(spec.member("limits").elements().size()).isGreaterThanOrEqualTo(5);
            Json variants = spec.path("inventory", "variants");
            assertThat(((Json.JObject) variants).members()).isNotEmpty();
            for (Map.Entry<String, Json> v : ((Json.JObject) variants).members().entrySet()) {
                assertThat(v.getValue().member("evidence").elements()).as(e.getKey() + " " + v.getKey()).isNotEmpty();
                for (String p : ((Json.JObject) v.getValue().member("paths")).members().keySet()) {
                    // the Claude, Codex and Antigravity tool-input rule; elsewhere ".input." is a vendor name (tokenDetails.input, gen_ai.input.messages)
                    if (!e.getValue().startsWith("copilot_") && !e.getValue().startsWith("junie_")) {
                        assertThat(p).as("no tool-defined key under a data-keyed path").doesNotMatch(".*\\.input\\.[A-Za-z_]+.*");
                    }
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

    /**
     * Antigravity 1.4.6 specification 0.2.0 admits every registered recording of its route: no recording proposes a
     * shape change. The revision is the one reviewed (object parameter values, output beside error on ERROR), it keeps
     * 0.1.0 and its four recordings under {@code revisions}, and the CLI version is unchanged.
     */
    @Test
    void theAntigravitySpecificationRevisionAdmitsEveryRegisteredRecordingAndKeepsItsPredecessor() {
        Json spec = SourceSpecification.read(VENDOR.resolve("antigravity-cli/stream-json/1.4.6.json"));
        assertThat(SchemaCatalog.text(spec.member("applies_to"), "version")).isEqualTo("1.4.6");
        Json variants = spec.path("inventory", "variants");
        for (String v : List.of("step_update/tool/ACTIVE", "step_update/tool/DONE", "step_update/tool/ERROR")) {
            assertThat(((Json.JObject) variants.path(v, "paths", "step_update.tool_info.parameters.{*}", "kinds")).members().keySet())
                    .as(v + " parameter values").containsExactlyInAnyOrder("object", "string");
        }
        assertThat(variants.path("step_update/tool/ERROR", "paths", "step_update.tool_info.output")).as("output beside error").isNotNull();
        assertThat(variants.path("step_update/tool/ERROR", "paths", "step_update.tool_info.error")).isNotNull();
        List<Json> revisions = spec.member("revisions").elements();
        assertThat(revisions).extracting(r -> SchemaCatalog.text(r, "specification_version")).containsExactly("0.1.0", "0.2.0");
        assertThat(revisions.get(0).member("observed_on").elements()).hasSize(4);
        assertThat(revisions.get(1).member("observed_on").elements()).hasSize(AGY_FIXTURES.size());
        for (String fixture : AGY_FIXTURES) {
            Path stream = FIXTURES.resolve(fixture).resolve("native").resolve("stream.jsonl");
            Json report = InventoryComparison.compare(spec, RawInventory.inventory(NativeArtifact.read(stream), "stream.jsonl", "agy_stream"));
            assertThat(report.member("shape_drift")).as(fixture + " drift against the accepted specification").isEqualTo(new Json.JBool(false));
            assertThat(report.member("proposals").scalarText()).as(fixture).isEqualTo("0");
        }
    }
}
