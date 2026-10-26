package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACCT-1, the Claude paired-source check (handoff: "execute the paired-source checks and derive final expectations
 * independently"). The stdout stream of {@code claude-live-2.1.304-mcp-pair} reports every assistant record with
 * {@code stop_reason} null and provisional usage; the native session file the same launch saved reports the same
 * records (same uuid) with their stop reason and final usage. The expectations below are read from the two native
 * files alone, never from the assembled file or the writer: per assistant record (uuid), the final output tokens from
 * the session file and the provisional ones from the stream; per API message (message.id), how many records carry
 * it. The assembled package must carry exactly one canonical model_response per uuid whose usage is the session file's
 * Final value, keep the stream's provisional observation as supporting evidence (never counted again), and never sum
 * the two or count one API message twice because two records carry its usage.
 */
class PairedSourceFinalUsageTest {

    static final String PAIR = "claude-live-2.1.304-mcp-pair";

    /** One assistant record as a native file reports it. */
    record Reported(int record, String uuid, String messageId, Json outputTokens, String stopReason) {
    }

    static List<Reported> assistants(Path artifact) {
        List<Reported> out = new ArrayList<>();
        for (NativeArtifact.Record r : NativeArtifact.read(artifact).records()) {
            if (r.parsed() && "assistant".equals(SourceProfile.text(r.json(), "type"))) {
                Json m = r.json().member("message");
                out.add(new Reported(r.index(), SourceProfile.text(r.json(), "uuid"), SourceProfile.text(m, "id"), m.path("usage", "output_tokens"), SourceProfile.text(m, "stop_reason")));
            }
        }
        return out;
    }

    @Test
    void theSessionFilesFinalUsageIsCanonicalAndTheStreamsProvisionalUsageIsEvidenceOnly(@TempDir Path work) {
        Path fixture = OcsfFixtures.copy(PAIR, work);
        List<Reported> stream = assistants(fixture.resolve("native/stream.jsonl"));
        List<Reported> transcript = assistants(fixture.resolve("native/transcript.jsonl"));
        // the acquisition boundary the review stated, read from the bytes: no stream record is final, every session-file record is
        assertThat(stream).hasSize(6).allMatch(r -> r.stopReason() == null);
        assertThat(transcript).hasSize(6).allMatch(r -> r.stopReason() != null);
        Map<String, Reported> finals = new LinkedHashMap<>();
        transcript.forEach(r -> finals.put(r.uuid(), r));
        assertThat(finals.keySet()).containsExactlyInAnyOrderElementsOf(stream.stream().map(Reported::uuid).toList());
        // the example the handoff names: one response's first record says 8 output tokens live and 184 when final
        Reported first = stream.get(0);
        assertThat(first.messageId()).isEqualTo("msg_011CgKzSHFoJacdngyy7dCti");
        assertThat(first.outputTokens().scalarText()).isEqualTo("8");
        assertThat(finals.get(first.uuid()).outputTokens().scalarText()).isEqualTo("184");

        OcsfPackage pkg = OcsfFixtures.open(fixture);
        List<Event> responses = pkg.events.stream().filter(e -> "model_response".equals(e.className)).toList();
        for (Reported want : transcript) {
            List<Event> canonical = responses.stream().filter(e -> e.canonical() && want.uuid().equals(e.text("metadata", "original_event_uid"))).toList();
            assertThat(canonical).as("one canonical response per assistant record " + want.uuid()).hasSize(1);
            Event e = canonical.get(0);
            assertThat(e.activityId).as("consolidated at the most advanced stage the sources report").isEqualTo(2);
            assertThat(e.text("stop_reason")).isEqualTo(want.stopReason());
            List<Json> usage = e.elements("usage_observations");
            assertThat(usage).as("exactly one usage observation on the canonical response").hasSize(1);
            assertThat(Event.integer(usage.get(0).member("usage_status_id"))).as("Final").isEqualTo(2);
            assertThat(usage.get(0).member("output_tokens").scalarText()).as("the session file's final figure, not the stream's").isEqualTo(want.outputTokens().scalarText());
            assertThat(Event.integer(usage.get(0).path("source_ref", "record_index"))).isEqualTo(want.record());
            assertThat(usage.get(0).path("source_ref", "artifact").text()).isEqualTo("transcript.jsonl");
            // the stream's observation of the same record is kept as supporting evidence with its own provisional figure, never summed
            Reported live = stream.stream().filter(s -> s.uuid().equals(want.uuid())).findFirst().orElseThrow();
            List<Event> supporting = responses.stream().filter(x -> Integer.valueOf(2).equals(x.assemblyRole()) && want.uuid().equals(x.text("metadata", "original_event_uid"))
                    && "stream.jsonl".equals(x.artifact())).toList();
            assertThat(supporting).hasSize(1);
            Json provisional = supporting.get(0).elements("usage_observations").get(0);
            assertThat(Event.integer(provisional.member("usage_status_id"))).isEqualTo(1);
            assertThat(provisional.member("output_tokens").scalarText()).isEqualTo(live.outputTokens().scalarText());
            assertThat(usage.get(0).member("output_tokens").scalarText()).isNotEqualTo(sum(live.outputTokens(), want.outputTokens()));
        }
        // two records carry the first API message (two content blocks of one response): the usage view counts its equal Final observations once
        long recordsOfFirst = transcript.stream().filter(r -> r.messageId().equals(first.messageId())).count();
        assertThat(recordsOfFirst).isEqualTo(2);
        Json group = AnalysisViews.usage(pkg).member("groups").elements().stream()
                .filter(g -> first.messageId().equals(SchemaCatalog.text(g, "owner")) && "Model response".equals(SchemaCatalog.text(g, "scope"))).findFirst().orElseThrow();
        assertThat(group.member("final_observations").scalarText()).isEqualTo("2");
        assertThat(group.member("repeated_final_observations").scalarText()).isEqualTo("1");
        assertThat(group.member("applicable_final").member("output_tokens").scalarText()).isEqualTo("184");
        assertThat(group.member("provisional_observations").scalarText()).as("the canonical projection carries no provisional observation for the response").isEqualTo("0");
        // the run and model windows stay the stream's and are not confused with the response windows
        List<Event> turns = pkg.events("prompt_turn", 2).stream().filter(Event::canonical).toList();
        assertThat(turns).hasSize(1);
        assertThat(turns.get(0).artifact()).isEqualTo("stream.jsonl");
        assertThat(turns.get(0).elements("usage_observations").stream().map(u -> Event.integer(u.member("scope_id")) + "/" + Event.integer(u.member("aggregation_id"))))
                .containsExactlyInAnyOrder("4/null", "6/2", "4/3");
    }

    static String sum(Json a, Json b) {
        return ((Json.JNumber) a).value().add(((Json.JNumber) b).value()).toPlainString();
    }

    /** The oracle's own transcript reading expects the same finals: the test above and the profile agree without sharing code. */
    @Test
    void theTranscriptProfileReadsTheSameFinalFigures(@TempDir Path work) {
        Path fixture = OcsfFixtures.copy(PAIR, work);
        NativeArtifact transcript = NativeArtifact.read(fixture.resolve("native/transcript.jsonl"));
        ExpectedFacts facts = SourceProfile.forName("claude_transcript").expected(transcript, io.github.markpollack.journal.tck.verify.NeutralJson.parse(
                "{\"artifact\": \"transcript.jsonl\", \"profile\": \"claude_transcript\"}"), null);
        Map<Integer, String> byRecord = new LinkedHashMap<>();
        facts.usages.stream().filter(u -> u.scopeId() == 2).forEach(u -> byRecord.put(u.recordIndex(), u.statusId() + ":" + u.components().get("output_tokens").scalarText()));
        for (Reported r : assistants(fixture.resolve("native/transcript.jsonl"))) {
            assertThat(byRecord.get(r.record())).isEqualTo("2:" + r.outputTokens().scalarText());
        }
        assertThat(facts.usages.stream().filter(u -> u.scopeId() == 2).map(u -> Objects.requireNonNull(u.statusId()))).allMatch(s -> s == 2);
    }
}
