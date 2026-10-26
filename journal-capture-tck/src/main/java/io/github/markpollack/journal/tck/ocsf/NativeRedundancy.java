package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Checks explicitly named repeated views in the native record. Equal values never choose their
 * association.
 */
final class NativeRedundancy {

    record Reading(
            OracleReport report, String artifact, String owner, NativeArtifact.Record record) {
        void compare(String original, String repeated) {
            compare(FactComparison.at(record.json(), original), original, repeated);
        }

        void compare(Json expected, String original, String repeated) {
            compare(expected, record.index(), original, repeated);
        }

        void compare(Json expected, int originRecord, String original, String repeated) {
            String support = artifact + ":record=" + record.index() + "#";
            String fact = "redundancy:" + record.index() + repeated;
            List<String> addresses =
                    List.of(
                            artifact + ":record=" + originRecord + "#" + original,
                            support + repeated);
            report.expect(
                    new OracleReport.JsonExpectation(
                            owner, fact, "source.native_redundancy/v1", expected, addresses));
            FactComparison.compare(
                    report,
                    "fact.redundancy",
                    owner,
                    new io.github.markpollack.journal.tck.verify.Check.Evidence(
                            "source.native_redundancy/v1",
                            fact,
                            addresses,
                            "native:" + support + repeated,
                            expected,
                            FactComparison.at(record.json(), repeated)));
        }
    }

    private NativeRedundancy() {}

    static void check(
            OracleReport report,
            NativeArtifact artifact,
            ExpectedFacts facts,
            String owner,
            String profile) {
        wireInputs(report, artifact, facts, owner, profile);
        tools(report, artifact, facts, owner, profile);
        denials(report, artifact, facts, owner, profile);
        if (!profile.startsWith("copilot_")) {
            return;
        }
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed() || !facts.owned.contains(record.index())) {
                continue;
            }
            Reading reading = new Reading(report, facts.artifact, owner, record);
            CopilotRedundancy.copilot(reading, profile);
        }
    }

    private static void wireInputs(
            OracleReport report,
            NativeArtifact artifact,
            ExpectedFacts facts,
            String owner,
            String profile) {
        if (!"claude_transcript".equals(profile)) {
            return;
        }
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed()
                    || !facts.owned.contains(record.index())
                    || !(record.json().member("wireToolInputs") instanceof Json.JObject inputs)) {
                continue;
            }
            wireInputs(new Reading(report, facts.artifact, owner, record), inputs);
        }
    }

    private static void wireInputs(Reading reading, Json.JObject inputs) {
        Json blocks = reading.record().json().path("message", "content");
        for (String id : inputs.members().keySet()) {
            List<Json> matches =
                    (blocks == null ? List.<Json>of() : blocks.elements())
                            .stream()
                                    .filter(
                                            b ->
                                                    id.equals(SourceProfile.text(b, "id"))
                                                            && "tool_use"
                                                                    .equals(
                                                                            SourceProfile.text(
                                                                                    b, "type")))
                                    .toList();
            if (matches.size() == 1) {
                int index = blocks.elements().indexOf(matches.get(0));
                reading.compare(
                        "/message/content/" + index + "/input",
                        "/wireToolInputs/" + FactComparison.escape(id));
            } else {
                reading.report()
                        .add(
                                io.github.markpollack.journal.tck.verify.Check.Category
                                        .NORMALIZATION,
                                "native.redundancy.identity",
                                reading.owner(),
                                io.github.markpollack.journal.tck.verify.Check.Outcome.UNRESOLVED,
                                "record "
                                        + reading.record().index()
                                        + ": wireToolInputs/"
                                        + id
                                        + " matches "
                                        + matches.size()
                                        + " tool_use ids");
            }
        }
    }

    private static void tools(
            OracleReport report,
            NativeArtifact artifact,
            ExpectedFacts facts,
            String owner,
            String profile) {
        if (!List.of("agy_stream", "codex_stream").contains(profile)) {
            return;
        }
        Map<String, NativeArtifact.Record> starts = new java.util.LinkedHashMap<>();
        boolean agy = profile.equals("agy_stream");
        String base = agy ? "/step_update" : "/item";
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed() || !facts.owned.contains(record.index())) {
                continue;
            }
            tool(new Reading(report, facts.artifact, owner, record), starts, agy, base);
        }
    }

    private static void tool(
            Reading reading, Map<String, NativeArtifact.Record> starts, boolean agy, String base) {
        NativeArtifact.Record record = reading.record();
        Json item = FactComparison.at(record.json(), base);
        if (item == null) {
            return;
        }
        String type = SourceProfile.text(item, agy ? "step_type" : "type");
        if (!(agy
                ? "tool".equals(type)
                : List.of("mcp_tool_call", "collab_tool_call").contains(String.valueOf(type)))) {
            return;
        }
        Json id = item.member(agy ? "step_index" : "id");
        String key = type + ":" + id;
        boolean start =
                agy
                        ? "ACTIVE".equals(SourceProfile.text(item, "state"))
                        : "item.started".equals(SourceProfile.text(record.json(), "type"));
        if (start) {
            starts.put(key, record);
            if (agy) {
                reading.compare(base + "/tool_name", base + "/tool_info/name");
            }
        } else {
            completion(reading, starts.get(key), agy, base, type);
        }
    }

    private static void completion(
            Reading reading,
            NativeArtifact.Record original,
            boolean agy,
            String base,
            String type) {
        List<String> members =
                agy
                        ? List.of("/tool_name", "/tool_info/name", "/tool_info/parameters")
                        : "mcp_tool_call".equals(type)
                                ? List.of("/server", "/tool", "/arguments")
                                : List.of("/tool");
        for (String member : members) {
            reading.compare(
                    original == null ? null : FactComparison.at(original.json(), base + member),
                    original == null ? reading.record().index() : original.index(),
                    base + member,
                    base + member);
        }
    }

    private static void denials(
            OracleReport report,
            NativeArtifact artifact,
            ExpectedFacts facts,
            String owner,
            String profile) {
        if (!profile.equals("claude_stream")) {
            return;
        }
        for (NativeArtifact.Record record : artifact.records()) {
            if (!record.parsed()
                    || !facts.owned.contains(record.index())
                    || !"permission_denied".equals(SourceProfile.text(record.json(), "subtype"))) {
                continue;
            }
            denial(new Reading(report, facts.artifact, owner, record), artifact, facts);
        }
    }

    private record Denial(NativeArtifact.Record record, int index, Json value) {}

    private static void denial(Reading reading, NativeArtifact artifact, ExpectedFacts facts) {
        String id = SourceProfile.text(reading.record().json(), "tool_use_id");
        List<Denial> matches = new java.util.ArrayList<>();
        for (NativeArtifact.Record result : artifact.records()) {
            if (result.parsed() && facts.owned.contains(result.index())) {
                denialMatches(result, id, matches);
            }
        }
        if (matches.size() != 1) {
            reading.report()
                    .add(
                            io.github.markpollack.journal.tck.verify.Check.Category.NORMALIZATION,
                            "native.redundancy.identity",
                            reading.owner(),
                            io.github.markpollack.journal.tck.verify.Check.Outcome.UNRESOLVED,
                            "permission denial "
                                    + id
                                    + " has "
                                    + matches.size()
                                    + " native result matches");
            return;
        }
        Denial match = matches.get(0);
        for (String key : List.of("tool_use_id", "tool_name")) {
            reading.compare(
                    match.value().member(key),
                    match.record().index(),
                    "/permission_denials/" + match.index() + "/" + key,
                    "/" + key);
        }
    }

    private static void denialMatches(
            NativeArtifact.Record result, String id, List<Denial> matches) {
        Json collection = result.json().member("permission_denials");
        if (collection == null || id == null) {
            return;
        }
        List<Json> denied = collection.elements();
        for (int i = 0; i < denied.size(); i++) {
            if (id.equals(SourceProfile.text(denied.get(i), "tool_use_id"))) {
                matches.add(new Denial(result, i, denied.get(i)));
            }
        }
    }
}
