package io.github.markpollack.journal.tck.ocsf;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** A sealed Claude one-shot archive with one MCP call, read independently ({@code ocsf/terminal-mcp}). */
class TerminalMcpRelationTest {
    private static final Path CASES = Path.of("src/test/resources/ocsf/terminal-mcp");

    @Test void anMcpCallSeenByTheStreamAndTheSessionFileIsAccepted() throws IOException {
        OcsfReaderTool.Acceptance a = TerminalChildScopeTest.acceptance(CASES, "claude-mcp");
        assertThat(a.verdict()).as(a.toString()).isEqualTo("ACCEPTED");
    }

    /**
     * The unfixed writer called the consolidated MCP result "One richer" for its stream observation's basis-only
     * {@code mcp} member; the oracle derives the relation from facts only, and disagrees at that event alone.
     */
    @Test void theRelationTheUnfixedWriterDerivedFromProvenanceIsDefective() throws IOException {
        OcsfReaderTool.Acceptance a = TerminalChildScopeTest.acceptance(CASES, "claude-mcp-aa75c02");
        assertThat(a.verdict()).isEqualTo("DEFECTIVE");
        assertThat(a.reasons()).hasSize(2).allMatch(r -> r.matches(
                "NORMALIZATION assembly\\.evidence\\.value \\[main/stdout\\.bin\\] DEFECTIVE: (\\d+)/assembly/relation_class(_id)? at main/stdout\\.bin:line=\\1#/assembly/relation_class(_id)?: .*"));
    }
}
