package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_CODEX_MCP;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_FEATURES;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The independent oracle's controls for identity, lifecycle and classification, each one corruption of a committed
 * sanitized live recording: a stream-local item given a response identity or a model-facing basis, a standalone
 * operation given a call identity, a built-in kind assigned to an MCP tool without a reviewed binding, and invocations or
 * operations invented from the text of one reported shell command. The unchanged recordings are accepted beside them.
 */
class IdentityClassificationControlTest {

    static final String MAIN = "main/stream.jsonl";

    @Test
    void theUnchangedRecordingsAreAccepted(@TempDir Path work) {
        OracleReport mcp = verify(copy(LIVE_CODEX_MCP, work.resolve("mcp")));
        assertThat(mcp.outcome("request.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        OracleReport commands = verify(copy(LIVE_CODEX, work.resolve("commands")));
        assertThat(commands.outcome("operation.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        OracleReport claude = verify(copy(LIVE_FEATURES, work.resolve("claude")));
        assertThat(claude.outcome("population.requests", MAIN)).isEqualTo(Outcome.VERIFIED);
    }

    /**
     * Reproduced on the unchanged a7673e3 oracle, which accepted this package: it compared response_uid only where the
     * profile states one, and the Codex profile never does. A stream-local item names no response, so a response identity
     * on it is fabricated (RULE-44).
     */
    @Test
    void aForgedResponseIdentityOnAStreamLocalToolItemIsADefect(@TempDir Path work) {
        Path fixture = copy(LIVE_CODEX_MCP, work);
        editEvents(pkg(fixture), events -> obj(at(events, "tool_call", 4, 4)).members().put("response_uid", str("item_1")));
        OracleReport report = verify(fixture);
        assertThat(report.outcome("request.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("request.values", MAIN).detail()).contains("4:item_1 response_uid item_1 present, but the source names no response for this item");
    }

    @Test
    void aModelFacingIdentityBasisOnAStreamLocalToolItemIsADefect(@TempDir Path work) {
        Path fixture = copy(LIVE_CODEX_MCP, work);
        editEvents(pkg(fixture), events -> obj(at(events, "tool_call", 4, 4)).members().put("identity_basis_id", num("1")));
        assertThat(verify(fixture).check("request.values", MAIN).detail()).contains("4:item_1 identity_basis_id 1 != 2");
    }

    @Test
    void aCallIdentityGivenToAStandaloneOperationIsADefect(@TempDir Path work) {
        Path fixture = copy(LIVE_CODEX, work);
        editEvents(pkg(fixture), events -> obj(at(events, "tool_call", 3, 4)).members().put("tool_call_uid", str("item_1")));
        OracleReport report = verify(fixture);
        assertThat(report.outcome("operation.values", MAIN)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(report.check("operation.values", MAIN).detail()).contains("tool_call_uid");
    }

    /** No reviewed binding names this MCP server and tool, so its kind is Other; a built-in kind is the bare-name inference RULE-45 forbids. */
    @Test
    void aBuiltInKindGivenToAnMcpToolWithoutAReviewedBindingIsADefect(@TempDir Path work) {
        Path fixture = copy(LIVE_CODEX_MCP, work);
        editEvents(pkg(fixture), events -> obj(at(events, "tool_call", 4, 4)).members().put("tool_kind_id", num("6")));
        assertThat(verify(fixture).check("request.values", MAIN).detail()).contains("4:item_1 tool_kind_id 6 != 99 (the profile's family or namespace reading)");
    }

    /**
     * One reported Bash invocation carries the command text. An invocation of the command inside it, or an operation for
     * it, is not something the source reported: either added to the package is a population defect.
     */
    @Test
    void anInvocationOrOperationInventedFromAShellCommandsTextIsAPopulationDefect(@TempDir Path work) {
        Path invocation = copy(LIVE_FEATURES, work.resolve("invocation"));
        editEvents(pkg(invocation), events -> {
            Json bash = at(events, "tool_call", 1, 16);
            Json inner = OcsfFixtures.copy(bash);
            obj(inner).members().put("tool_call_uid", str(bash.member("tool_call_uid").text() + "-cat"));
            obj(inner).members().put("tool_name", str("cat"));
            events.add(events.indexOf(bash) + 1, inner);
        });
        OracleReport invented = verify(invocation);
        assertThat(invented.outcome("population.requests", MAIN)).isEqualTo(Outcome.DEFECTIVE);

        Path operation = copy(LIVE_CODEX, work.resolve("operation"));
        editEvents(pkg(operation), events -> {
            Json printf = at(events, "tool_call", 3, 4);
            Json split = OcsfFixtures.copy(printf);
            obj(split.member("tool_operation")).members().put("uid", str("item_1-redirect"));
            obj(split.member("tool_operation")).members().put("command_line", str("printf alpha"));
            events.add(events.indexOf(printf) + 1, split);
        });
        OracleReport split = verify(operation);
        assertThat(split.outcome("population.operations", MAIN)).isEqualTo(Outcome.DEFECTIVE);
    }
}
