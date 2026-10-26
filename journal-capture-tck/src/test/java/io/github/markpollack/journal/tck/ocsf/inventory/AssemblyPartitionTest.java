package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACCT-1 repair of review finding CDX-R1D-03: the assembly partition reads lifecycle stages from the native records,
 * so a candidate cannot hide a repeated native identity by labelling one of the two records another stage. Labelled
 * synthetic inputs: two native tool completions of one call id in one source, and a candidate that consolidates them
 * after relabelling one as a start.
 */
class AssemblyPartitionTest {

    static final String SOURCE = "session-events.jsonl";

    static NativeArtifact twoCompletions() {
        String lines = "{\"type\":\"tool.execution_complete\",\"id\":\"e1\",\"data\":{\"toolCallId\":\"call_1\",\"result\":\"a\"}}\n"
                + "{\"type\":\"tool.execution_complete\",\"id\":\"e2\",\"data\":{\"toolCallId\":\"call_1\",\"result\":\"a\"}}\n";
        return NativeArtifact.ofBytes(Path.of(SOURCE), lines.getBytes(StandardCharsets.UTF_8));
    }

    /** One observation of the candidate: a tool_call at the given activity for the given native record, with its assembly fields. */
    static Event observation(int line, int record, int activity, int role, String occurrence, String entity, Integer relation) {
        String assembly = "{\"assembly_role_id\": " + role + ", \"occurrence_uid\": \"" + occurrence + "\", \"entity_uid\": \"" + entity + "\""
                + (relation == null ? "" : ", \"relation_class_id\": " + relation) + "}";
        Json json = NeutralJson.parse("{\"class_uid\": 90111003, \"activity_id\": " + activity + ", \"tool_call_uid\": \"call_1\", \"source_ref\": {\"artifact\": \"" + SOURCE
                + "\", \"record_index\": " + record + "}, \"assembly\": " + assembly + "}");
        return new Event(line, json, "tool_call");
    }

    static Json expectation(boolean nativeStage) {
        String stage = nativeStage ? ", \"stage\": {\"pointer\": \"/type\"}" : "";
        return NeutralJson.parse("{\"kind\": \"agent-journal/assembly-expectation\", \"entities\": [{\"name\": \"tool result\", \"class\": \"tool_call\", "
                + "\"locators\": {\"session\": {\"select\": {\"/type\": \"tool.execution_complete\"}, \"pointer\": \"/data/toolCallId\"" + stage + "}}}]}");
    }

    static OracleReport partition(List<Event> observations, Json spec, Map<String, Set<String>> consolidated) {
        OracleReport report = new OracleReport();
        AssemblyPartition.check(report, observations, spec, List.of(new AssemblyOracle.Supplied(SOURCE, "session", "import-1", twoCompletions(), null)), consolidated);
        return report;
    }

    @Test
    void aRelabelledDuplicateIsStillAmbiguousWhenTheStageComesFromTheNativeRecord() {
        // the candidate labels the second completion a start (activity 4) and consolidates both into one entity
        List<Event> relabelled = List.of(observation(1, 1, 2, 2, "o1", "ent", null), observation(2, 2, 4, 2, "o2", "ent", null));
        Map<String, Set<String>> consolidated = Map.of("ent", Set.of("o1", "o2"));
        // without a native stage rule the partition accepts the candidate's stages and says so: UNRESOLVED, not VERIFIED
        OracleReport candidateStages = partition(relabelled, expectation(false), consolidated);
        Check c = candidateStages.check("assembly.entities", "assembly");
        assertThat(c.outcome()).isEqualTo(Check.Outcome.UNRESOLVED);
        assertThat(c.detail()).contains("lifecycle stage taken from the candidate (no native stage rule) for [tool result in session]");
        // with the native stage (both records are tool.execution_complete) the identity repeats a stage: ambiguous, and the consolidation is a defect
        OracleReport nativeStages = partition(relabelled, expectation(true), consolidated);
        Check n = nativeStages.check("assembly.entities", "assembly");
        assertThat(n.outcome()).isEqualTo(Check.Outcome.DEFECTIVE);
        assertThat(n.detail()).contains("should be a canonical single observation flagged ambiguous");
        // the honest candidate: two single observations each flagged ambiguous (relation 6), verified with native stages
        List<Event> flagged = List.of(observation(1, 1, 2, 1, "o1", "e1", 6), observation(2, 2, 2, 1, "o2", "e2", 6));
        Check ok = partition(flagged, expectation(true), Map.of()).check("assembly.entities", "assembly");
        assertThat(ok.outcome()).isEqualTo(Check.Outcome.VERIFIED);
        assertThat(ok.detail()).contains("2 ambiguous observation(s)").contains("lifecycle stages read from the native records");
    }

    @Test
    void aNativeStageRuleReadsTheRecordNotTheCandidate() {
        Json entity = NeutralJson.parse("{\"name\": \"assistant record\", \"class\": \"model_response\"}");
        Json byStopReason = NeutralJson.parse("{\"pointer\": \"/message/stop_reason\", \"null\": \"streamed\", \"otherwise\": \"final\"}");
        assertThat(AssemblyPartition.nativeStage(entity, NeutralJson.parse("{\"stage\": " + NeutralJson.write(byStopReason) + "}"),
                NeutralJson.parse("{\"message\": {\"stop_reason\": null}}"))).isEqualTo("streamed");
        assertThat(AssemblyPartition.nativeStage(entity, NeutralJson.parse("{\"stage\": " + NeutralJson.write(byStopReason) + "}"),
                NeutralJson.parse("{\"message\": {\"stop_reason\": \"end_turn\"}}"))).isEqualTo("final");
        assertThat(AssemblyPartition.nativeStage(entity, NeutralJson.parse("{\"stage\": {\"pointer\": \"/type\"}}"), NeutralJson.parse("{\"type\": \"assistant.message\"}")))
                .isEqualTo("assistant.message");
        assertThat(AssemblyPartition.nativeStage(entity, NeutralJson.parse("{}"), NeutralJson.parse("{\"type\": \"x\"}"))).as("no rule, no single activity: not derivable").isNull();
        assertThat(AssemblyPartition.nativeStage(NeutralJson.parse("{\"name\": \"tool start\", \"class\": \"tool_call\", \"activities\": [4]}"), NeutralJson.parse("{}"),
                NeutralJson.parse("{}"))).isEqualTo("activity 4");
    }
}
