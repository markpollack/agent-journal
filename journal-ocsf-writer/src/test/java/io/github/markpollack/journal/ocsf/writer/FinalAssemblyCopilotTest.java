package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.FIXTURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_COPILOT;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final Level 1 assembly of GitHub Copilot CLI 1.0.100's four sources of one run (the sanitized live run copilot-otel-1:
 * the session file, standard output, the OTel file export and the usage report) by the same engine as Claude's, under
 * the experimental profile, judged by the independent {@link AssemblyOracle}. The union: richer tool results and
 * ephemeral progress from standard output, per-call usage and cost from the session file and OTel, tool definitions
 * from OTel, MCP structured content kept from the event routes. The accounting regression: the OTel invocation's
 * multiplier sum (5.0) and the session's one premium request are distinct quantity kinds, never compared or added;
 * an absent per-span cache-write count stays absent.
 */
class FinalAssemblyCopilotTest {

    static final String PROFILE = "github-copilot-cli-1.0.100-three-source";
    static final String RUN_SESSION = "00000000-0000-4000-8000-0000000000c1";
    static final String OTHER_SESSION = "11111111-1111-4111-8111-111111111111";

    /** One input with the acquisition its supplier asserts (the fixture's own launch). */
    record In(String artifact, String role, Path file, String origin, String acquisition) {

        In(String artifact, String role, Path file, String origin) {
            this(artifact, role, file, origin, WriterTestSupport.launch(LIVE_COPILOT));
        }
    }

    static List<In> all() {
        return List.of(in("session-events.jsonl", "session", "native session directory events.jsonl copied after the run (sanitized)"),
                in("stdout.jsonl", "stdout", "standard output of the launch (sanitized)"), in("otel.jsonl", "otel", "the CLI's OTel file exporter output (sanitized)"),
                in("usage-output.json", "usage_report", "the usage report the launch named with --usage-output-file"));
    }

    static In in(String artifact, String role, String origin) {
        return new In(artifact, role, nativeOf(LIVE_COPILOT, artifact), origin);
    }

    @Test
    void theFourSourcesAssembleIntoOneExecutionTheOracleVerifies(@TempDir Path work) throws IOException {
        Path out = assemble(work.resolve("final"), all());
        OracleReport report = oracle(out, all());
        assertThat(report.defects()).as(report.toString()).isEmpty();
        assertThat(report.outcome("assembly.entities", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(report.outcome("assembly.consolidation", "assembly")).isEqualTo(Check.Outcome.VERIFIED);
        for (String total : List.of("call_output_tokens", "call_cache_read_tokens", "call_model_multipliers", "run_multiplier_sum", "session_premium_requests")) {
            assertThat(report.outcome("assembly.accounting." + total, "assembly")).as(total + ": " + report).isEqualTo(Check.Outcome.VERIFIED);
        }
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        // five model calls, each one canonical response consolidating the message, its usage record and its chat span
        assertThat(pkg.events("model_response")).hasSize(5).allSatisfy(e -> assertThat(e.assemblyRole()).isEqualTo(3));
        // Five completions plus two separately reported partial results; partial output never becomes another completion.
        assertThat(pkg.events("tool_call", 1)).hasSize(5);
        assertThat(pkg.events("tool_call", 4)).hasSize(5);
        assertThat(pkg.events("tool_call", 2)).hasSize(7);
        assertThat(pkg.events("tool_call", 2).stream().filter(e -> "tool.execution_partial_result".equals(e.text("data", "type")))).hasSize(2);
        Json declared = pkg.declarations.assembly();
        assertThat(declared.path("result", "missing_required_roles").elements()).isEmpty();
        assertThat(declared.path("result", "refused_sources").elements()).isEmpty();
    }

    @Test
    void theCanonicalEventsCarryTheUnionOfTheSources(@TempDir Path work) throws IOException {
        OcsfPackage pkg = PackageReader.open(assemble(work.resolve("final"), all()).resolve("events.jsonl"));
        Event alpha = pkg.events("tool_call", 2).stream().filter(e -> e.get("tool_result", "structured_result", "echo") != null
                && "alpha".equals(e.text("tool_result", "structured_result", "echo"))).findFirst().orElseThrow();
        // MCP structured content from the event routes, the MCP content list from standard output, the arguments from OTel
        assertThat(alpha.get("tool_result", "data", "contents")).as("standard output's richer result").isNotNull();
        assertThat(alpha.get("arguments", "text").text()).isEqualTo("alpha");
        Json projection = alpha.elements("mcp", "protocol_objects").get(0);
        assertThat(projection.path("mcp_call_tool_result", "mcp_structured_content", "echo").text()).isEqualTo("alpha");
        assertThat(projection.path("mcp_call_tool_result", "mcp_content_block_list")).as("standard output's content list").isNotNull();
        assertThat(projection.member("missing_required")).as("the session's projection lacked content; standard output's supplied it").isNull();
        assertThat(projection.member("projection_id").scalarText()).as("still a partial projection, not a synthesized wire message").isEqualTo("2");
        assertThat(alpha.elements("assembly", "assembly_conflicts")).singleElement()
                .satisfies(c -> assertThat(c.member("field_pointer").text()).isEqualTo("/tool_result/data"));
        // the OTel span's text rendering stays on its own supporting observation
        assertThat(pkg.events.stream().filter(e -> e.assemblyRole() != null && e.assemblyRole() == 2 && "otel.jsonl".equals(e.artifact()) && e.is("tool_call", 2))
                .map(e -> e.get("tool_result", "data")).filter(v -> v instanceof Json.JString)).hasSize(5);
        // tool definitions and MCP servers only OTel reports
        assertThat(pkg.events("session_context", 1).stream().filter(e -> !e.elements("tool_definitions").isEmpty())).singleElement()
                .satisfies(e -> assertThat(e.elements("tool_definitions")).isNotEmpty());
        // ephemeral progress only standard output reports is canonical, once
        assertThat(pkg.canonical().stream().filter(e -> "stdout.jsonl".equals(e.artifact()) && "assistant.message_delta".equals(e.text("record_type")))).hasSize(12);
    }

    @Test
    void aMultiplierSumAndPremiumRequestsAreDistinctQuantities(@TempDir Path work) throws IOException {
        OcsfPackage pkg = PackageReader.open(assemble(work.resolve("final"), all()).resolve("events.jsonl"));
        Event finalAccounting = pkg.events("session_context", 3).stream().filter(e -> e.assemblyRole() == 3 && e.elements("assembly", "observation_refs").size() == 3)
                .findFirst().orElseThrow();
        assertThat(cost(finalAccounting, 3, 4)).as("OTel invoke_agent: the run's multiplier sum").isEqualByComparingTo("5.0");
        assertThat(cost(finalAccounting, 2, 5)).as("the session's premium requests (shutdown and usage report agree)").isEqualByComparingTo("1");
        assertThat(finalAccounting.elements("cost_observations")).extracting(c -> c.member("quantity_unit").text()).contains("multiplier sum", "premium requests", "nano-AIU")
                .doesNotContain("USD");
        // per call: a multiplier of 1.0 each, at model-response scope; never premium requests
        assertThat(pkg.events("model_response")).allSatisfy(e -> assertThat(cost(e, 3, 2)).isEqualByComparingTo("1.0"));
        assertThat(pkg.events("model_response").stream().flatMap(e -> e.elements("cost_observations").stream()).filter(c -> "2".equals(c.member("quantity_kind_id").scalarText())))
                .isEmpty();
        // per-call usage sums to the session totals, which are snapshots at another scope and never added to them
        BigDecimal perCall = pkg.events("model_response").stream().flatMap(e -> e.elements("usage_observations").stream())
                .filter(u -> "2".equals(u.member("scope_id").scalarText())).map(u -> ((Json.JNumber) u.member("output_tokens")).value()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(perCall).isEqualByComparingTo("212");
        Json session = finalAccounting.elements("usage_observations").stream().filter(u -> "5".equals(u.member("scope_id").scalarText())).findFirst().orElseThrow();
        assertThat(session.member("aggregation_id").scalarText()).as("a cumulative snapshot").isEqualTo("2");
        assertThat(((Json.JNumber) session.member("output_tokens")).value()).isEqualByComparingTo("212");
    }

    @Test
    void anAbsentPerSpanCacheWriteStaysAbsent(@TempDir Path work) throws IOException {
        // without the session file (required: the assembly is incomplete) the calls' usage is OTel's and standard output's
        List<In> inputs = List.of(all().get(1), all().get(2));
        Path out = assemble(work.resolve("final"), inputs);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        assertThat(pkg.declarations.assembly().path("result", "status").text()).isEqualTo("incomplete");
        Event first = pkg.events("model_response").stream().filter(e -> "opaque-0001".equals(e.text("response_uid"))).findFirst().orElseThrow();
        Json usage = first.elements("usage_observations").get(0);
        assertThat(usage.member("cache_write_tokens")).as("the chat span reports no cache writes: absent, not the run metric's 0").isNull();
        assertThat(usage.member("uncached_input_tokens")).as("not established without cache writes").isNull();
        assertThat(usage.member("cache_read_tokens").scalarText()).isEqualTo("1152");
        OracleReport report = oracle(out, inputs);
        assertThat(report.outcome("assembly.required", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void anotherExecutionsTelemetryIsRefused(@TempDir Path work) throws IOException {
        Path foreign = Files.writeString(Files.createDirectories(work.resolve("edited")).resolve("otel.jsonl"),
                Files.readString(nativeOf(LIVE_COPILOT, "otel.jsonl"), StandardCharsets.UTF_8).replace(RUN_SESSION, OTHER_SESSION), StandardCharsets.UTF_8);
        List<In> inputs = List.of(all().get(0), all().get(1), new In("otel.jsonl", "otel", foreign, "the CLI's OTel file exporter output (sanitized)"), all().get(3));
        Path out = assemble(work.resolve("final"), inputs);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        assertThat(pkg.events.stream().filter(e -> "otel.jsonl".equals(e.artifact()))).isEmpty();
        OracleReport report = oracle(out, inputs);
        assertThat(report.outcome("assembly.gate", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(pkg.events("model_response")).as("the calls are still one each").hasSize(5);
    }

    @Test
    void theFinalFileReadsAloneAndTheOriginalsAreUnchanged(@TempDir Path work) throws IOException {
        StringBuilder before = new StringBuilder();
        all().forEach(i -> before.append(NativeArtifact.read(i.file()).sha256()));
        Path out = assemble(work.resolve("final"), all());
        StringBuilder after = new StringBuilder();
        all().forEach(i -> after.append(NativeArtifact.read(i.file()).sha256()));
        assertThat(after.toString()).isEqualTo(before.toString());
        Path alone = Files.createDirectories(work.resolve("elsewhere"));
        Files.copy(out.resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage pkg = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(pkg.diagnostics(io.github.markpollack.journal.tck.ocsf.Diagnostic.Severity.DEFECT)).as(pkg.diagnostics.toString()).isEmpty();
        assertThat(pkg.diagnostics(io.github.markpollack.journal.tck.ocsf.Diagnostic.Severity.REFUSED)).isEmpty();
        assertThat(pkg.events("model_response")).hasSize(5);
    }

    // ---- support -----------------------------------------------------------------------------------------------------

    static BigDecimal cost(Event e, int kind, int scope) {
        return e.elements("cost_observations").stream()
                .filter(c -> String.valueOf(kind).equals(c.member("quantity_kind_id").scalarText()) && String.valueOf(scope).equals(c.member("scope_id").scalarText()))
                .map(c -> ((Json.JNumber) c.member("total_cost")).value()).findFirst().orElse(null);
    }

    static Path assemble(Path out, List<In> inputs) {
        List<FinalAssembly.Input> in = inputs.stream().map(i -> new FinalAssembly.Input(i.artifact(), i.role(), i.file(), i.origin(), i.acquisition())).toList();
        FinalAssembly.assemble(out, run(LIVE_COPILOT), AssemblyProfile.bundled(PROFILE), in, new Jv.Arr(), options());
        return out;
    }

    static OracleReport oracle(Path out, List<In> inputs) throws IOException {
        Json spec = NeutralJson.parse(Files.readString(FIXTURES.resolve(LIVE_COPILOT).resolve("native/assembly-expectation.json")));
        List<AssemblyOracle.Supplied> supplied = inputs.stream().map(i -> new AssemblyOracle.Supplied(i.artifact(), i.role(), i.origin(),
                "usage_report".equals(i.role()) ? NativeArtifact.document(i.file()) : NativeArtifact.read(i.file()), i.acquisition())).toList();
        return AssemblyOracle.verify(PackageReader.open(out.resolve("events.jsonl")), spec, supplied);
    }
}
