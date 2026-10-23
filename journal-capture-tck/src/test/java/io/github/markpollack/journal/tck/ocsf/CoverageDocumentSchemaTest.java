package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structure, types, enums and conditional required members of the coverage bookkeeping documents, checked against
 * their JSON Schemas ({@code ocsf/schemas/*.schema.json}, draft 2020-12) with a test-scoped validator. The Java checks
 * beside them ({@link VendorStreamInventoryTest}, {@link LiveScenarioTest}, {@link FoundationComparisonTest},
 * {@link MappingCatalogTest}) add what a schema cannot: unique ids, reference resolution, pins and hashes, populations
 * from native evidence, executed tests and evidence-class separation. A valid document says nothing about whether a
 * correspondence is right.
 */
class CoverageDocumentSchemaTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final Path PROJECT = FieldDispositionTest.PROJECT;
    static final Path SCHEMAS = PROJECT.resolve("journal-capture-tck/src/test/resources/ocsf/schemas");

    /** Each document with the schema that governs it. */
    static Map<Path, String> documents() {
        Map<Path, String> m = new LinkedHashMap<>();
        for (VendorStreamInventoryTest.Profile p : VendorStreamInventoryTest.PROFILES) {
            m.put(PROJECT.resolve(p.inventory()), "vendor-stream-inventory.schema.json");
        }
        m.put(LiveScenarioTest.DIR.resolve("roster.json"), "live-scenario-roster.schema.json");
        m.put(LiveScenarioTest.DIR.resolve("results.json"), "live-scenario-results.schema.json");
        m.put(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-pins.json"), "foundation-pins.schema.json");
        m.put(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-inventory.json"), "foundation-inventory.schema.json");
        m.put(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-fields.json"), "foundation-fields.schema.json");
        m.put(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-parity.json"), "foundation-parity.schema.json");
        return m;
    }

    static JsonNode read(Path p) {
        try {
            return MAPPER.readTree(p.toFile());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonSchema schema(String name) {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(read(SCHEMAS.resolve(name)));
    }

    @Test
    void everyCoverageDocumentConformsToItsSchema() {
        for (Map.Entry<Path, String> d : documents().entrySet()) {
            Set<ValidationMessage> errors = schema(d.getValue()).validate(read(d.getKey()));
            assertThat(errors).as(d.getKey().getFileName() + " against " + d.getValue()).isEmpty();
        }
    }

    @Test
    void aMissingRequiredMemberAnUnknownEnumAndAMissingConditionalMemberAreRejected() {
        JsonNode inventory = read(PROJECT.resolve(VendorStreamInventoryTest.PROFILES.get(0).inventory()));
        JsonSchema inv = schema("vendor-stream-inventory.schema.json");
        ObjectNode noJournal = inventory.deepCopy();
        ((ObjectNode) noJournal.path("families").get(0)).remove("journal");
        assertThat(inv.validate(noJournal)).isNotEmpty();
        ObjectNode badBehavior = inventory.deepCopy();
        ((ObjectNode) badBehavior.path("families").get(0).path("journal")).put("behavior", "supported");
        assertThat(inv.validate(badBehavior)).isNotEmpty();
        ObjectNode noCondition = inventory.deepCopy();
        for (JsonNode f : noCondition.path("families")) {
            if ("conditional".equals(f.path("emitted").asText())) {
                ((ObjectNode) f).remove("condition");
                break;
            }
        }
        assertThat(inv.validate(noCondition)).isNotEmpty();

        JsonSchema results = schema("live-scenario-results.schema.json");
        ObjectNode observedWithoutPredicates = read(LiveScenarioTest.DIR.resolve("results.json")).deepCopy();
        ((ObjectNode) observedWithoutPredicates.path("attempts").get(0).path("behaviors").get(0)).remove("predicates");
        assertThat(results.validate(observedWithoutPredicates)).isNotEmpty();
        ObjectNode blockedWithoutReason = read(LiveScenarioTest.DIR.resolve("results.json")).deepCopy();
        ObjectNode b = (ObjectNode) blockedWithoutReason.path("attempts").get(0).path("behaviors").get(0);
        b.put("status", "blocked");
        b.remove("note");
        assertThat(results.validate(blockedWithoutReason)).isNotEmpty();

        JsonSchema pins = schema("foundation-pins.schema.json");
        ObjectNode unpinned = read(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-pins.json")).deepCopy();
        ObjectNode f0 = (ObjectNode) unpinned.path("foundations").get(0);
        for (String k : List.of("revision", "commit", "tag_commit")) {
            f0.remove(k);
        }
        assertThat(pins.validate(unpinned)).isNotEmpty();
    }

    /**
     * A third party's normalized corpus (for example a published agent-trajectory dataset) is its own evidence class:
     * neither native CLI output nor a live run. A reference to one must pin its revision and file and say what the
     * publisher transformed; an example's native input can never be such a corpus row, only a reviewed synthetic
     * equivalent, so no private dataset enters the build.
     */
    @Test
    void aPublisherNormalizedCorpusIsItsOwnEvidenceClassAndNeverAnExamplesNativeInput() {
        JsonSchema references = schema("coverage-references.schema.json");
        ObjectNode doc = read(SemanticClaims.DIR.resolve("references.json")).deepCopy();
        ObjectNode corpus = ((ArrayNode) doc.path("references")).addObject();
        corpus.put("id", "corpus-control").put("source", "a published normalized corpus").put("evidence_kind", "publisher_normalized");
        assertThat(references.validate(doc)).as("unpinned corpus reference").isNotEmpty();
        corpus.put("revision", "0000000000000000000000000000000000000000").put("path", "data/sample.jsonl").put("note", "publisher-normalized rows: renamed tools, rewritten arguments, positional call ids");
        assertThat(references.validate(doc)).as("pinned corpus reference with its transformation stated").isEmpty();

        JsonSchema examples = schema("semantic-example.schema.json");
        ObjectNode example = read(SemanticClaims.DIR.resolve("examples").resolve("codex-command-pipeline-synthetic.json")).deepCopy();
        assertThat(examples.validate(example)).isEmpty();
        ((ObjectNode) example.path("native")).put("evidence_kind", "publisher_normalized");
        assertThat(examples.validate(example)).as("a corpus row as an example's native input").isNotEmpty();
    }
}
