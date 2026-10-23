package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the writer classifies a reported tool activity: from the declared family table, from a reviewed binding of an MCP
 * tool's exact namespace and name, or from Claude's declared exact-name table, never from a bare name that coincides with
 * another namespace's tool and never from the text of a command a reported invocation carries.
 */
class ToolClassificationTest {

    /**
     * No reviewed namespace binding exists in the bundled Codex exec contract, so every MCP tool is Other. A binding of an
     * exact namespace and tool name, once reviewed into the contract, gives its kind to exactly that pair: the same tool
     * name under another namespace, another name in the same namespace and an unknown namespace stay Other. (Adding a
     * binding changes the relevant content of the claim codex.exec-stream.mcp-tool-call, which names this table, so an
     * approval does not carry over it until the binding is reviewed.)
     */
    @Test
    void onlyAReviewedExactNamespaceAndNameBindingGivesAnMcpToolAKind() {
        MappingContracts bundled = new MappingContracts();
        assertThat(bundled.contract(CodexStreamMapper.CONTRACT).path("namespace_kind_bindings", "bindings").elements()).as("no binding has been reviewed").isEmpty();
        assertThat(bundled.familyKind(CodexStreamMapper.CONTRACT, "mcp_tool_call", "local", "exec").id()).isEqualTo(99);

        MappingContracts reviewed = new MappingContracts();
        Jv.Arr bindings = (Jv.Arr) reviewed.contract(CodexStreamMapper.CONTRACT).path("namespace_kind_bindings", "bindings");
        bindings.add(new Jv.Obj().put("namespace", "local").put("tool", "exec").put("kind", 6));
        assertThat(reviewed.familyKind(CodexStreamMapper.CONTRACT, "mcp_tool_call", "local", "exec").id()).as("the reviewed pair").isEqualTo(6);
        assertThat(reviewed.familyKind(CodexStreamMapper.CONTRACT, "mcp_tool_call", "elsewhere", "exec").id()).as("the same name in another namespace").isEqualTo(99);
        assertThat(reviewed.familyKind(CodexStreamMapper.CONTRACT, "mcp_tool_call", "local", "exec_command").id()).as("another name in the namespace").isEqualTo(99);
        assertThat(reviewed.familyKind(CodexStreamMapper.CONTRACT, "mcp_tool_call", null, "exec").id()).as("no namespace reported").isEqualTo(99);
        assertThat(reviewed.familyKind(CodexStreamMapper.CONTRACT, "mcp_tool_call", "local", "exec").rule()).isEqualTo("codex.stream.mcp_namespace_kind.v1");
    }

    /** Claude's table is keyed by the exact reported name: an MCP tool reported as mcp__server__Bash is not the built-in Bash. */
    @Test
    void claudesExactNameTableDoesNotGiveAnMcpToolTheKindOfABuiltInWithTheSameShortName() {
        MappingContracts c = new MappingContracts();
        assertThat(c.toolKind(ClaudeStreamMapper.CONTRACT, "Bash")).isEqualTo(6);
        assertThat(c.toolKind(ClaudeStreamMapper.CONTRACT, "mcp__local__Bash")).isEqualTo(99);
        assertThat(c.toolKind(ClaudeStreamMapper.CONTRACT, "mcp__local__Read")).isEqualTo(99);
    }

    /**
     * A command that runs grep through a pipe into a Python interpreter is one reported invocation on each CLI: the writer
     * records exactly the reported tool events, with the command text verbatim, and none named after a command inside it.
     */
    @Test
    void aShellCommandWithGrepAPipeAndPythonIsOneReportedInvocation(@TempDir Path work) throws IOException {
        List<Jv> codex = events("codex-command-pipeline-synthetic", work.resolve("codex"));
        List<Jv> codexTools = tools(codex);
        assertThat(codexTools).as("the started and the completed operation of item_0, nothing else").hasSize(2)
                .allSatisfy(e -> {
                    assertThat(e.path("tool_operation", "uid").text()).isEqualTo("item_0");
                    assertThat(e.path("tool_operation", "command_line").text()).contains("grep -rn TODO src | python3 -c");
                    assertThat(e.member("tool_call_uid")).isNull();
                    assertThat(e.path("tool_operation", "argv")).isNull();
                });
        List<Jv> claude = events("claude-bash-pipeline-synthetic", work.resolve("claude"));
        List<Jv> claudeTools = tools(claude);
        assertThat(claudeTools).as("the Bash request and its result, nothing else").hasSize(2)
                .allSatisfy(e -> assertThat(e.member("tool_call_uid").text()).isEqualTo("toolu_SYNTHETIC_pipeline"));
        assertThat(claudeTools.get(0).member("tool_name").text()).isEqualTo("Bash");
        assertThat(claudeTools.get(0).member("tool_operation")).isNull();
        for (Jv e : concat(codexTools, claudeTools)) {
            Jv name = e.member("tool_name");
            assertThat(name == null ? null : name.text()).as("no tool named after a command inside the shell text").isNotIn("grep", "python3", "python");
        }
    }

    static List<Jv> events(String example, Path dir) throws IOException {
        Jv ex = Jv.parse(Files.readString(SemanticExampleTest.EXAMPLES.resolve(example + ".json"), StandardCharsets.UTF_8));
        Jv nat = ex.member("native");
        return SemanticExampleTest.write(nat, SemanticExampleTest.nativeBytes(nat, dir), dir);
    }

    static List<Jv> tools(List<Jv> events) {
        List<Jv> out = new ArrayList<>();
        for (Jv e : events) {
            if ("Tool Call".equals(e.member("class_name") == null ? null : e.member("class_name").text())) {
                out.add(e);
            }
        }
        return out;
    }

    static List<Jv> concat(List<Jv> a, List<Jv> b) {
        List<Jv> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
