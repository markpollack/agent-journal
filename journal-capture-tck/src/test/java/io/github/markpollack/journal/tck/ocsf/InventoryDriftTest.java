package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.ocsf.inventory.ExternalSchemaPaths;
import io.github.markpollack.journal.tck.ocsf.inventory.InventoryComparison;
import io.github.markpollack.journal.tck.ocsf.inventory.NativeInventoryTool;
import io.github.markpollack.journal.tck.ocsf.inventory.RawInventory;
import io.github.markpollack.journal.tck.ocsf.inventory.SourceSpecification;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The raw-record inventory, the accepted-specification drift comparison and the external-description cross-check on
 * the committed fixtures: a new field is discovered even where the derived schema is permissive, a new record
 * variant, a kind change and a nullability change are each reported with their variant and record locations (never
 * by finding an equal value elsewhere), a same-shape change of meaning is invisible to the inventory and is caught by
 * the semantic oracle instead, and the external cross-check reports described/undescribed variants and undeclared
 * paths without filtering the inventory.
 */
class InventoryDriftTest {

    static final Path FIXTURES = Path.of("src", "test", "resources", "ocsf", "fixtures").toAbsolutePath();
    static final Path PAIR_TRANSCRIPT = FIXTURES.resolve("syn-claude-pair-transcript").resolve("native").resolve("transcript.jsonl");
    static final Path CODEX_STREAM = FIXTURES.resolve("syn-codex-exec-stream").resolve("native").resolve("stream.jsonl");

    static Json inventory(Path artifact, String profile) {
        return RawInventory.inventory(NativeArtifact.read(artifact), artifact.getFileName().toString(), profile);
    }

    static Json spec(Json... inventories) {
        return SourceSpecification.draft(SchemaCatalog.text(inventories[0], "profile"), "test", null, null, List.of(), null, List.of(inventories));
    }

    static List<Json> findings(Json report, String kind) {
        List<Json> out = new ArrayList<>();
        for (Json f : report.member("findings").elements()) {
            if (kind.equals(SchemaCatalog.text(f, "finding"))) {
                out.add(f);
            }
        }
        return out;
    }

    static Json mutated(Path work, Path artifact, java.util.function.Consumer<List<Json>> edit) throws Exception {
        List<Json> lines = new ArrayList<>();
        for (NeutralJson.Line l : NeutralJson.readLines(artifact)) {
            lines.add(l.json());
        }
        edit.accept(lines);
        StringBuilder sb = new StringBuilder();
        for (Json l : lines) {
            sb.append(NeutralJson.write(l)).append('\n');
        }
        Files.createDirectories(work);
        Path out = work.resolve(artifact.getFileName().toString());
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return inventory(out, "claude_transcript");
    }

    @Test
    void inventoryRecordsVariantsPathsKindsAndEvidenceAndKeepsDataKeysApart() {
        Json inv = inventory(PAIR_TRANSCRIPT, "claude_transcript");
        Json variants = inv.member("variants");
        assertThat(((Json.JObject) variants).members().keySet()).contains("assistant", "user/prompt", "user/tool_result", "attachment/environment", "cost-state", "atis-latch", "queue-operation");
        Json assistant = variants.member("assistant");
        assertThat(assistant.member("records").scalarText()).isEqualTo("2");
        assertThat(assistant.member("evidence").elements()).extracting(Json::scalarText).containsExactly("12", "13");
        Json stop = assistant.path("paths", "message.stop_reason");
        assertThat(((Json.JObject) stop.member("kinds")).members().keySet()).containsExactly("string");
        assertThat(stop.member("present_in_records").scalarText()).isEqualTo("2");
        Json input = assistant.path("paths", "message.content[].input");
        assertThat(input).as("the tool input container is inventoried as a data-keyed object").isNotNull();
        assertThat(input.member("distinct_data_keys")).isNotNull();
        assertThat(assistant.path("paths", "message.content[].input.{*}")).as("only the value kinds under a data-keyed path").isNotNull();
        for (String p : ((Json.JObject) assistant.member("paths")).members().keySet()) {
            assertThat(p).as("no tool-defined key is inventoried as vendor structure").doesNotMatch("message\\.content\\[\\]\\.input\\.[a-z_]+");
        }
        Json cost = variants.member("cost-state");
        assertThat(cost.path("paths", "modelUsage.{*}.inputTokens")).as("map-keyed: model names collapse, vendor structure continues below").isNotNull();
        assertThat(cost.path("paths", "modelUsage").member("distinct_data_keys").scalarText()).isEqualTo("1");
        Json usage = assistant.path("paths", "message.usage.input_tokens");
        assertThat(((Json.JObject) usage.member("kinds")).members().keySet()).containsExactly("integer");
    }

    @Test
    void aNewFieldIsDiscoveredEvenWhereTheDerivedSchemaIsPermissive(@TempDir Path work) throws Exception {
        Json base = inventory(PAIR_TRANSCRIPT, "claude_transcript");
        Json spec = spec(base);
        ((Json.JObject) spec).members().put("open_variants", new Json.JArray(List.of(new Json.JString("attachment/environment"))));
        Json schema = SourceSpecification.jsonSchema(spec);
        assertThat(schema.path("$defs", "attachment_environment", "additionalProperties")).as("the derived schema is permissive for this variant").isEqualTo(new Json.JBool(true));
        assertThat(schema.path("$defs", "assistant", "additionalProperties")).isEqualTo(new Json.JBool(false));
        assertThat(schema.path("$defs", "assistant", "properties", "message", "properties", "content", "items", "properties", "input", "additionalProperties")).isEqualTo(new Json.JBool(true));
        assertThat(schema.path("$defs", "assistant", "properties", "message", "properties", "content", "items", "properties", "input", "x-journal-data-keyed")).isEqualTo(new Json.JBool(true));

        Json observed = mutated(work, PAIR_TRANSCRIPT, lines -> ((Json.JObject) lines.get(3)).members().put("syntheticNewVendorField", new Json.JString("x"))); // record 4: attachment/environment
        Json report = InventoryComparison.compare(spec, observed);
        assertThat(report.member("shape_drift")).isEqualTo(new Json.JBool(true));
        List<Json> newPaths = findings(report, "NEW_PATH");
        assertThat(newPaths).hasSize(1);
        assertThat(newPaths.get(0).member("variant").text()).isEqualTo("attachment/environment");
        assertThat(newPaths.get(0).member("path").text()).isEqualTo("syntheticNewVendorField");
        assertThat(newPaths.get(0).member("records").elements()).extracting(Json::scalarText).containsExactly("4");
        assertThat(newPaths.get(0).member("artifact").text()).isEqualTo("transcript.jsonl");
        assertThat(report.path("proposed_specification_inventory", "status").text()).startsWith("PROPOSAL");
        assertThat(report.path("proposed_specification_inventory", "inventory", "variants", "attachment/environment", "paths", "syntheticNewVendorField")).as("the proposal would admit it; the accepted spec is untouched").isNotNull();
        assertThat(spec.path("inventory", "variants", "attachment/environment", "paths", "syntheticNewVendorField")).isNull();
    }

    @Test
    void aFieldKnownOnAnotherVariantIsStillNewOnThisOneAndLocatedThere(@TempDir Path work) throws Exception {
        Json spec = spec(inventory(PAIR_TRANSCRIPT, "claude_transcript"));
        assertThat(spec.path("inventory", "variants", "user/prompt", "paths", "promptId")).as("promptId is an accepted path of user records").isNotNull();
        Json observed = mutated(work, PAIR_TRANSCRIPT, lines -> ((Json.JObject) lines.get(4)).members().put("promptId", new Json.JString("p"))); // record 5: attachment/model
        List<Json> newPaths = findings(InventoryComparison.compare(spec, observed), "NEW_PATH");
        assertThat(newPaths).hasSize(1);
        assertThat(newPaths.get(0).member("variant").text()).isEqualTo("attachment/model");
        assertThat(newPaths.get(0).member("path").text()).isEqualTo("promptId");
        assertThat(newPaths.get(0).member("records").elements()).extracting(Json::scalarText).containsExactly("5");
    }

    @Test
    void aNewVariantAKindChangeAndANullabilityChangeAreEachReportedWithTheirLocation(@TempDir Path work) throws Exception {
        Json spec = spec(inventory(PAIR_TRANSCRIPT, "claude_transcript"));
        Json observed = mutated(work, PAIR_TRANSCRIPT, lines -> {
            lines.add(NeutralJson.parse("{\"type\":\"synthetic-new-line\",\"uuid\":\"u\",\"timestamp\":\"2026-10-21T00:00:00.000Z\"}")); // record 18
            ((Json.JObject) lines.get(2)).members().put("cwd", new Json.JNumber("7"));                 // record 3: user/prompt cwd string -> integer
            ((Json.JObject) lines.get(11)).members().put("uuid", Json.JNull.INSTANCE);                // record 12: assistant uuid string -> null
        });
        Json report = InventoryComparison.compare(spec, observed);
        List<Json> variants = findings(report, "NEW_VARIANT");
        assertThat(variants).hasSize(1);
        assertThat(variants.get(0).member("variant").text()).isEqualTo("synthetic-new-line");
        assertThat(variants.get(0).member("records").elements()).extracting(Json::scalarText).containsExactly("18");
        List<Json> kinds = findings(report, "KIND_CHANGE");
        assertThat(kinds).hasSize(2);
        Json cwd = kinds.stream().filter(f -> "cwd".equals(f.member("path").text())).findFirst().orElseThrow();
        assertThat(cwd.member("variant").text()).isEqualTo("user/prompt");
        assertThat(cwd.member("detail").text()).contains("kind integer observed").contains("accepted kinds [string]");
        assertThat(cwd.member("records").elements()).extracting(Json::scalarText).containsExactly("3");
        Json uuid = kinds.stream().filter(f -> "uuid".equals(f.member("path").text())).findFirst().orElseThrow();
        assertThat(uuid.member("variant").text()).isEqualTo("assistant");
        assertThat(uuid.member("detail").text()).contains("nullability change");
        assertThat(uuid.member("records").elements()).extracting(Json::scalarText).as("both assistant records carry uuid; the finding cites the variant's evidence for the path").contains("12");
        assertThat(report.member("proposals").scalarText()).isEqualTo("3");
    }

    @Test
    void aMissingOptionalFieldIsANoteNotARemovalAndAnUnchangedSpecimenHasNoDrift(@TempDir Path work) throws Exception {
        Json base = inventory(PAIR_TRANSCRIPT, "claude_transcript");
        Json spec = spec(base);
        Json same = InventoryComparison.compare(spec, base);
        assertThat(same.member("shape_drift")).isEqualTo(new Json.JBool(false));
        assertThat(same.member("proposals").scalarText()).isEqualTo("0");
        Json observed = mutated(work, PAIR_TRANSCRIPT, lines -> {
            ((Json.JObject) lines.get(2)).members().remove("gitBranch");  // record 3, the only user/prompt record: the path is no longer observed at all
            ((Json.JObject) lines.get(11)).members().remove("gitBranch"); // record 12, one of two assistant records: the path became optional
        });
        Json report = InventoryComparison.compare(spec, observed);
        assertThat(report.member("shape_drift")).isEqualTo(new Json.JBool(false));
        List<Json> absent = findings(report, "NEWLY_ABSENT");
        assertThat(absent).hasSize(1);
        assertThat(absent.get(0).member("variant").text()).isEqualTo("assistant");
        assertThat(absent.get(0).member("path").text()).isEqualTo("gitBranch");
        assertThat(absent.get(0).member("severity").text()).isEqualTo("note");
        assertThat(absent.get(0).member("detail").text()).contains("present in 1 of 2 record(s)").contains("not inferred as removed");
        List<Json> notObserved = findings(report, "NOT_OBSERVED");
        Json prompt = notObserved.stream().filter(f -> "user/prompt".equals(f.member("variant").text()) && "gitBranch".equals(f.member("path").text())).findFirst().orElseThrow();
        assertThat(prompt.member("severity").text()).isEqualTo("note");
        assertThat(prompt.member("detail").text()).contains("present in every accepted record").contains("not evidence of removal");
    }

    @Test
    void aSameShapeSemanticChangeIsInvisibleToTheInventoryAndCaughtByTheSemanticOracle(@TempDir Path work) throws Exception {
        Json spec = spec(inventory(PAIR_TRANSCRIPT, "claude_transcript"));
        Path fixture = OcsfFixtures.copy("syn-claude-pair-transcript", work);
        Path transcript = fixture.resolve("native").resolve("transcript.jsonl");
        // record 13: the terminal assistant record's stop_reason changes value, not shape
        Json observed = mutated(work.resolve("inv"), transcript, lines -> ((Json.JObject) lines.get(12).member("message")).members().put("stop_reason", new Json.JString("max_tokens")));
        Json report = InventoryComparison.compare(spec, observed);
        assertThat(report.member("shape_drift")).as("same kinds at the same paths: the inventory sees nothing").isEqualTo(new Json.JBool(false));
        OcsfFixtures.editNative(fixture, "transcript.jsonl", lines -> ((Json.JObject) lines.get(12).member("message")).members().put("stop_reason", new Json.JString("max_tokens")));
        OracleReport oracle = OcsfFixtures.verify(fixture);
        assertThat(oracle.outcome("response.values", "main/transcript.jsonl")).as("the semantic oracle judges the value").isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(oracle.check("response.values", "main/transcript.jsonl").detail()).contains("stop_reason tool_use != max_tokens");
    }

    @Test
    void theExternalDescriptionCrossCheckReportsDescribedVariantsUndeclaredPathsAndOpenSubtrees() throws Exception {
        Json schema = NeutralJson.readDocument(Path.of("src", "test", "resources", "ocsf", "inventory", "labelled-external-transcript.schema.json").toAbsolutePath());
        Map<String, ExternalSchemaPaths.Declared> declared = ExternalSchemaPaths.declared(schema, "type");
        assertThat(declared.keySet()).containsExactly("assistant", "user");
        assertThat(declared.get("user").paths()).contains("message.content[].input", "toolUseResult", "declaredOnlyField");
        assertThat(declared.get("user").open()).contains("message.content[].input", "toolUseResult");
        Json inv = inventory(PAIR_TRANSCRIPT, "claude_transcript");
        Json report = ExternalSchemaPaths.compare(schema, "type", inv, "type", "labelled-external-transcript.schema.json");
        assertThat(report.member("variants_described").scalarText()).isEqualTo("3");
        Map<String, Json> rows = new java.util.LinkedHashMap<>();
        for (Json r : report.member("rows").elements()) {
            rows.put(r.member("variant").text(), r);
        }
        assertThat(rows.get("attachment/environment").member("described")).isEqualTo(new Json.JBool(false));
        Json user = rows.get("user/tool_result");
        assertThat(user.member("described")).isEqualTo(new Json.JBool(true));
        assertThat(user.member("observed_not_declared").elements()).extracting(Json::text).contains("promptId", "permissionDecision", "message.content[].tool_use_id");
        assertThat(user.member("observed_not_declared").elements()).extracting(Json::text).as("paths under the external open subtree are not reported").noneMatch(p -> p.startsWith("toolUseResult."));
        assertThat(user.member("declared_not_observed").elements()).extracting(Json::text).contains("declaredOnlyField");
        assertThat(inv.path("variants", "user/tool_result", "paths", "promptId")).as("the inventory itself is not filtered by the external description").isNotNull();
    }

    @Test
    void theCodexStreamInventoryKeepsVariantsByItemTypeAndTheToolRunsEndToEnd(@TempDir Path work) throws Exception {
        Json inv = inventory(CODEX_STREAM, "codex_stream");
        assertThat(((Json.JObject) inv.member("variants")).members().keySet()).containsExactly("item.completed/agent_message", "item.completed/command_execution",
                "item.completed/synthetic_unknown_item", "item.started/command_execution", "synthetic.unknown_event", "thread.started", "turn.completed", "turn.started");
        Json exit = inv.path("variants", "item.started/command_execution", "paths", "item.exit_code");
        assertThat(((Json.JObject) exit.member("kinds")).members().keySet()).containsExactly("null");
        assertThat(exit.member("null").scalarText()).isEqualTo("3");
        Json usage = inv.path("variants", "turn.completed", "paths", "usage.input_tokens");
        assertThat(((Json.JObject) usage.member("kinds")).members().keySet()).containsExactly("integer");

        Path spec = work.resolve("spec.json");
        assertThat(run("draft", "codex_stream", "test", spec.toString(), CODEX_STREAM.toString())).isEqualTo(NativeInventoryTool.EXIT_OK);
        assertThat(run("drift", spec.toString(), CODEX_STREAM.toString(), work.resolve("drift.json").toString())).isEqualTo(NativeInventoryTool.EXIT_OK);
        assertThat(run("schema", spec.toString(), work.resolve("schema.json").toString())).isEqualTo(NativeInventoryTool.EXIT_OK);
        Path changed = work.resolve("stream.jsonl");
        Files.writeString(changed, Files.readString(CODEX_STREAM) + "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":1,\"synthetic_new_component\":2}}\n", StandardCharsets.UTF_8);
        assertThat(run("drift", spec.toString(), changed.toString(), work.resolve("drift2.json").toString())).isEqualTo(NativeInventoryTool.EXIT_DRIFT);
        Json drift = NeutralJson.readDocument(work.resolve("drift2.json"));
        assertThat(findings(drift, "NEW_PATH").get(0).member("path").text()).isEqualTo("usage.synthetic_new_component");
        assertThat(findings(drift, "NEW_PATH").get(0).member("records").elements()).extracting(Json::scalarText).containsExactly("13");
        assertThat(run("bogus", "x", "y")).isEqualTo(NativeInventoryTool.EXIT_USAGE);
    }

    static int run(String... args) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return NativeInventoryTool.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(out, true, StandardCharsets.UTF_8));
    }
}
