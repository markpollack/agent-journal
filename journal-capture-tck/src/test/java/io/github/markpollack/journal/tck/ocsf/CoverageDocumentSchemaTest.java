package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
}
