package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema paths resolve against the embedded closure, and saved values are checked against declared types and enums. */
class SchemaCatalogTest {

    @Test
    void pathsResolveAndWrongPathsFailLoudly(@TempDir Path work) {
        SchemaCatalog c = open(copy(CODEX, work)).catalog;
        assertThat(c.resolve("tool_call.tool_call_uid").type()).isEqualTo("string_t");
        assertThat(c.resolve("model_response.content_blocks[].block_index").type()).isEqualTo("integer_t");
        assertThat(c.resolve("prompt_turn.journal.run_uid").type()).isEqualTo("string_t");
        assertThat(c.resolve("*.source_ref.record_index").type()).isEqualTo("integer_t");
        assertThatThrownBy(() -> c.resolve("tool_call.tool_identifier")).hasMessageContaining("unknown attribute tool_identifier");
        assertThatThrownBy(() -> c.resolve("tool_call.tool_call_uid.deeper")).hasMessageContaining("is a scalar");
        assertThatThrownBy(() -> c.resolve("tool_call.source_ref[]")).hasMessageContaining("is not an array");
        assertThatThrownBy(() -> c.resolve("widget.anything")).hasMessageContaining("unknown class widget");
        assertThatThrownBy(() -> c.resolve("*.nowhere")).hasMessageContaining("unknown attribute nowhere");
    }

    @Test
    void savedValuesAreCheckedAgainstDeclaredTypesEnumsAndRequirements(@TempDir Path work) {
        OcsfPackage p = open(copy(CODEX, work));
        SchemaCatalog.Definition toolCall = p.catalog.classNamed("tool_call");
        Json event = NeutralJson.parse("{\"class_uid\":90111003,\"category_uid\":90111,\"activity_id\":1,\"type_uid\":9011100301,\"severity_id\":7,"
                + "\"time\":\"not-a-timestamp\",\"metadata\":{\"version\":\"1.9.0\",\"product\":{\"name\":\"x\"}},\"journal\":{\"run_uid\":1},"
                + "\"session_uid\":\"s\",\"tool_call_uid\":true,\"unmapped\":{\"anything\":\"goes\"},\"novel\":1}");
        List<SchemaCatalog.Finding> findings = p.catalog.check(event, toolCall);
        assertThat(findings).extracting(f -> f.kind() + " " + f.path())
                .contains("ENUM_VALUE_UNKNOWN $.severity_id", "TYPE_MISMATCH $.time", "TYPE_MISMATCH $.journal.run_uid",
                        "TYPE_MISMATCH $.tool_call_uid", "UNKNOWN_ATTRIBUTE $.novel", "MISSING_REQUIRED $.source_ref")
                .doesNotContain("UNKNOWN_ATTRIBUTE $.unmapped.anything");
        assertThat(findings).extracting(SchemaCatalog.Finding::kind).contains(SchemaCatalog.Finding.Kind.MISSING_REQUIRED);
    }

    @Test
    void theDefaultSemanticContractResolvesAgainstEveryFixture(@TempDir Path work) {
        for (String name : List.of(OcsfFixtures.CODEX, OcsfFixtures.CHILD)) {
            OcsfPackage p = open(copy(name, work.resolve(name)));
            assertThat(SemanticContract.validate(SemanticContract.loadDefault(), p.catalog)).as(name).isEmpty();
            for (OcsfPackage child : p.children) {
                // a child file declares only the classes its events use; targets under the other pinned classes are empty views, not defects
                List<String> absent = child.manifest.classTable().keySet().stream().filter(c -> child.catalog.classNamed(c) == null).toList();
                assertThat(absent).as("the child fixture uses fewer classes than the pin table").isNotEmpty();
                assertThat(SemanticContract.validate(SemanticContract.loadDefault(), child.catalog, absent)).as(name + " child").isEmpty();
            }
        }
    }
}
