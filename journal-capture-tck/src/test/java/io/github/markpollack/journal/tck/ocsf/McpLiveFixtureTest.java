package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_AGY_MCP_DENIED;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_MCP;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.eventLines;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live MCP recordings of the 2026-10-23 close-out, made with the Java MCP SDK stdio test server (tools echo_record and
 * always_fails), checked against the raw stream first and the persisted events second. What a CLI's stream projects is
 * what is asserted: neither stream reports MCP initialization, discovery, resources, prompts, progress or cancellation,
 * and none is expected.
 *
 * <p>Claude Code 2.1.304 (records 1-15): init lists the server {@code journal_echo} as connected with the two prefixed
 * tool names; a ToolSearch call (records 5, 6) loads the deferred tools; then {@code echo_record {text alpha, n 1}}
 * (8, 9), {@code echo_record {text beta, n 2}} (10, 11) and {@code always_fails {}} (12, 13, is_error true); the result
 * (15) succeeds. Each echo result's content is compact JSON equal to the server's structuredContent, which tool_use_result
 * also carries; the server's own text content (with spaces) does not appear in the stream.
 *
 * <p>Antigravity 1.4.6 in request-review mode (records 1-6): the MCP call is the dispatcher tool {@code call_mcp_tool}
 * whose parameters name the server, the tool and the arguments; it goes ACTIVE (4), then DONE without output or error (5);
 * the result (6) reports {@code denied_actions [{action mcp}]} and an empty response.
 */
class McpLiveFixtureTest {

    static final String STREAM = "main/stream.jsonl";

    @Test
    void theClaudeRecordingCarriesThreeMcpCallsWithStructuredResultsAndTheToolError(@TempDir Path work) throws IOException {
        List<Json> raw = records(LIVE_MCP);
        assertThat(raw.get(0).member("mcp_servers").elements()).extracting(m -> m.member("name").text()).containsExactly("journal_echo");
        List<Json> uses = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<Json> results = new ArrayList<>();
        for (Json r : raw) {
            for (Json b : r.path("message", "content") instanceof Json.JArray a ? a.elements() : List.<Json>of()) {
                if ("tool_use".equals(SchemaCatalog.text(b, "type")) && SchemaCatalog.text(b, "name").startsWith("mcp__")) {
                    uses.add(b);
                    ids.add(SchemaCatalog.text(b, "id"));
                }
                if ("tool_result".equals(SchemaCatalog.text(b, "type")) && ids.contains(SchemaCatalog.text(b, "tool_use_id"))) {
                    results.add(r);
                }
            }
        }
        assertThat(uses).extracting(u -> u.member("name").text()).containsExactly("mcp__journal_echo__echo_record", "mcp__journal_echo__echo_record", "mcp__journal_echo__always_fails");
        assertThat(uses).extracting(u -> NeutralJson.write(u.member("input"))).containsExactly("{\"text\":\"alpha\",\"n\":1}", "{\"text\":\"beta\",\"n\":2}", "{}");
        assertThat(uses).extracting(u -> u.member("id").text()).doesNotHaveDuplicates();
        assertThat(results).hasSize(3);
        assertThat(results.get(0).path("tool_use_result", "structuredContent", "echo").text()).isEqualTo("alpha");
        assertThat(results.get(1).path("tool_use_result", "structuredContent", "echo").text()).isEqualTo("beta");
        Json failed = results.get(2).path("message", "content").elements().get(0);
        assertThat(failed.member("is_error").scalarText()).isEqualTo("true");
        assertThat(failed.member("content").text()).isEqualTo("always_fails: deliberate test failure");

        OracleReport report = verify(copy(LIVE_MCP, work));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        for (String property : List.of("request.values", "result.values", "result.owner")) {
            assertThat(report.outcome(property, STREAM)).as(property).isEqualTo(Outcome.VERIFIED);
        }
        List<Json> events = eventLines(pkg(copy(LIVE_MCP, work.resolve("events"))).resolve("events.jsonl"));
        Json first = at(events, "tool_call", 1, 8);
        assertThat(first.member("tool_name").text()).isEqualTo("mcp__journal_echo__echo_record");
        assertThat(first.member("tool_namespace")).as("the namespace is not parsed out of the name; no reviewed binding exists").isNull();
        assertThat(first.member("tool_kind_id").scalarText()).isEqualTo("99");
        Json echoed = at(events, "tool_call", 2, 9);
        assertThat(echoed.path("tool_result", "structured_result", "structuredContent", "n").scalarText()).isEqualTo("1");
        assertThat(echoed.member("status_id").scalarText()).as("no is_error reported: success is not derivable").isEqualTo("0");
        Json error = at(events, "tool_call", 2, 13);
        assertThat(error.member("status_id").scalarText()).isEqualTo("2");
        assertThat(error.path("tool_result", "error_reported").scalarText()).isEqualTo("true");
    }

    /** Each corruption leaves the native bytes alone and changes one MCP fact of the saved package; the oracle names it. */
    @Test
    void aSwappedEchoResultADroppedStructuredResultAnErrorReadAsSuccessOrAParsedNamespaceIsDetected(@TempDir Path work) {
        Path swapped = copy(LIVE_MCP, work.resolve("swapped"));
        editEvents(pkg(swapped), events -> {
            Json.JObject alpha = obj(at(events, "tool_call", 2, 9));
            Json.JObject beta = obj(at(events, "tool_call", 2, 11));
            Json a = alpha.member("tool_result");
            alpha.members().put("tool_result", beta.member("tool_result"));
            beta.members().put("tool_result", a);
        });
        assertThat(verify(swapped).check("result.values", STREAM).detail()).as("equal shapes never pair a result with another call").contains("9:").contains("11:");
        Path dropped = copy(LIVE_MCP, work.resolve("dropped"));
        editEvents(pkg(dropped), events -> obj(at(events, "tool_call", 2, 11).member("tool_result")).members().remove("structured_result"));
        assertThat(verify(dropped).outcome("result.values", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        Path success = copy(LIVE_MCP, work.resolve("success"));
        editEvents(pkg(success), events -> obj(at(events, "tool_call", 2, 13)).members().put("status_id", num("1")));
        assertThat(verify(success).outcome("result.values", STREAM)).as("a reported tool error is not a success").isEqualTo(Outcome.DEFECTIVE);
        Path namespace = copy(LIVE_MCP, work.resolve("namespace"));
        editEvents(pkg(namespace), events -> obj(at(events, "tool_call", 1, 8)).members().put("tool_namespace", str("journal_echo")));
        assertThat(verify(namespace).check("request.values", STREAM).detail()).contains("tool_namespace journal_echo != absent");
    }

    @Test
    void theAntigravityRecordingShowsTheMcpCallSoftDeniedAtRunScope(@TempDir Path work) throws IOException {
        List<Json> raw = records(LIVE_AGY_MCP_DENIED);
        Json active = raw.get(3).member("step_update");
        assertThat(active.member("state").text()).isEqualTo("ACTIVE");
        assertThat(active.member("tool_name").text()).isEqualTo("call_mcp_tool");
        assertThat(NeutralJson.write(active.path("tool_info", "parameters"))).isEqualTo("{\"Arguments\":{\"n\":1,\"text\":\"alpha\"},\"ServerName\":\"journal_echo\",\"ToolName\":\"echo_record\"}");
        Json done = raw.get(4).member("step_update");
        assertThat(done.member("state").text()).isEqualTo("DONE");
        assertThat(done.path("tool_info", "output")).isNull();
        assertThat(done.path("tool_info", "error")).isNull();
        assertThat(raw.get(5).path("result", "denied_actions").elements().get(0).member("action").text()).isEqualTo("mcp");

        OracleReport report = verify(copy(LIVE_AGY_MCP_DENIED, work));
        assertThat(report.defects()).as(report.toString()).isEmpty();
        List<Json> events = eventLines(pkg(copy(LIVE_AGY_MCP_DENIED, work.resolve("events"))).resolve("events.jsonl"));
        Json started = at(events, "tool_call", 4, 4);
        assertThat(started.member("tool_name").text()).as("the dispatcher's own name; the MCP tool stays in its arguments").isEqualTo("call_mcp_tool");
        assertThat(started.path("arguments", "ToolName").text()).isEqualTo("echo_record");
        Json result = at(events, "tool_call", 2, 5);
        assertThat(result.member("status_id").scalarText()).as("DONE without output or error: the outcome is not derivable").isEqualTo("0");
        assertThat(result.member("tool_result")).isNull();
        Json decision = at(events, "prompt_turn", 2, 6).member("permission_decisions").elements().get(0);
        assertThat(decision.member("scope_id").scalarText()).isEqualTo("4");
        assertThat(decision.path("attribution", "outcome_id").scalarText()).isEqualTo("3");
    }

    @Test
    void aDeniedMcpCallReadAsSuccessOrTiedToItsStepOrRenamedAfterItsArgumentIsDetected(@TempDir Path work) {
        Path success = copy(LIVE_AGY_MCP_DENIED, work.resolve("success"));
        editEvents(pkg(success), events -> obj(at(events, "tool_call", 2, 5)).members().put("status_id", num("1")));
        assertThat(verify(success).outcome("result.values", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        Path attributed = copy(LIVE_AGY_MCP_DENIED, work.resolve("attributed"));
        editEvents(pkg(attributed), events -> {
            Json.JObject att = obj(at(events, "prompt_turn", 2, 6).member("permission_decisions").elements().get(0).member("attribution"));
            att.members().put("outcome_id", num("1"));
            att.members().put("candidates", new Json.JArray(List.of(str("2"))));
        });
        assertThat(verify(attributed).outcome("decision.values", STREAM)).as("a run-scope category names no step").isEqualTo(Outcome.DEFECTIVE);
        Path renamed = copy(LIVE_AGY_MCP_DENIED, work.resolve("renamed"));
        editEvents(pkg(renamed), events -> obj(at(events, "tool_call", 4, 4)).members().put("tool_name", str("echo_record")));
        assertThat(verify(renamed).check("request.values", STREAM).detail()).contains("tool_name");
    }

    private static List<Json> records(String fixture) throws IOException {
        List<Json> out = new ArrayList<>();
        Path stream;
        try {
            stream = Path.of(McpLiveFixtureTest.class.getResource("/ocsf/fixtures/" + fixture + "/native/stream.jsonl").toURI());
        }
        catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        for (String line : Files.readAllLines(stream)) {
            out.add(NeutralJson.parse(line));
        }
        return out;
    }
}
