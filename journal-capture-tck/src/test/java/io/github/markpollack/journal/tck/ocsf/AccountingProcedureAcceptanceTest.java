package io.github.markpollack.journal.tck.ocsf;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editDeclarations;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class AccountingProcedureAcceptanceTest {

    @Test
    void provisionalEightSupportsFinal184And192IsRejected(@TempDir Path work) {
        Path fixture = copy("claude-live-2.1.304-mcp-pair", work);
        OracleReport control = accounting(fixture);
        assertThat(control.checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.final.value")
                                        && c.outcome() == Check.Outcome.VERIFIED
                                        && c.evidence().fact().endsWith("/output_tokens")
                                        && Json.sameValue(c.evidence().expected(), num("184"))
                                        && c.evidence().nativeAddresses().stream()
                                                .anyMatch(
                                                        a ->
                                                                a.startsWith(
                                                                        "transcript.jsonl:record=")));
        editEvents(
                pkg(fixture),
                events ->
                        events.stream()
                                .filter(
                                        e ->
                                                "3"
                                                                .equals(
                                                                        text(
                                                                                e.path(
                                                                                        "assembly",
                                                                                        "assembly_role_id")))
                                                        && e.member("usage_observations") != null
                                                        && "msg_011CgKzSHFoJacdngyy7dCti"
                                                                .equals(
                                                                        text(
                                                                                e.member(
                                                                                        "response_uid"))))
                                .forEach(
                                        e ->
                                                obj(e.member("usage_observations")
                                                                .elements()
                                                                .get(0))
                                                        .members()
                                                        .put("output_tokens", num("192"))));
        OracleReport changed = accounting(fixture);
        assertThat(changed.checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.final.value")
                                        && c.outcome() == Check.Outcome.DEFECTIVE
                                        && Json.sameValue(c.evidence().expected(), num("184"))
                                        && Json.sameValue(c.evidence().actual(), num("192")));
        assertThat(changed.checks().stream().filter(c -> c.property().equals("fact.usage.value")))
                .allMatch(c -> c.outcome() == Check.Outcome.VERIFIED);
    }

    @Test
    void theSameVendorUsesExactFrozenVersionBindings(@TempDir Path work) {
        for (String fixtureName : List.of("claude-child-2.1.299", "claude-live-2.1.304-features")) {
            Path fixture = copy(fixtureName, work);
            OracleReport control = OcsfFixtures.verify(fixture);
            assertThat(control.checks().stream().filter(c -> c.property().equals("source.binding")))
                    .isNotEmpty()
                    .allMatch(c -> c.outcome() == Check.Outcome.VERIFIED);
            editDeclarations(
                    pkg(fixture),
                    declarations ->
                            declarations.stream()
                                    .filter(
                                            d ->
                                                    "compatibility"
                                                            .equals(
                                                                    text(
                                                                            d.path(
                                                                                    "declaration",
                                                                                    "kind"))))
                                    .forEach(
                                            d ->
                                                    obj(d.member("compatibility"))
                                                            .members()
                                                            .put(
                                                                    "entry_id",
                                                                    str(
                                                                            fixtureName.contains(
                                                                                            "299")
                                                                                    ? "claude-code/stream-json/2.1.304-stdout"
                                                                                    : "claude-code/stream-json/2.1.299-sdk"))));
            assertThat(OcsfFixtures.verify(fixture).checks())
                    .anyMatch(
                            c ->
                                    c.property().equals("source.binding")
                                            && c.outcome() == Check.Outcome.DEFECTIVE);
        }
    }

    @Test
    void anUnknownVersionPreservesBytesAndReceivesNoNormalizationCredit(@TempDir Path work) {
        OracleReport report = OcsfFixtures.verify(copy("syn-claude-untested-version", work));
        assertThat(report.checks())
                .anyMatch(
                        c ->
                                c.property().equals("whole_record.coverage")
                                        && c.outcome() == Check.Outcome.VERIFIED);
        assertThat(report.checks())
                .anyMatch(
                        c ->
                                c.property().equals("usage.values")
                                        && c.outcome() == Check.Outcome.UNSUPPORTED);
        assertThat(report.checks())
                .noneMatch(
                        c ->
                                c.category() == Check.Category.NORMALIZATION
                                        && c.property().startsWith("fact.")
                                        && c.outcome() == Check.Outcome.VERIFIED);
    }

    @Test
    void missingNullDefaultAndMeasuredZeroRemainDistinctInComparisonsAndConsumerView(
            @TempDir Path work) {
        Path control = copy(OcsfFixtures.SYN_AGENT_DEFAULTS, work.resolve("control"));
        Json defaulted = usageView(control, 5);
        assertThat(defaulted.path("components", "output_tokens")).isEqualTo(num("0"));
        assertThat(defaulted.member("measurement_status").text()).isEqualTo("Possibly default");
        Json measured = usageView(control, 6);
        assertThat(measured.path("components", "cache_write_tokens")).isEqualTo(num("0"));
        assertThat(measured.member("measurement_status").text())
                .isEqualTo("Measured (no status stated)");
        for (String kind : List.of("missing", "null")) {
            Path changed = copy(OcsfFixtures.SYN_AGENT_DEFAULTS, work.resolve(kind));
            editEvents(
                    pkg(changed),
                    events -> {
                        Json usage =
                                OcsfFixtures.at(events, "session_context", 3, 6)
                                        .member("usage_observations")
                                        .elements()
                                        .get(0);
                        if (kind.equals("missing"))
                            obj(usage).members().remove("cache_write_tokens");
                        else obj(usage).members().put("cache_write_tokens", Json.JNull.INSTANCE);
                    });
            Json actual = usageView(changed, 6).path("components", "cache_write_tokens");
            if (kind.equals("missing")) assertThat(actual).isNull();
            else assertThat(actual).isEqualTo(Json.JNull.INSTANCE);
            assertThat(OcsfFixtures.verify(changed).checks())
                    .anyMatch(
                            c ->
                                    c.evidence() != null
                                            && c.evidence().fact().endsWith("/cache_write_tokens")
                                            && c.outcome() == Check.Outcome.DEFECTIVE
                                            && Json.sameValue(c.evidence().expected(), num("0"))
                                            && Json.sameValue(c.evidence().actual(), actual));
        }
    }

    private static Json usageView(Path fixture, int record) {
        return AnalysisViews.usage(OcsfFixtures.open(fixture)).member("groups").elements().stream()
                .flatMap(g -> g.member("observations").elements().stream())
                .filter(o -> Integer.toString(record).equals(o.member("record_index").scalarText()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void anOriginLabelCannotExcuseAnAlteredVendorMeasurement(@TempDir Path work) {
        Path fixture = copy(OcsfFixtures.LIVE_CODEX, work);
        editEvents(
                pkg(fixture),
                events -> {
                    Json event =
                            events.stream()
                                    .filter(e -> e.member("usage_observations") != null)
                                    .findFirst()
                                    .orElseThrow();
                    Json.JObject usage = obj(event.member("usage_observations").elements().get(0));
                    usage.members().put("basis", str("caller origin: supplied by the caller"));
                    usage.members().put("output_tokens", num("999999"));
                });
        assertThat(OcsfFixtures.verify(fixture).checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.usage.value")
                                        && c.outcome() == Check.Outcome.DEFECTIVE
                                        && c.evidence().fact().endsWith("/output_tokens"));
    }

    @Test
    void anApplicationTokensMemberCreatesNoUsageObligationOrPermissionToInventUsage() {
        String nativeText =
                "{\"type\":\"assistant\",\"message\":{\"id\":\"response-1\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call-1\",\"name\":\"application\",\"input\":{\"tokens\":7}}]}}\n";
        NativeArtifact nativeArtifact =
                NativeArtifact.ofBytes(
                        Path.of("native.jsonl"), nativeText.getBytes(StandardCharsets.UTF_8));
        ExpectedFacts facts =
                SourceProfile.forName("claude_stream")
                        .expected(
                                nativeArtifact,
                                new Json.JObject(Map.of("artifact", str("native.jsonl"))),
                                null);
        assertThat(facts.requests)
                .singleElement()
                .satisfies(r -> assertThat(r.arguments().member("tokens")).isEqualTo(num("7")));
        assertThat(facts.usages).isEmpty();
        Json source =
                new Json.JObject(Map.of("artifact", str("native.jsonl"), "record_index", num("1")));
        Event invented =
                new Event(
                        1,
                        new Json.JObject(
                                Map.of(
                                        "source_ref",
                                        source,
                                        "usage_observations",
                                        new Json.JArray(
                                                List.of(
                                                        new Json.JObject(
                                                                Map.of(
                                                                        "source_ref",
                                                                        source,
                                                                        "scope_id",
                                                                        num("2"),
                                                                        "output_tokens",
                                                                        num("7"))))))),
                        "model_response");
        OracleReport report = new OracleReport();
        UsageComparisons.check(report, facts, List.of(invented), "main/native.jsonl");
        assertThat(report.defects())
                .singleElement()
                .satisfies(c -> assertThat(c.property()).isEqualTo("fact.usage.unsupported"));
    }

    @Test
    void theAntigravityDispatcherDoesNotAuthorizeAnInventedMcpResult(@TempDir Path work) {
        Path fixture = copy("antigravity-live-1.4.6-mcp", work);
        OracleReport control = OcsfFixtures.verify(fixture);
        assertThat(control.checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.mcp.value")
                                        && c.outcome() == Check.Outcome.VERIFIED);
        editEvents(
                pkg(fixture),
                events -> {
                    Json event =
                            events.stream()
                                    .filter(e -> e.member("mcp") != null)
                                    .findFirst()
                                    .orElseThrow();
                    Json.JArray objects = (Json.JArray) event.path("mcp", "protocol_objects");
                    List<Json> changed = new java.util.ArrayList<>(objects.elements());
                    changed.add(
                            io.github.markpollack.journal.tck.verify.NeutralJson.parse(
                                    """
                                    {"protocol_definition":"CallToolResult","binding_revision":"2025-11-25","projection_id":2,
                                     "projection":"Partial projection","mcp_call_tool_result":{"mcp_content":[],
                                     "mcp_is_error":false,"mcp_structured_content":{}}}
                                    """));
                    obj(event.member("mcp"))
                            .members()
                            .put("protocol_objects", new Json.JArray(changed));
                });
        OracleReport changed = OcsfFixtures.verify(fixture);
        assertThat(changed.defects()).isNotEmpty();
        assertThat(OcsfReaderTool.acceptance(changed).verdict()).isEqualTo("DEFECTIVE");
    }

    @Test
    void exactExecutionSecondsAndUnitsRejectRoundingAndRelabelling(@TempDir Path work) {
        for (String member : List.of("reported_value", "metric_unit")) {
            Path fixture = copy("antigravity-live-1.4.6-mcp", work.resolve(member));
            OracleReport control = OcsfFixtures.verify(fixture);
            assertThat(control.checks())
                    .anyMatch(
                            c ->
                                    c.evidence() != null
                                            && c.evidence().fact().endsWith("/reported_value")
                                            && Json.sameValue(
                                                    c.evidence().expected(), num("5.502943992"))
                                            && c.outcome() == Check.Outcome.VERIFIED);
            editEvents(
                    pkg(fixture),
                    events ->
                            events.stream()
                                    .filter(
                                            e ->
                                                    "3"
                                                            .equals(
                                                                    text(
                                                                            e.path(
                                                                                    "source_ref",
                                                                                    "record_index"))))
                                    .filter(e -> e.member("execution_metrics") != null)
                                    .forEach(
                                            e ->
                                                    obj(e.member("execution_metrics")
                                                                    .elements()
                                                                    .get(0))
                                                            .members()
                                                            .put(
                                                                    member,
                                                                    member.equals("reported_value")
                                                                            ? num("5.503")
                                                                            : str("ms"))));
            assertThat(OcsfFixtures.verify(fixture).defects())
                    .anyMatch(
                            c ->
                                    c.evidence() != null
                                            && c.evidence().fact().endsWith("/" + member));
        }
    }

    @Test
    void childIdentityModelAndCountersAreRequiredOutsideTheirPreservedPayload(@TempDir Path work) {
        for (String member : List.of("agent_uid", "model_ref", "execution_metrics")) {
            Path fixture = copy("claude-live-2.1.304-features", work.resolve(member));
            assertThat(OcsfFixtures.verify(fixture).defects()).isEmpty();
            editEvents(
                    pkg(fixture),
                    events ->
                            events.stream()
                                    .filter(
                                            e ->
                                                    "33"
                                                            .equals(
                                                                    text(
                                                                            e.path(
                                                                                    "source_ref",
                                                                                    "record_index"))))
                                    .filter(e -> e.member("agent_uid") != null)
                                    .forEach(
                                            e -> {
                                                if (member.equals("agent_uid"))
                                                    obj(e).members()
                                                            .put(member, str("another-child"));
                                                if (member.equals("model_ref"))
                                                    obj(e.member(member))
                                                            .members()
                                                            .put("name", str("another-model"));
                                                if (member.equals("execution_metrics")) {
                                                    Json metric =
                                                            e.member(member).elements().stream()
                                                                    .filter(
                                                                            m ->
                                                                                    "tool_use_result/totalToolUseCount"
                                                                                            .equals(
                                                                                                    text(
                                                                                                            m
                                                                                                                    .member(
                                                                                                                            "metric_name"))))
                                                                    .findFirst()
                                                                    .orElseThrow();
                                                    assertThat(metric.member("reported_value"))
                                                            .isEqualTo(num("2"));
                                                    obj(metric)
                                                            .members()
                                                            .put("reported_value", num("3"));
                                                }
                                            }));
            OracleReport changed = OcsfFixtures.verify(fixture);
            assertThat(changed.defects())
                    .anyMatch(
                            c ->
                                    c.evidence() != null
                                            && c.evidence().nativeAddresses().stream()
                                                    .anyMatch(
                                                            a ->
                                                                    a.contains(
                                                                            "record=33#/tool_use_result/")));
            assertThat(changed.outcome("whole_record.structure_value", "main/stream.jsonl"))
                    .as("preserving the parent object cannot discharge its typed child facts")
                    .isEqualTo(Check.Outcome.VERIFIED);
        }
    }

    @Test
    void planCompletionCannotChangeWhileItsNativeRecordIsPreserved(@TempDir Path work) {
        Path fixture = copy("codex-live-0.160.1-plan", work);
        editEvents(
                pkg(fixture),
                events ->
                        events.stream()
                                .filter(e -> "3".equals(text(e.path("source_ref", "record_index"))))
                                .filter(e -> e.member("plan_items") != null)
                                .forEach(
                                        e ->
                                                obj(e.member("plan_items").elements().get(0))
                                                        .members()
                                                        .put(
                                                                "is_completed",
                                                                new Json.JBool(true))));
        OracleReport changed = OcsfFixtures.verify(fixture);
        assertThat(changed.defects())
                .anyMatch(c -> c.evidence() != null && c.evidence().fact().contains("plan_items"));
    }

    @Test
    void aWrongFrozenSpecificationHashCannotSelectTheSameNamedReader(@TempDir Path work) {
        Path fixture = copy(OcsfFixtures.LIVE_CODEX, work);
        OcsfFixtures.editExpected(
                fixture,
                manifest ->
                        obj(manifest.member("sources").elements().get(0).member("binding"))
                                .members()
                                .put("specification_sha256", str("0".repeat(64))));
        OracleReport changed = OcsfFixtures.verify(fixture);
        assertThat(changed.defects())
                .anyMatch(
                        c ->
                                c.property().equals("source.reader_binding")
                                        && c.evidence().fact().endsWith(":specification_sha256"));
    }

    @Test
    void aMissingScalarComparisonCannotBorrowCreditFromItsPreservedParent(@TempDir Path work) {
        String name = "claude-live-2.1.304-features";
        OracleReport report = OcsfFixtures.verify(copy(name, work));
        OracleReport missing =
                report.filtered(
                        c -> c.evidence() == null || !c.evidence().fact().endsWith("/agent_uid"));
        OccurrenceLedger.Source source =
                OccurrenceLedger.sources().stream()
                        .filter(
                                s ->
                                        s.fixture().equals(name)
                                                && s.artifact().equals("stream.jsonl"))
                        .findFirst()
                        .orElseThrow();
        OccurrenceLedger.Ledger ledger =
                OccurrenceLedger.ledger(
                        source,
                        OccurrenceLedger.catalogs(),
                        OccurrenceLedger.Families.read(),
                        OccurrenceLedger.ownerPath(source.profile()));
        assertThat(NativeAccounting.account(ledger, missing))
                .filteredOn(o -> o.record() == 33 && o.pointer().equals("/tool_use_result/agentId"))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo("UNCHECKED"));
        assertThat(OcsfReaderTool.acceptance(missing).verdict()).isEqualTo("INCOMPLETE");
    }

    private static String text(Json value) {
        return value == null ? null : value.scalarText();
    }

    @Test
    void exporterStatisticsKeepTheirWindowAndRejectAChangedBucket(@TempDir Path work) {
        Path fixture = copy("copilot-live-1.0.100-otel", work);
        OracleReport control = accounting(fixture);
        assertThat(control.checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.value")
                                        && c.evidence() != null
                                        && c.evidence()
                                                .meaning()
                                                .equals("source.exporter_statistic/v1")
                                        && c.evidence()
                                                .fact()
                                                .contains(
                                                        "gen_ai.client.operation.duration:/dataPoints/0/value/buckets/counts/0")
                                        && c.evidence().fact().endsWith("/reported_value")
                                        && Json.sameValue(c.evidence().expected(), num("2"))
                                        && c.outcome() == Check.Outcome.VERIFIED);
        editEvents(
                pkg(fixture),
                events ->
                        events.forEach(
                                e -> {
                                    if (e.member("execution_metrics") == null) return;
                                    e.member("execution_metrics").elements().stream()
                                            .filter(
                                                    m ->
                                                            "gen_ai.client.operation.duration:/dataPoints/0/value/buckets/counts/0"
                                                                    .equals(
                                                                            text(
                                                                                    m.member(
                                                                                            "metric_name"))))
                                            .forEach(
                                                    m ->
                                                            obj(m).members()
                                                                    .put(
                                                                            "reported_value",
                                                                            num("3")));
                                }));
        OracleReport changed = accounting(fixture);
        assertThat(changed.checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.value")
                                        && c.evidence()
                                                .meaning()
                                                .equals("source.exporter_statistic/v1")
                                        && c.outcome() == Check.Outcome.DEFECTIVE
                                        && Json.sameValue(c.evidence().expected(), num("2"))
                                        && Json.sameValue(c.evidence().actual(), num("3")));
    }

    @Test
    void aJunieAgentIdentityIsNotDischargedByWholeRecordRetention(@TempDir Path work) {
        Path fixture = copy("junie-live-3813.7-view", work);
        editEvents(
                pkg(fixture),
                events ->
                        events.stream()
                                .filter(e -> e.member("reported_agent") != null)
                                .forEach(
                                        e ->
                                                obj(e.member("reported_agent")).members()
                                                        .put("uid", str("another-agent"))));
        OracleReport changed = accounting(fixture);
        assertThat(changed.checks())
                .anyMatch(
                        c ->
                                c.property().equals("fact.value")
                                        && c.evidence().meaning().equals("source.reported_agent/v2")
                                        && c.outcome() == Check.Outcome.DEFECTIVE);
        assertThat(
                        changed.checks().stream()
                                .filter(c -> c.property().equals("whole_record.coverage")))
                .allMatch(c -> c.outcome() == Check.Outcome.VERIFIED);
    }

    private static OracleReport accounting(Path fixture) {
        return io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle.verifyAll(
                OcsfFixtures.open(fixture),
                fixture.resolve("native"),
                ExpectedSources.beside(fixture.resolve("native")),
                AssemblyOracleTest.expectation(fixture),
                AssemblyOracleTest.supplied(fixture));
    }
}
