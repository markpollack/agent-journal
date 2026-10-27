package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Literal answers from the frozen native records, independent of the mapper and its tables. */
class SemanticCorrectionWitnessTest {
    static final String JUNIE = "junie-live-3813.7-terminal";

    static OcsfPackage read(String fixture, Path work) {
        return PackageReader.open(OcsfFixtures.pkg(OcsfFixtures.copy(fixture, work)).resolve("events.jsonl"));
    }

    static Event record(OcsfPackage pkg, String artifact, int record, String member) {
        return pkg.events.stream().filter(e -> artifact.equals(e.artifact())
                && e.integer("source_ref", "record_index") == record && e.get(member) != null).findFirst().orElseThrow();
    }

    @Test
    void nativeMainAgentIsAnAgentSubjectNeverAChild(@TempDir Path work) {
        int count = 0;
        for (String fixture : List.of(JUNIE, "junie-live-3813.7-view")) {
            OcsfPackage pkg = read(fixture, work);
            for (Event event : pkg.events) {
                if (!"session-events.jsonl".equals(event.artifact()) || event.get("execution_metrics") == null
                        || event.elements("execution_metrics").stream().noneMatch(m -> m.member("metric_name").text().startsWith("event/agentEvent/"))) continue;
                assertThat(event.text("reported_agent", "uid")).isEqualTo("main");
                assertThat(event.text("reported_agent", "kind")).isEqualTo("MainAgent");
                assertThat(event.get("agent_uid")).isNull();
                for (Json metric : event.elements("execution_metrics")) {
                    assertThat(metric.member("scope_id").scalarText()).isEqualTo("8");
                    count++;
                }
            }
        }
        assertThat(count).isEqualTo(18);
    }

    @Test
    void modelPricingTableBelongsToLatestRequestWhileAmountIsCumulative(@TempDir Path work) {
        Event e = record(read(OcsfFixtures.LIVE_FEATURES, work), "stream.jsonl", 50, "cost_observations");
        Json cost = e.elements("cost_observations").get(0);
        assertThat(cost.path("model_ref", "name").text()).isEqualTo("claude-haiku-5-5");
        assertThat(cost.member("total_cost").scalarText()).isEqualTo("0.009542775");
        assertThat(cost.member("aggregation_id").scalarText()).isEqualTo("2");
        assertThat(cost.member("cost_basis")).isEqualTo(OcsfFixtures.str("list"));
        assertThat(cost.member("cost_basis_window")).isEqualTo(OcsfFixtures.str("most_recent_request"));
    }

    @Test
    void modelLimitsAreCommonCapacitiesNotConsumedTokens(@TempDir Path work) {
        Event e = record(read(OcsfFixtures.LIVE_FEATURES, work), "stream.jsonl", 50, "execution_metrics");
        for (String[] pair : new String[][] {{"model.context_capacity", "1000000"}, {"model.output_limit", "128000"}}) {
            Json metric = e.elements("execution_metrics").stream().filter(m -> pair[0].equals(SourceProfile.text(m, "concept"))).findFirst().orElseThrow();
            assertThat(metric.member("reported_value").scalarText()).isEqualTo(pair[1]);
            assertThat(metric.member("measurement_window").text()).isEqualTo("configuration_snapshot");
            assertThat(metric.member("metric_unit").text()).isEqualTo("token");
        }
    }

    @Test
    void unestablishedCacheZerosMakeUncachedInputConditional(@TempDir Path work) {
        Json usage = record(read(JUNIE, work), "session-events.jsonl", 7, "usage_observations").elements("usage_observations").get(0);
        assertThat(usage.member("uncached_input_tokens")).isNull();
        Json input = usage.member("components").elements().stream().filter(c -> "uncached_input_tokens".equals(c.member("name").text())).findFirst().orElseThrow();
        assertThat(input.member("conditional_value").scalarText()).isEqualTo("772");
        assertThat(input.member("component_basis_id").scalarText()).isEqualTo("3");
        assertThat(input.member("measurement_status_id").scalarText()).isEqualTo("3");
        assertThat(usage.path("data", "inputTokens").scalarText()).isEqualTo("772");
    }

    private static Json selected(List<Json> events, String artifact, int record, String field) {
        return events.stream().filter(e -> artifact.equals(SourceProfile.text(e.member("source_ref"), "artifact"))
                && Integer.toString(record).equals(e.path("source_ref", "record_index").scalarText()) && e.member(field) != null).findFirst().orElseThrow();
    }

    private static void rejected(Path work, String fixtureName, String artifact, String meaning, Consumer<List<Json>> mutation) {
        Path fixture = copy(fixtureName, work);
        OracleReport before = verify(fixture);
        assertThat(before.checks().stream().filter(c -> c.evidence() != null && c.evidence().meaning().equals(meaning)).toList())
                .isNotEmpty().allMatch(c -> c.outcome() == Outcome.VERIFIED);
        editEvents(pkg(fixture), mutation);
        OracleReport after = verify(fixture);
        assertThat(after.outcome("whole_record.structure_value", "main/" + artifact)).isEqualTo(Outcome.VERIFIED);
        assertThat(after.checks()).anyMatch(c -> c.outcome() == Outcome.DEFECTIVE && c.evidence() != null && c.evidence().meaning().equals(meaning));
    }

    @Test
    void childScopeCorruptionIsRejectedWithNativePreservationPassing(@TempDir Path work) {
        rejected(work, JUNIE, "session-events.jsonl", "source.execution_quantity/v2", events -> {
            Json metric = selected(events, "session-events.jsonl", 33, "execution_metrics").member("execution_metrics").elements().get(0);
            obj(metric).members().put("scope_id", num("7"));
        });
    }

    @Test
    void missingCommonPricingBasisIsRejectedEvenWhenRawCostBasisSurvives(@TempDir Path work) {
        rejected(work, LIVE_FEATURES, "stream.jsonl", "source.cost/v2", events -> {
            Json cost = selected(events, "stream.jsonl", 50, "cost_observations").member("cost_observations").elements().get(0);
            obj(cost).members().remove("cost_basis");
        });
    }

    @Test
    void cumulativePricingBasisWindowIsRejected(@TempDir Path work) {
        rejected(work, LIVE_FEATURES, "stream.jsonl", "source.cost/v2", events -> {
            Json cost = selected(events, "stream.jsonl", 50, "cost_observations").member("cost_observations").elements().get(0);
            obj(cost).members().put("cost_basis_window", str("model_cumulative"));
        });
    }

    @Test
    void sourcePathOnlyCapacityEvadesNothing(@TempDir Path work) {
        rejected(work, LIVE_FEATURES, "stream.jsonl", "source.execution_quantity/v2", events -> {
            for (Json metric : selected(events, "stream.jsonl", 50, "execution_metrics").member("execution_metrics").elements()) {
                if ("model.context_capacity".equals(SourceProfile.text(metric, "concept"))) obj(metric).members().remove("concept");
            }
        });
    }

    @Test
    void missingSharedCapacityIsRejectedWithItsNativeValuePreserved(@TempDir Path work) {
        rejected(work, LIVE_FEATURES, "stream.jsonl", "source.execution_quantity/v2", events -> {
            Json e = selected(events, "stream.jsonl", 50, "execution_metrics");
            obj(e).members().put("execution_metrics", new Json.JArray(e.member("execution_metrics").elements().stream()
                    .filter(m -> !"model.output_limit".equals(SourceProfile.text(m, "concept"))).toList()));
        });
    }

    @Test
    void unjustifiedUncachedInputIsRejectedWithRawZerosPreserved(@TempDir Path work) {
        rejected(work, JUNIE, "session-events.jsonl", "junie.usage.3813.7/v2", events -> {
            Json usage = selected(events, "session-events.jsonl", 7, "usage_observations").member("usage_observations").elements().get(0);
            obj(usage).members().put("uncached_input_tokens", num("772"));
        });
    }

    @Test
    void conditionalInputAndPremiseReachTheConsumer(@TempDir Path work) {
        Json view = AnalysisViews.usage(read(JUNIE, work));
        Json observation = view.member("groups").elements().stream()
                .flatMap(g -> g.member("observations").elements().stream())
                .filter(o -> "7".equals(o.member("record_index").scalarText()) && o.member("component_evidence") != null)
                .findFirst().orElseThrow();
        assertThat(observation.member("components").member("uncached_input_tokens")).isNull();
        assertThat(observation.member("component_evidence").elements()).anyMatch(c -> "772".equals(c.member("conditional_value") == null ? null : c.member("conditional_value").scalarText()));
    }

    @Test
    void explicitlyMeasuredSyntheticAnthropicZerosRemainKnown() {
        // Labelled synthetic contrast under the pinned Anthropic disjoint-token definition.
        ExpectedFacts.Tranches measured = TrancheReading.anthropic(NeutralJson.parse(
                "{\"input_tokens\":772,\"cache_read_input_tokens\":0,\"cache_creation_input_tokens\":0,\"output_tokens\":2}"));
        assertThat(measured.values().get("uncached_input_tokens").scalarText()).isEqualTo("772");
        assertThat(measured.conditionals()).isEmpty();
    }

    static List<Json> quantities(OcsfPackage p, String concept, String window) {
        return p.events.stream().flatMap(e -> e.elements("execution_metrics").stream())
                .filter(q -> concept.equals(SourceProfile.text(q, "concept")) && window.equals(SourceProfile.text(q, "measurement_window"))).toList();
    }

    @Test
    void oneCommonSelectorFindsModelRequestCountsAcrossJunieAndCopilot(@TempDir Path work) {
        for (String fixture : List.of(JUNIE, "copilot-live-1.0.100-otel")) {
            List<Json> counts = quantities(read(fixture, work), "model.request_count", "model_cumulative");
            assertThat(counts).isNotEmpty().allMatch(q -> "6".equals(q.member("scope_id").scalarText()) && "count".equals(q.member("metric_unit").text()) && q.member("model_ref") != null);
            assertThat(counts).anyMatch(q -> (fixture.equals(JUNIE) ? "4" : "5").equals(q.member("reported_value").scalarText()));
        }
    }

    @Test
    void exactAntigravitySecondsAndDifferentWindowsStayDistinct(@TempDir Path work) {
        OcsfPackage p = read("antigravity-live-1.4.6-mcp", work);
        assertThat(quantities(p, "duration.elapsed", "conversation_step")).anyMatch(q -> "1.9507197920000001".equals(q.member("reported_value").scalarText()));
        assertThat(quantities(p, "duration.elapsed", "session_cumulative")).singleElement().satisfies(q -> assertThat(q.member("reported_value").scalarText()).isEqualTo("37.323760492"));
    }

    @Test
    void labelledSyntheticSpawnedChildRemainsChildScoped(@TempDir Path work) {
        OcsfPackage p = read("syn-claude-child-runs-2.1.304", work);
        for (String window : List.of("task_tracker_snapshot", "child_result")) {
            assertThat(quantities(p, "tool.invocation_count", window)).isNotEmpty().allMatch(q -> "7".equals(q.member("scope_id").scalarText()));
        }
    }
}
