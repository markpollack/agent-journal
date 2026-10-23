package io.github.markpollack.journal.tck.ocsf;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The committed foundation field inventory ({@code foundation-fields.json}): its structure always, and, when the pinned
 * upstream files are supplied, that it is exactly what {@link FoundationFields} derives from them. The supplied files
 * must carry the git blob ids the inventory records, so a copy that is not the pinned revision is refused rather than
 * silently re-inventoried. With {@code -Djournal.foundation.write=true} the derivation rewrites the committed file
 * (the only way it is produced).
 */
class FoundationFieldsTest {

    static final Path FILE = FoundationComparisonTest.CATALOG_DIR.resolve("foundation-fields.json");
    static final List<String> TIER_ONE = List.of(FoundationFields.OTEL, FoundationFields.OI, FoundationFields.WEAVE);

    static JsonNode inventory() {
        return FoundationComparisonTest.read(FILE);
    }

    /** Foundation id to its exact pinned commit, from foundation-pins.json. */
    static Map<String, String> revisions() {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode f : FoundationComparisonTest.read(FoundationComparisonTest.CATALOG_DIR.resolve("foundation-pins.json")).path("foundations")) {
            String rev = Stream.of("revision", "commit", "tag_commit").map(k -> f.path(k).asText()).filter(v -> FoundationComparisonTest.COMMIT.matcher(v).matches())
                    .findFirst().orElse(null);
            out.put(f.path("id").asText(), rev);
        }
        return out;
    }

    static List<String> problems(JsonNode inv) {
        List<String> problems = new ArrayList<>();
        Map<String, String> revisions = revisions();
        Set<String> seen = new TreeSet<>();
        List<String> foundations = new ArrayList<>();
        for (JsonNode f : inv.path("foundations")) {
            String id = f.path("foundation").asText();
            foundations.add(id);
            if (!f.path("revision").asText().equals(revisions.get(id))) {
                problems.add(id + ": revision " + f.path("revision").asText() + " is not its pin " + revisions.get(id));
            }
            if (f.path("files").isEmpty() || f.path("boundary").asText().isBlank()) {
                problems.add(id + ": names no source files or no boundary");
            }
            for (JsonNode file : f.path("files")) {
                if (!file.path("blob").asText().matches("[0-9a-f]{40}")) {
                    problems.add(id + ": file " + file.path("path").asText() + " has no git blob id");
                }
            }
            for (JsonNode x : f.path("excluded")) {
                if (x.path("what").asText().isBlank() || x.path("reason").asText().isBlank()) {
                    problems.add(id + ": an exclusion needs what and reason");
                }
            }
            Map<String, Integer> counts = new TreeMap<>();
            for (JsonNode x : f.path("fields")) {
                String fid = x.path("id").asText();
                if (!seen.add(fid)) {
                    problems.add("duplicate field " + fid);
                }
                if (!fid.equals(id + ":" + x.path("kind").asText() + ":" + x.path("name").asText())) {
                    problems.add(fid + ": id is not <foundation>:<kind>:<name>");
                }
                if (x.path("locator").asText().isBlank()) {
                    problems.add(fid + ": no locator");
                }
                counts.merge(x.path("kind").asText(), 1, Integer::sum);
            }
            Map<String, Integer> declared = new TreeMap<>();
            f.path("counts").fields().forEachRemaining(e -> declared.put(e.getKey(), e.getValue().asInt()));
            if (!declared.equals(counts) || f.path("total").asInt() != f.path("fields").size()) {
                problems.add(id + ": counts " + declared + " / total " + f.path("total").asInt() + " do not match its fields " + counts);
            }
        }
        if (!foundations.equals(TIER_ONE)) {
            problems.add("foundations " + foundations + " are not the Tier 1 foundations " + TIER_ONE);
        }
        return problems;
    }

    @Test
    void theCommittedInventoryIsWellFormedAndPinned() {
        JsonNode inv = inventory();
        assertThat(problems(inv)).isEmpty();
        // corruption controls: a duplicated field, a count that disagrees, a revision that is not the pin
        JsonNode dup = inv.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) dup.path("foundations").get(0).path("fields")).add(dup.path("foundations").get(0).path("fields").get(0).deepCopy());
        assertThat(problems(dup)).anyMatch(p -> p.contains("duplicate field"));
        JsonNode dropped = inv.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) dropped.path("foundations").get(1).path("fields")).remove(0);
        assertThat(problems(dropped)).anyMatch(p -> p.contains("do not match its fields"));
        JsonNode moved = inv.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) moved.path("foundations").get(2)).put("revision", "main");
        assertThat(problems(moved)).anyMatch(p -> p.contains("is not its pin"));
    }

    /**
     * Gated: {@code -Djournal.foundation.sources=<dir>} holds {@code <foundation id>/<path>} copies of the pinned files.
     * Their blob ids must equal the inventory's; the derivation must then equal the committed inventory exactly.
     */
    @Test
    void theInventoryIsExactlyWhatThePinnedSourcesDeclare() throws IOException {
        String dir = System.getProperty("journal.foundation.sources");
        Assumptions.assumeTrue(dir != null && !dir.isBlank(), "no -Djournal.foundation.sources");
        Path root = Path.of(dir);
        List<FoundationFields.Extraction> extractions = List.of(FoundationFields.otel(root.resolve(FoundationFields.OTEL)),
                FoundationFields.openInference(root.resolve(FoundationFields.OI)), FoundationFields.weave(root.resolve(FoundationFields.WEAVE)));
        String derived = MappingCatalogTest.PRETTY.writeValueAsString(FoundationFields.document(extractions, revisions())) + "\n";
        if ("true".equals(System.getProperty("journal.foundation.write"))) {
            Files.writeString(FILE, derived, StandardCharsets.UTF_8);
        }
        JsonNode committed = inventory();
        for (FoundationFields.Extraction e : extractions) {
            for (JsonNode f : committed.path("foundations")) {
                if (f.path("foundation").asText().equals(e.foundation())) {
                    Map<String, String> blobs = new TreeMap<>();
                    f.path("files").forEach(x -> blobs.put(x.path("path").asText(), x.path("blob").asText()));
                    Map<String, String> supplied = new TreeMap<>();
                    e.files().forEach(x -> supplied.put(x.path(), x.blob()));
                    assertThat(supplied).as("supplied files of " + e.foundation() + " are the pinned blobs").isEqualTo(blobs);
                }
            }
        }
        assertThat(FoundationComparisonTest.MAPPER.readTree(derived)).isEqualTo(committed);
    }
}
