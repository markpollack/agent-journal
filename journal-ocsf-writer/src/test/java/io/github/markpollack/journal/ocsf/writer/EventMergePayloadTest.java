package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review F1 (main aa75c02..11e3714): provenance is decided by the schema's declared payload attributes, never by key
 * names. These are merge-rule controls on two observations of one tool request, not vendor-shaped fixtures: in real Claude
 * Code output the stream and the session file write a request's input identically, so neither shape below has been
 * recorded. The rule they pin is that a native payload's own keys never make it provenance.
 */
class EventMergePayloadTest {
    private static Assembler.Observation request(String occurrence, int record, String arguments) {
        Jv.Obj event = new Jv.Obj().put("class_uid", 90111003).put("class_name", "Tool Call").put("activity_id", 1).put("tool_call_uid", "toolu_A");
        if (arguments != null) { event.put("arguments", Jv.parse(arguments)); }
        return new Assembler.Observation(occurrence, null, record, 0, "tool_call", record, event);
    }

    @Test void theArgumentsSchemaTypeIsAPayload() {
        assertThat(EventMerge.PAYLOADS).contains("arguments", "structured_result").doesNotContain("data", "mcp", "basis");
        assertThat(EventMerge.inPayload("/arguments/basis")).isTrue();
        assertThat(EventMerge.inPayload("/mcp/basis")).isFalse();
    }

    @Test void argumentsOnlyOneObservationReportsAreItsRicherFactWhateverTheirKeys() {
        EventMerge.Result r = EventMerge.merge(List.of(request("stdout.jsonl", 1, "{\"basis\":\"business input\",\"data\":42}"), request("transcript.jsonl", 2, null)));
        assertThat(r.relation()).isEqualTo(3);
        assertThat(Jv.write(r.evidence())).contains("\"field_pointer\":\"/arguments\"");
    }

    @Test void argumentsWhoseDataDiffersConflictWhateverTheirKeys() {
        EventMerge.Result r = EventMerge.merge(List.of(request("stdout.jsonl", 1, "{\"basis\":\"business input\",\"data\":42}"),
                request("transcript.jsonl", 2, "{\"basis\":\"business input\",\"data\":43}")));
        assertThat(r.relation()).isEqualTo(5);
        assertThat(Jv.write(r.conflicts())).contains("\"field_pointer\":\"/arguments/data\"");
        assertThat(r.event().member("arguments").member("data")).isNull();
    }

    /**
     * Re-review F1: a payload member named like Journal's partial-projection list is a plain fact. Differing
     * {@code /arguments/missing_required} arrays conflict; they are not intersected.
     */
    @Test void aMissingRequiredArrayInsideArgumentsConflictsRatherThanIntersecting() {
        EventMerge.Result r = EventMerge.merge(List.of(request("stdout.jsonl", 1, "{\"missing_required\":[\"a\",\"b\"]}"),
                request("transcript.jsonl", 2, "{\"missing_required\":[\"a\"]}")));
        assertThat(r.relation()).isEqualTo(5);
        assertThat(Jv.write(r.conflicts())).contains("\"field_pointer\":\"/arguments/missing_required\"");
    }

    /** Re-review F1: an array inside a payload named like a Journal identified array is compared whole, not by Journal's identity. */
    @Test void anIdentifiedArrayNameInsideArgumentsIsComparedWhole() {
        EventMerge.Result r = EventMerge.merge(List.of(request("stdout.jsonl", 1, "{\"content_blocks\":[{\"block_index\":0,\"x\":1}]}"),
                request("transcript.jsonl", 2, "{\"content_blocks\":[{\"block_index\":0,\"x\":2}]}")));
        assertThat(r.relation()).isEqualTo(5);
        assertThat(Jv.write(r.conflicts())).contains("\"field_pointer\":\"/arguments/content_blocks\"").doesNotContain("/arguments/content_blocks/0");
    }

    /** Payloads are recognized by attribute name, which is sound while no payload name is declared with another type. */
    @Test void noPayloadNameIsDeclaredWithAnotherType() {
        Jv compiled = SchemaPin.bundled().compiled();
        java.util.Map<String, java.util.Set<String>> types = new java.util.TreeMap<>();
        for (String scope : List.of("objects", "classes")) {
            for (Jv definition : ((Jv.Obj) compiled.member(scope)).members().values()) {
                if (!(definition.member("attributes") instanceof Jv.Obj attributes)) { continue; }
                attributes.members().forEach((name, attribute) -> types.computeIfAbsent(name, k -> new java.util.TreeSet<>())
                        .add(attribute.member("type") == null ? "object" : attribute.member("type").text()));
            }
        }
        assertThat(EventMerge.PAYLOADS).allSatisfy(name -> assertThat(types.get(name)).as(name).containsExactly("json_t"));
    }

    @Test void journalsOwnBasisOnlyMemberStillTakesNoEvidence() {
        Assembler.Observation a = request("stdout.jsonl", 1, null), b = request("transcript.jsonl", 2, null);
        ((Jv.Obj) a.event()).put("mcp", Jv.parse("{\"basis\":\"claude.stream.mcp.v1: the result of the MCP call\"}"));
        EventMerge.Result r = EventMerge.merge(List.of(a, b));
        assertThat(r.relation()).isEqualTo(1);
        assertThat(Jv.write(r.evidence())).doesNotContain("/mcp");
    }
}
