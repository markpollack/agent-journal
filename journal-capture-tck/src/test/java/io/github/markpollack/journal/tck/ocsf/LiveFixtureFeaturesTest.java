package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_CANCELLED;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_CODEX_CANCELLED;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_DENIAL;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_FEATURES;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.LIVE_SPAWN;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feature families the ACCEPT-1 work order names, asserted on the sanitized live recordings (recorded replay of
 * live Claude Code 2.1.304 and Codex CLI 0.160.1 streams; the live captures themselves are private steward evidence).
 * Each assertion names an occurrence that actually happened in the stream and the saved fact that represents it;
 * nothing here claims a feature from the prompt that asked for it. Families the recordings do not exhibit are stated
 * as such in the class-level notes of each test, never asserted.
 */
class LiveFixtureFeaturesTest {

    static final String MAIN = "main/stream.jsonl";
    static final String CHILD = "child:" + LIVE_SPAWN + "/stream.jsonl";

    /** Prompt/response, tools, children, failure, usage/cost and unknown content on the Claude feature recording. */
    @Test
    void theClaudeFeatureRecordingExhibitsToolsChildrenFailureUsageAndRetainedFamilies(@TempDir Path work) {
        Path fixture = copy(LIVE_FEATURES, work);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.VERIFIED);
        OcsfPackage p = open(fixture);
        assertThat(p.manifest.cliVersion()).isEqualTo("2.1.304");
        assertThat(p.manifest.compatibility("stream.jsonl").member("status").text()).as("2.1.304 on route stdout is a tested version of the entry").isEqualTo("tested");

        // tools: exact structured arguments, repeated names, identical results under distinct owners, three requests in one response
        List<Event> requests = p.events("tool_call", 1);
        assertThat(requests.stream().map(e -> e.text("tool_name")).toList()).containsExactly("Write", "Write", "Read", "Read", "Bash", "Agent", "Read", "Edit");
        Event write = requests.get(0);
        assertThat(write.text("tool_call_uid")).isEqualTo("toolu_01LqFQri46W5W1yjZRqLKDVB");
        assertThat(write.text("arguments", "file_path")).isEqualTo("/work/alpha.txt");
        assertThat(write.text("arguments", "content")).isEqualTo("alpha\n");
        assertThat(requests.get(2).text("response_uid")).as("two Reads and the Bash were issued in one response").isEqualTo(requests.get(3).text("response_uid")).isEqualTo(requests.get(4).text("response_uid"));
        assertThat(report.check("population.requests", MAIN).detail()).isEqualTo("8 expected, 8 observed");
        assertThat(report.outcome("request.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        List<Event> results = p.events("tool_call", 2);
        Map<String, Event> byId = new java.util.LinkedHashMap<>();
        results.forEach(e -> byId.put(e.text("tool_call_uid"), e));
        assertThat(byId.get("toolu_01GoJ3J6vriveqPgCVibjQeT").text("tool_result", "data")).isEqualTo("1\tbeta\n2\t");
        assertThat(report.outcome("result.owner", MAIN)).isEqualTo(Outcome.VERIFIED);

        // failure: the Bash result reports an error and the saved status says so, with its basis
        Event bash = byId.get("toolu_01N38UBNkmXvoMBRjQgL3ZdD");
        assertThat(bash.get("tool_result", "error_reported").scalarText()).isEqualTo("true");
        assertThat(bash.integer("status_id")).isEqualTo(2);
        assertThat(bash.text("tool_result", "data")).startsWith("Exit code 1\ncat: missing-file.txt: No such file or directory");
        assertThat(bash.text("permission_decision", "reason_type")).isEqualTo("subcommandResults");

        // children: the Agent call spawned a child core with its own declarations; its Read result is identical in content to the
        // parent's beta Read under a distinct id and owner, and both stay distinct
        assertThat(p.children).hasSize(1);
        OcsfPackage child = p.children.get(0);
        assertThat(child.label()).isEqualTo("child:" + LIVE_SPAWN);
        assertThat(child.manifest.parent().member("spawn_tool_call_uid").text()).isEqualTo(LIVE_SPAWN);
        assertThat(child.declarations.compatibility("stream.jsonl")).as("the child file declares the selection too").isNotEmpty();
        Event childRead = child.events("tool_call", 2).stream().filter(e -> "toolu_01RnNHTYgz6yym3A1m1sYZ6S".equals(e.text("tool_call_uid"))).findFirst().orElseThrow();
        assertThat(childRead.text("tool_result", "data")).isEqualTo("1\tbeta\n2\t");
        assertThat(report.outcome("package.children", "main")).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("result.owner", CHILD)).isEqualTo(Outcome.VERIFIED);
        assertThat(child.events("tool_call", 1).stream().map(e -> e.text("tool_name")).toList()).containsExactly("Read", "SubagentHandback");

        // usage and cost: provisional per response, final run and model scope on the result line, the cost as a total
        assertThat(report.check("population.usage", MAIN).detail()).endsWith("observed").doesNotStartWith("0 ");
        assertThat(report.outcome("usage.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        Event turn = p.events("prompt_turn", 2).get(0);
        assertThat(turn.get("cost_observation", "total_cost").scalarText()).isEqualTo("0.009542775");
        assertThat(turn.text("cost_observation", "scope")).isEqualTo("Run");
        assertThat(turn.text("cost_observation", "cost_basis")).isNotNull();
        assertThat(report.outcome("cost.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(turn.get("permission_decisions")).as("no run-scope denial on this run (the Edit ran under auto permission mode)").isNull();

        // unknown and rich content retained whole: rate limit, running thinking estimates, task start and patch; reasoning blocks kept with their data
        List<String> families = new ArrayList<>();
        for (Event e : p.events("source_record", 1)) {
            families.add(e.text("record_type"));
        }
        assertThat(families).contains("rate_limit_event", "system/thinking_tokens", "system/task_started", "system/task_updated")
                .doesNotContain("system/task_progress", "system/task_notification");
        // the task's usage and the Agent result's usage are child-run accounting at the spawning call (D1), whole records kept in data
        assertThat(p.events("session_context", 3)).extracting(e -> e.text("context_kind") + " " + e.text("tool_call_uid") + " " + e.recordIndex())
                .containsExactly("system/task_progress " + LIVE_SPAWN + " 25", "system/task_progress " + LIVE_SPAWN + " 28", "system/task_notification " + LIVE_SPAWN + " 32",
                        "user/tool_result " + LIVE_SPAWN + " 33", "result/modelUsage null 50");
        // ACCT-1: the result record's per-model running cost is its own accounting event, and the Agent result's envelope
        // state and duration ride on the child-run accounting event (never on the outer tool call)
        Event modelCosts = p.events("session_context", 3).stream().filter(e -> "result/modelUsage".equals(e.text("context_kind"))).findFirst().orElseThrow();
        assertThat(modelCosts.elements("cost_observations")).singleElement().satisfies(c -> {
            assertThat(c.member("total_cost").scalarText()).isEqualTo("0.009542775");
            assertThat(c.member("scope_id").scalarText()).isEqualTo("6");
            assertThat(SchemaCatalog.text(c.member("model_ref"), "name")).isEqualTo("claude-haiku-5-5");
        });
        Event agent = p.events("session_context", 3).stream().filter(e -> "user/tool_result".equals(e.text("context_kind"))).findFirst().orElseThrow();
        assertThat(agent.text("reported_state")).isEqualTo("completed");
        assertThat(agent.get("duration").scalarText()).isEqualTo("3973");
        assertThat(p.events("prompt_turn", 2).get(0).get("duration").scalarText()).isEqualTo("22832");
        assertThat(report.outcome("attribute.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("context.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.outcome("whole_record.coverage", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(p.events("model_response", 1).stream().anyMatch(e -> e.elements("content_blocks").stream().anyMatch(b -> "thinking".equals(SchemaCatalog.text(b, "block_type"))))).isTrue();
    }

    /** UC3-AC13 on the stream route: the one prompt the stream reports is the sub-agent's, in the child scope, with its exact text. */
    @Test
    void theSubAgentPromptIsTheChildScopesVendorReportedPrompt(@TempDir Path work) {
        Path fixture = copy(LIVE_FEATURES, work);
        OracleReport report = verify(fixture);
        assertThat(report.outcome("population.prompts", MAIN)).as("the main thread's -p prompt is not echoed").isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(report.check("population.prompts", CHILD).detail()).isEqualTo("1 expected, 1 observed");
        assertThat(report.outcome("prompt.values", CHILD)).isEqualTo(Outcome.VERIFIED);
        OcsfPackage child = open(fixture).children.get(0);
        Event prompt = child.events("prompt_turn", 1).get(0);
        assertThat(prompt.recordIndex()).isEqualTo(24);
        assertThat(prompt.text("content_blocks")).isNull();
        assertThat(prompt.elements("content_blocks").get(0).member("text").text()).startsWith("Read the file /work/beta.txt and report its content back");
        assertThat(prompt.get("turn_uid")).as("the stream names no turn id; none is invented").isNull();
        assertThat(prompt.text("journal", "rule_uid")).isEqualTo("claude.stream.user.prompt->prompt_turn.submitted");
    }

    /** Permission: an observed rejection at tool-call scope and the run-scope denial that names the same call; the Write before it was accepted by rule. */
    @Test
    void theDenialRecordingExhibitsAnObservedRejectionAtBothScopes(@TempDir Path work) {
        Path fixture = copy(LIVE_DENIAL, work);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        OcsfPackage p = open(fixture);
        assertThat(p.manifest.text("run", "launch_settings", "allowed_tools")).isNull();
        Event edit = p.events("tool_call", 2).stream().filter(e -> "toolu_01N8xZH4f5KoLaQhkgQLfVLi".equals(e.text("tool_call_uid"))).findFirst().orElseThrow();
        assertThat(edit.text("permission_decision", "decision")).isEqualTo("Denied");
        assertThat(edit.text("permission_decision", "reported_decision")).isEqualTo("reject");
        assertThat(edit.text("permission_decision", "decision_source")).isEqualTo("config");
        assertThat(edit.text("permission_decision", "data", "non_execution_kind")).isEqualTo("user-rejected");
        assertThat(edit.get("tool_result", "error_reported").scalarText()).isEqualTo("true");
        assertThat(edit.integer("status_id")).isEqualTo(2);
        Event write = p.events("tool_call", 2).stream().filter(e -> "toolu_01H4zranPawUBkppWXa5hMZX".equals(e.text("tool_call_uid"))).findFirst().orElseThrow();
        assertThat(write.text("permission_decision", "decision")).isEqualTo("Allowed");
        assertThat(write.text("permission_decision", "reason_type")).isEqualTo("rule");
        Event turn = p.events("prompt_turn", 2).get(0);
        List<Json> denials = turn.elements("permission_decisions");
        assertThat(denials).hasSize(1);
        assertThat(SchemaCatalog.text(denials.get(0), "scope")).isEqualTo("Run");
        assertThat(SchemaCatalog.text(denials.get(0).member("attribution"), "outcome")).isEqualTo("Unique");
        assertThat(denials.get(0).member("attribution").member("candidates").elements().get(0).text()).isEqualTo("toolu_01N8xZH4f5KoLaQhkgQLfVLi");
        assertThat(SchemaCatalog.text(denials.get(0).member("data"), "tool_name")).isEqualTo("Edit");
        assertThat(report.check("population.decisions", MAIN).detail()).isEqualTo("4 expected, 4 observed");
        assertThat(report.outcome("decision.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(p.events("source_record", 1).stream().map(e -> e.text("record_type")).toList()).contains("system/permission_denied");
    }

    /** Recording: a capture that cancelled the CLI is an honest interrupted prefix; the Codex one too, with exit 0 on the signal recorded by the live capture. */
    @Test
    void theCancelledRecordingsAreHonestInterruptedPrefixes(@TempDir Path work) {
        for (String name : List.of(LIVE_CANCELLED, LIVE_CODEX_CANCELLED)) {
            Path fixture = copy(name, work.resolve(name));
            OracleReport report = verify(fixture);
            assertThat(report.defects()).as(name + ": " + report).isEmpty();
            assertThat(report.outcome("acquisition.complete", "main")).as(name).isEqualTo(Outcome.UNSUPPORTED);
            assertThat(report.outcome("package.state", "main")).as(name).isEqualTo(Outcome.UNSUPPORTED);
            OcsfPackage p = open(fixture);
            assertThat(p.manifest.packageState()).isEqualTo("INTERRUPTED");
            assertThat(p.manifest.text("recording", "interruption_reason")).contains("deliberate cancellation");
            Json frozen = NeutralJson.readDocument(fixture.resolve("native").resolve("expected-sources.json"));
            assertThat(SchemaCatalog.text(frozen.path("frozen", "live_process"), "how")).isEqualTo("cancelled");
            assertThat(frozen.member("complete").scalarText()).isEqualTo("false");
        }
        Json claude = NeutralJson.readDocument(copy(LIVE_CANCELLED, work.resolve("c2")).resolve("native").resolve("expected-sources.json"));
        assertThat(claude.path("frozen", "live_process", "exit_code").scalarText()).as("claude exits 143 on the terminate signal").isEqualTo("143");
        Json codex = NeutralJson.readDocument(copy(LIVE_CODEX_CANCELLED, work.resolve("c3")).resolve("native").resolve("expected-sources.json"));
        assertThat(codex.path("frozen", "live_process", "exit_code").scalarText()).as("codex catches the signal and exits 0; the capture still records the cancellation").isEqualTo("0");
    }

    /** Codex: operations with exact exit codes and output, a failed command, an identical command repeated under a new item id, the turn's usage, and the thread id as session. */
    @Test
    void theCodexRecordingExhibitsOperationsFailureRepetitionAndUsage(@TempDir Path work) {
        Path fixture = copy(LIVE_CODEX, work);
        OracleReport report = verify(fixture);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("acquisition.complete", "main")).isEqualTo(Outcome.VERIFIED);
        OcsfPackage p = open(fixture);
        assertThat(p.manifest.cliVersion()).isEqualTo("0.160.1");
        assertThat(p.manifest.text("run", "cli", "version_basis")).contains("codex --version");
        assertThat(p.manifest.sessionUid()).isEqualTo("00000000-0000-4000-8000-0000000c0de6");
        List<Event> ops = p.events("tool_call", 3);
        assertThat(ops).hasSize(8);
        Event failed = ops.stream().filter(e -> "item_3".equals(e.text("tool_operation", "uid")) && "failed".equals(e.text("tool_operation", "reported_state"))).findFirst().orElseThrow();
        assertThat(failed.integer("tool_operation", "exit_code")).isEqualTo(1);
        assertThat(failed.text("tool_operation", "stdout")).isEqualTo("cat: missing-file.txt: No such file or directory\n");
        assertThat(failed.integer("status_id")).isEqualTo(2);
        Event second = ops.stream().filter(e -> "item_2".equals(e.text("tool_operation", "uid")) && "completed".equals(e.text("tool_operation", "reported_state"))).findFirst().orElseThrow();
        Event fourth = ops.stream().filter(e -> "item_4".equals(e.text("tool_operation", "uid")) && "completed".equals(e.text("tool_operation", "reported_state"))).findFirst().orElseThrow();
        assertThat(second.text("tool_operation", "data", "command")).isEqualTo(fourth.text("tool_operation", "data", "command"));
        assertThat(second.text("tool_operation", "stdout")).isEqualTo(fourth.text("tool_operation", "stdout")).isEqualTo("alpha");
        assertThat(second.text("tool_operation", "uid")).isNotEqualTo(fourth.text("tool_operation", "uid"));
        assertThat(report.outcome("operation.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        assertThat(report.check("population.operations", MAIN).detail()).isEqualTo("8 expected, 8 observed");
        Event turn = p.events("prompt_turn", 2).get(0);
        Json usage = turn.elements("usage_observations").get(0);
        // the reported inclusive input 79261 less the 74880 cached is 4381 only if the cache-write 0 was measured and both running
        // totals are complete: it is the uncached component's conditional value, and the canonical tranche stays absent
        assertThat(usage.member("uncached_input_tokens")).isNull();
        assertThat(usage.member("components").elements().get(0).member("conditional_value").scalarText()).isEqualTo("4381");
        assertThat(usage.member("cache_read_tokens").scalarText()).isEqualTo("74880");
        assertThat(usage.member("aggregation").text()).isEqualTo("Cumulative snapshot");
        assertThat(usage.path("data", "input_tokens").scalarText()).as("the native inclusive value stays in data").isEqualTo("79261");
        assertThat(usage.member("reasoning_tokens").scalarText()).isEqualTo("18");
        // the thread's cumulative usage at turn completion (SEM-1 candidate reading of contract 0.3.0; pending Owner approval)
        assertThat(usage.member("scope").text()).isEqualTo("Session");
        // an update arrived, but the running total adds only the responses whose usage arrived: at least these values
        assertThat(usage.member("measurement_status").text()).isEqualTo("Lower bound");
        assertThat(turn.get("cost_observation")).as("the exec stream reports no cost; none is invented").isNull();
        assertThat(report.outcome("identity.values", MAIN)).isEqualTo(Outcome.VERIFIED);
        // two completed content items; the stream names no model response, so none carries a response id
        assertThat(p.events("model_response", 3)).hasSize(2).allSatisfy(e -> assertThat(e.get("response_uid")).isNull());
        assertThat(p.events("model_response", 2)).isEmpty();
    }

    /** Every committed fixture states its evidence class in its spec label, and every live-derived one carries a provenance manifest. */
    @Test
    void everyFixtureStatesItsEvidenceClassAndLiveOnesCarryProvenance() throws Exception {
        Path fixtures = Path.of(OcsfFixtures.class.getResource("/ocsf/fixtures/README.md").toURI()).getParent();
        List<String> seen = new ArrayList<>();
        try (var dirs = Files.list(fixtures)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String name = dir.getFileName().toString();
                seen.add(name);
                Json spec = NeutralJson.readDocument(dir.resolve("spec.json"));
                String label = SchemaCatalog.text(spec.member("synthetic"), "label");
                assertThat(label).as(name + " spec.json synthetic.label").isNotBlank();
                assertThat(FixtureEvidenceClass.origin(dir)).as(name + " states live recording, sanitized real stream or labelled synthetic: " + label).isNotNull();
                assertThat(FixtureEvidenceClass.problems(dir)).as(name + " is consistent with its stated evidence class").isEmpty();
                boolean live = FixtureEvidenceClass.origin(dir) == FixtureEvidenceClass.Origin.LIVE_RECORDING;
                if (live) {
                    Json prov = NeutralJson.readDocument(dir.resolve("provenance.json"));
                    assertThat(SchemaCatalog.text(prov, "origin")).startsWith("SANITIZED REAL LIVE RECORDING");
                    if (prov.member("files") instanceof Json.JObject files) {
                        // a multi-source live fixture: every native file with its original and sanitized hash
                        for (Map.Entry<String, Json> f : files.members().entrySet()) {
                            assertThat(SchemaCatalog.text(f.getValue(), "original_sha256")).as(name + " " + f.getKey()).hasSize(64);
                            assertThat(SchemaCatalog.text(f.getValue(), "sha256")).as(name + " " + f.getKey()).isEqualTo(NativeArtifact.read(dir.resolve("native").resolve(f.getKey())).sha256());
                        }
                    }
                    else {
                        assertThat(SchemaCatalog.text(prov, "original_sha256")).hasSize(64);
                        assertThat(SchemaCatalog.text(prov, "sanitized_sha256")).isEqualTo(NativeArtifact.read(dir.resolve("native").resolve("stream.jsonl")).sha256());
                    }
                    assertThat(prov.member("sanitization").elements()).isNotEmpty();
                    assertThat(prov.member("capture_command").elements()).isNotEmpty();
                    assertThat(SchemaCatalog.text(prov, "cli_version")).isNotBlank();
                    try (var natives = Files.list(dir.resolve("native"))) {
                        for (Path n : natives.toList()) {
                            assertThat(Files.readString(n)).as(name + " " + n.getFileName()).doesNotContain("/home/mark").doesNotContain("/tmp/claude-1000");
                        }
                    }
                    assertThat(Files.readString(dir.resolve("provenance.json"))).as(name + " provenance").doesNotContain("/home/mark").doesNotContain("/tmp/claude-1000").doesNotContain("-home-mark");
                }
                assertThat(Files.readString(dir.resolve("spec.json"))).as(name + " spec").doesNotContain("/home/mark").doesNotContain("/tmp/claude-1000").doesNotContain("-home-mark");
            }
        }
        assertThat(seen).contains(LIVE_FEATURES, LIVE_DENIAL, LIVE_CANCELLED, LIVE_CODEX, LIVE_CODEX_CANCELLED);
    }
}
