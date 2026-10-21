package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CHILD;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.CODEX;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.at;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.open;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.subsetDifferences;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The semantic questions, answered from the core alone and compared with expected answers written from the native
 * records ({@code expected-views.json} beside each fixture, authored by hand from the fixture's own content, not
 * generated from the views): one submission despite repeated representations, environment context not a submission,
 * identical text in a new turn a new submission; one response despite two native updates, identical content under a
 * second id separate; applicable final usage per scope/owner/source with repeats counted once and nothing summed
 * across cores; derived versus reported association; identical results kept separate.
 */
class AnalysisViewsTest {

    private static Json expected(String fixture) throws Exception {
        return NeutralJson.parse(new String(AnalysisViewsTest.class.getResourceAsStream("/ocsf/fixtures/" + fixture + "/expected-views.json")
                .readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void syntheticCodexViewsMatchTheIndependentlyAuthoredAnswers(@TempDir Path work) throws Exception {
        Json views = AnalysisViews.all(open(copy(CODEX, work)));
        assertThat(subsetDifferences(expected(CODEX), views, "$")).isEmpty();
    }

    @Test
    void claudeChildViewsMatchTheIndependentlyAuthoredAnswers(@TempDir Path work) throws Exception {
        Json views = AnalysisViews.all(open(copy(CHILD, work)));
        assertThat(subsetDifferences(expected(CHILD), views, "$")).isEmpty();
    }

    @Test
    void submissionsCountRepresentationsOnceAndNewIdenticalTextAgain(@TempDir Path work) {
        Json s = AnalysisViews.submissions(open(copy(CODEX, work)));
        assertThat(s.member("submission_count").scalarText()).isEqualTo("2");
        List<Json> rows = s.member("rows").elements();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).member("classification").text()).isEqualTo("environment_context");
        assertThat(rows.get(0).member("counts_as_submission")).isEqualTo(new Json.JBool(false));
        assertThat(rows.get(1).member("occurrences").elements()).as("response_item message + item_completed UserMessage of one turn").hasSize(2);
        assertThat(rows.get(2).member("occurrences").elements()).hasSize(2);
        assertThat(rows.get(1).member("text_sha256")).as("identical text").isEqualTo(rows.get(2).member("text_sha256"));
        assertThat(rows.get(1).member("turn_uid")).as("different turns").isNotEqualTo(rows.get(2).member("turn_uid"));
    }

    @Test
    void responsesAssembleUpdatesAndKeepIdenticalContentUnderDifferentIdsSeparate(@TempDir Path work) {
        Json r = AnalysisViews.responses(open(copy(CODEX, work)));
        assertThat(r.member("identity_count").scalarText()).isEqualTo("3");
        List<Json> rows = r.member("rows").elements();
        assertThat(rows.get(0).member("occurrence_count").scalarText()).isEqualTo("2");
        Json art = rows.get(0).path("artifacts", "rollout.jsonl");
        assertThat(art.member("update_semantics").text()).isEqualTo("SNAPSHOT");
        assertThat(art.member("snapshot_consistency").text()).isEqualTo("text equal; native block structure differs");
        assertThat(rows.get(0).member("response_uid").text()).isEqualTo("msg_a_1");
        assertThat(rows.get(2).member("response_uid").text()).isEqualTo("msg_a_3");
        assertThat(rows.get(0).path("artifacts", "rollout.jsonl", "assembled_blocks")).as("same text, separate responses")
                .isEqualTo(rows.get(2).path("artifacts", "rollout.jsonl", "assembled_blocks"));
    }

    @Test
    void claudeResponsesAppendBlocksAndTheChildCoreIsAssembledOnItsOwn(@TempDir Path work) {
        OcsfPackage p = open(copy(CHILD, work));
        Json r = AnalysisViews.responses(p);
        assertThat(r.member("identity_count").scalarText()).isEqualTo("3");
        Json first = r.member("rows").elements().get(1);
        assertThat(first.member("occurrence_count").scalarText()).isEqualTo("3");
        assertThat(first.path("artifacts", "stream.jsonl", "assembled_blocks").elements()).extracting(b -> b.member("block_type").text())
                .containsExactly("thinking", "text", "tool_use");
        Json child = AnalysisViews.responses(p.children.get(0));
        assertThat(child.member("identity_count").scalarText()).isEqualTo("1");
    }

    @Test
    void usageSelectsOneApplicableFinalPerGroupAndNeverSums(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        Json u = AnalysisViews.usage(open(fixture));
        List<Json> groups = u.member("groups").elements();
        assertThat(groups).hasSize(2);
        Json turn1 = groups.get(0);
        assertThat(turn1.member("owner").text()).isEqualTo("turn-0001-synthetic");
        assertThat(turn1.path("applicable_final", "input_tokens").scalarText()).as("the last cumulative token_count, not the sum 1000+2100").isEqualTo("2100");
        assertThat(turn1.member("provisional_observations").scalarText()).isEqualTo("1");
        assertThat(turn1.member("final_observations").scalarText()).isEqualTo("1");
        assertThat(u.member("aggregation").text()).startsWith("none");
        // repeated identical finals are counted once; differing finals are a conflict with no applicable value
        editEvents(pkg(fixture), events -> {
            Json turn = at(events, "prompt_turn", 2, 17);
            List<Json> obs = turn.member("usage_observations").elements();
            Json finalObs = obs.get(obs.size() - 1);
            obs.add(OcsfFixtures.copy(finalObs));
        });
        Json repeated = AnalysisViews.usage(open(fixture)).member("groups").elements().get(0);
        assertThat(repeated.member("final_observations").scalarText()).isEqualTo("2");
        assertThat(repeated.member("repeated_final_observations").scalarText()).isEqualTo("1");
        assertThat(repeated.path("applicable_final", "input_tokens").scalarText()).isEqualTo("2100");
        editEvents(pkg(fixture), events -> {
            Json turn = at(events, "prompt_turn", 2, 17);
            List<Json> obs = turn.member("usage_observations").elements();
            obj(obs.get(obs.size() - 1)).members().put("input_tokens", num("2101"));
        });
        Json conflict = AnalysisViews.usage(open(fixture)).member("groups").elements().get(0);
        assertThat(conflict.member("conflict")).isEqualTo(new Json.JBool(true));
        assertThat(conflict.member("applicable_final").isNull()).isTrue();
    }

    @Test
    void parentAndChildUsageStayInSeparateGroups(@TempDir Path work) {
        OcsfPackage p = open(copy(CHILD, work));
        Json main = AnalysisViews.usage(p);
        Json child = AnalysisViews.usage(p.children.get(0));
        assertThat(main.member("groups").elements()).extracting(g -> g.member("scope").text()).contains("Run", "Model", "Model response");
        assertThat(child.member("groups").elements()).hasSize(1);
        assertThat(child.member("groups").elements().get(0).member("owner").text()).isEqualTo("msg_011Cg2aSBYeTae2cPghGn3eT");
        assertThat(child.member("groups").elements().get(0).member("applicable_final").isNull()).as("the child reports only a provisional observation").isTrue();
        assertThat(main.member("groups").elements()).extracting(g -> g.member("owner").text()).doesNotContain("msg_011Cg2aSBYeTae2cPghGn3eT");
    }

    @Test
    void associationsSeparateCandidateCountFromEvidenceMethod(@TempDir Path work) {
        Json a = AnalysisViews.associations(open(copy(CODEX, work)));
        List<Json> rows = a.member("rows").elements();
        Json unique = rows.get(0);
        assertThat(unique.member("outcome").text()).isEqualTo("Unique");
        assertThat(unique.member("method").text()).isEqualTo("Derived by rule");
        assertThat(unique.member("reported_parent").isNull()).as("a unique text match is not a reported parent").isTrue();
        assertThat(unique.member("derived_association").text()).isEqualTo("call_0001aaaa");
        Json ambiguous = rows.get(2);
        assertThat(ambiguous.member("outcome").text()).isEqualTo("Ambiguous");
        assertThat(ambiguous.member("candidate_count").scalarText()).isEqualTo("2");
        assertThat(ambiguous.member("stored_tool_call_uid").isNull()).isTrue();
        Json claude = AnalysisViews.associations(open(copy(CHILD, work.resolve("c"))));
        Json decision = claude.member("rows").elements().get(0);
        assertThat(decision.member("fact").text()).isEqualTo("permission_decision");
        assertThat(decision.member("method").text()).isEqualTo("Reported identifier");
        assertThat(decision.member("attached_to_tool_call").text()).isEqualTo("toolu_0183ddwvMmvPzqAU5Jeu5hwZ");
    }

    @Test
    void identicalResultContentUnderTwoOwnersIsKeptSeparate(@TempDir Path work) {
        Json t = AnalysisViews.toolExchanges(open(copy(CODEX, work)));
        assertThat(t.member("calls").elements()).hasSize(3);
        List<Json> identical = t.member("identical_content_distinct_owners").elements();
        assertThat(identical).hasSize(1);
        assertThat(identical.get(0).member("owners").elements()).extracting(Json::text).containsExactly("call_0002bbbb", "call_0003cccc");
        assertThat(identical.get(0).member("kept_separate")).isEqualTo(new Json.JBool(true));
    }

    @Test
    void viewsRequireTheSemanticContractFromTheCore(@TempDir Path work) {
        Path fixture = copy(CODEX, work);
        OcsfFixtures.editManifest(pkg(fixture), run -> run.members().remove("semantic_contract"));
        OcsfPackage p = open(fixture);
        assertThat(p.diagnosticsWithCode("SEMANTIC_CONTRACT_ABSENT")).hasSize(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AnalysisViews.submissions(p)).hasMessageContaining("unavailable from this core");
        // a classifier that no longer matches changes the answer, visibly
        Path other = copy(CODEX, work.resolve("o"));
        OcsfFixtures.editManifest(pkg(other), run -> {
            Json decl = run.path("semantic_contract", "declarations").elements().get(0);
            obj(decl.member("classifiers").elements().get(0)).members().put("first_block_starts_with", str("<never>"));
            obj(run.get("semantic_contract")).members().remove("sha256");
        });
        assertThat(AnalysisViews.submissions(open(other)).member("submission_count").scalarText()).as("environment context now counts").isEqualTo("3");
    }

    @Test
    void settingsContextRowsListEachRecordAtItsScopeAndSessionUsageIsOwnedByTheSession(@TempDir Path work) {
        Path fixture = copy(OracleFullSourceTest.PAIR, work);
        Json views = AnalysisViews.all(open(fixture));
        Json sc = views.member("settings_context");
        assertThat(sc.member("settings_rows").scalarText()).isEqualTo("1");
        assertThat(sc.member("context_rows").scalarText()).isEqualTo("4");
        assertThat(sc.member("accounting_rows").scalarText()).isEqualTo("1");
        assertThat(sc.member("rule").text()).isEqualTo("settings.context.v1");
        List<Json> rows = sc.member("rows").elements();
        assertThat(rows).hasSize(6);
        Json init = rows.get(0);
        assertThat(init.member("activity").text()).isEqualTo("Settings reported");
        assertThat(init.member("context_kind").text()).isEqualTo("system/init");
        assertThat(init.member("scope").text()).isEqualTo("session");
        assertThat(init.path("settings", "working_directory").text()).isEqualTo("/work");
        assertThat(init.path("settings", "permission_mode").text()).isEqualTo("default");
        assertThat(init.path("settings", "tool_names").elements()).isNotEmpty();
        assertThat(init.member("usage_observations").scalarText()).isEqualTo("0");
        assertThat(((Json.JBool) init.member("cost_reported")).value()).isFalse();
        Json environment = rows.get(1);
        assertThat(environment.member("activity").text()).isEqualTo("Context injected");
        assertThat(environment.member("context_kind").text()).isEqualTo("attachment/environment");
        assertThat(environment.member("rendered_role").text()).isEqualTo("system");
        assertThat(environment.member("context_blocks").scalarText()).isEqualTo("1");
        assertThat(Integer.parseInt(environment.member("context_text_chars").scalarText())).isGreaterThan(20);
        Json credential = rows.get(3);
        assertThat(credential.member("context_kind").text()).isEqualTo("attachment/credential_org");
        assertThat(credential.member("rendered_role")).isNull();
        assertThat(credential.member("context_blocks")).isNull();
        Json cost = rows.get(5);
        assertThat(cost.member("activity").text()).isEqualTo("Accounting reported");
        assertThat(cost.member("usage_observations").scalarText()).isEqualTo("1");
        assertThat(((Json.JBool) cost.member("cost_reported")).value()).isTrue();
        // the session-scope usage group is owned by the session id, from the transcript source only
        Json session = null;
        for (Json g : views.path("usage", "groups").elements()) {
            if ("Session".equals(g.member("scope").text())) {
                session = g;
            }
        }
        assertThat(session).isNotNull();
        assertThat(session.member("owner").text()).isEqualTo("00000000-0000-4000-8000-000000000000");
        assertThat(session.member("source").text()).isEqualTo("transcript.jsonl");
        assertThat(session.path("applicable_final", "output_tokens").scalarText()).isEqualTo("1362");
        // turn-scoped settings keep their turn id (Codex)
        Json codex = AnalysisViews.all(open(copy(CODEX, work.resolve("codex")))).member("settings_context");
        Json turnContext = codex.member("rows").elements().get(1);
        assertThat(turnContext.member("context_kind").text()).isEqualTo("turn_context");
        assertThat(turnContext.member("scope").text()).isEqualTo("turn");
        assertThat(turnContext.member("turn_uid").text()).isNotNull();
        assertThat(turnContext.path("settings", "approval_policy").text()).isEqualTo("never");
    }
}
