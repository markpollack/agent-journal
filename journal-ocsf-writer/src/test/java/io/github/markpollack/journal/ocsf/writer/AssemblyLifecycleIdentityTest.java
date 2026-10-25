package io.github.markpollack.journal.ocsf.writer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lifecycle identity under a staged join (review finding CDX-R1-07), on labelled synthetic observations of the bundled
 * Claude profile's {@code claude.assistant_record} join (key: the record uuid; stages 1, 3, 2). One source reporting
 * one identity at two declared stages by one rule is one entity's lifecycle, not a repeated identity; the same identity
 * twice at one stage stays ambiguous, unpaired and flagged.
 */
class AssemblyLifecycleIdentityTest {

    static final String RULE = "synthetic.assistant->model_response";

    static AssemblyPlan plan() {
        AssemblyProfile profile = AssemblyProfile.bundled("claude-code-2.1.304-stdout-session-file");
        return new AssemblyPlan(profile, List.of(new AssemblyPlan.Binding("stream.jsonl", "stream", "synthetic stream", "synthetic acquisition")), new Jv.Arr());
    }

    static Assembler.Observation observation(int record, int activity, int arrival) {
        Jv.Obj event = new Jv.Obj().put("activity_id", activity).put("source_ref", new Jv.Obj().put("record_index", record).put("native_uid", "uuid-a"))
                .put("journal", new Jv.Obj().put("rule_uid", RULE));
        return new Assembler.Observation("stream.jsonl", null, record, 0, "model_response", arrival, event);
    }

    @Test
    void oneIdentityAtTwoDeclaredStagesOfOneSourceIsOneEntitysLifecycle() {
        List<AssemblyGrouping.Group> groups = AssemblyGrouping.group(plan(), List.of(observation(5, 1, 0), observation(9, 2, 1)));
        assertThat(groups).singleElement().satisfies(g -> {
            assertThat(g.members()).hasSize(2);
            assertThat(g.ambiguous()).as("a request and its completion are stages, not a duplicate").isFalse();
            assertThat(g.members().get(g.envelope()).record()).as("the most advanced stage carries the envelope").isEqualTo(9);
        });
    }

    static Assembler.Observation withUsage(String artifact, int arrival, Jv.Obj usage) {
        Jv.Obj event = new Jv.Obj().put("activity_id", 3).put("tool_call_uid", "toolu_x").put("source_ref", new Jv.Obj().put("record_index", 1))
                .put("usage_observations", new Jv.Arr(List.of(usage)));
        return new Assembler.Observation(artifact, null, 1, 0, "session_context", arrival, event);
    }

    @Test
    void aFinalObservationOfAnotherMeasureNeverSupersedesAProvisionalOne() {
        // a provisional task figure (no aggregation) and a final one-response usage (aggregation 3) of one child run:
        // two quantities, both carried; only a final of the same scope, aggregation and model supersedes
        Jv.Obj taskFigure = new Jv.Obj().put("scope_id", 7).put("scope", "Child run").put("usage_status_id", 1).put("reported_total_tokens", 21161);
        Jv.Obj lastResponse = new Jv.Obj().put("scope_id", 7).put("scope", "Child run").put("usage_status_id", 2).put("aggregation_id", 3).put("output_tokens", 242);
        EventMerge.Result r = EventMerge.merge(List.of(withUsage("stream.jsonl", 0, taskFigure), withUsage("transcript.jsonl", 1, lastResponse)));
        assertThat(r.event().get("usage_observations").elements()).hasSize(2);
        assertThat(r.evidence().elements()).noneMatch(e -> e.member("relation_class_id") instanceof Jv.Num n && n.value().intValue() == 8);
        // the same measure reported Final by the other source does supersede the provisional figure
        Jv.Obj finalTask = new Jv.Obj().put("scope_id", 7).put("scope", "Child run").put("usage_status_id", 2).put("reported_total_tokens", 21189);
        EventMerge.Result same = EventMerge.merge(List.of(withUsage("stream.jsonl", 0, taskFigure), withUsage("transcript.jsonl", 1, finalTask)));
        assertThat(same.event().get("usage_observations").elements()).singleElement().satisfies(u -> assertThat(Jv.write(u.member("reported_total_tokens"))).isEqualTo("21189"));
        assertThat(same.evidence().elements()).anyMatch(e -> e.member("relation_class_id") instanceof Jv.Num n && n.value().intValue() == 8);
    }

    @Test
    void oneIdentityTwiceAtOneStageOfOneSourceStaysAmbiguous() {
        List<AssemblyGrouping.Group> groups = AssemblyGrouping.group(plan(), List.of(observation(5, 1, 0), observation(7, 1, 1)));
        assertThat(groups).singleElement().satisfies(g -> assertThat(g.ambiguous()).as("which record is the occurrence is not established").isTrue());
    }
}
