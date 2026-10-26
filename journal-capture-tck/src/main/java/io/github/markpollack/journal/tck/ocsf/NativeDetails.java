package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Dispatches native metadata expectations to each exact source family. */
final class NativeDetails {
    private NativeDetails() {}

    static void expand(ExpectedFacts facts, NativeArtifact artifact, String profile) {
        for (NativeArtifact.Record record : artifact.records()) {
            if (!facts.owned.contains(record.index())) {
                continue;
            }
            Json nativeRecord =
                    record.parsed()
                            ? record.json()
                            : "codex_stream".equals(profile)
                                    ? CodexStreamProfile.webSearchLine(record)
                                    : null;
            if (nativeRecord == null) {
                continue;
            }
            record(facts, record.index(), nativeRecord, profile);
        }
        for (ExpectedFacts.TurnCompleted turn : facts.completed) {
            new SourceFacts.Target(facts, turn.recordIndex(), "prompt_turn", 2)
                    .event(
                            "",
                            "source.native_details/v1",
                            Map.of(),
                            SourceFacts.fields(
                                    "/status_basis_id", profile.startsWith("codex_") ? 3 : 1));
        }
        facts.operations.forEach(operation -> operation(facts, artifact, profile, operation));
    }

    private static void record(ExpectedFacts facts, int index, Json record, String profile) {
        switch (profile) {
            case "claude_stream", "claude_transcript" ->
                    ClaudeNativeDetails.read(facts, index, record, profile);
            case "codex_stream" -> CodexNativeDetails.stream(facts, index, record);
            case "codex_rollout" -> CodexNativeDetails.rollout(facts, index, record);
            case "agy_stream" -> AntigravityNativeDetails.read(facts, index, record);
            case "copilot_events" -> copilot(facts, index, record);
            default -> {}
        }
    }

    private static void operation(
            ExpectedFacts facts,
            NativeArtifact artifact,
            String profile,
            ExpectedFacts.Operation operation) {
        Json n = artifact.record(operation.recordIndex()).json();
        if (n == null) {
            return;
        }
        Json item = "codex_stream".equals(profile) ? n.member("item") : n.path("payload", "item");
        if (item == null) {
            return;
        }
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/tool_operation/data",
                        item,
                        "/status_basis_id",
                        1,
                        "/tool_operation/stdout",
                        item.member("stdout") == null
                                ? item.member("aggregated_output")
                                : item.member("stdout"));
        if ("codex_rollout".equals(profile)) {
            fields.putAll(
                    SourceFacts.fields(
                            "/tool_operation/stderr",
                            item.member("stderr"),
                            "/tool_operation/attribution/scope_uid",
                            n.path("payload", "turn_id")));
        }
        if ("declined".equals(SourceProfile.text(item, "status"))) {
            fields.put("/status", FactComparison.value("Declined"));
        }
        new SourceFacts.Target(facts, operation.recordIndex(), "tool_call", 3)
                .event(
                        "codex_stream".equals(profile) ? "/item" : "/payload/item",
                        "source.native_details/v1",
                        SourceFacts.fields("/tool_operation/uid", operation.uid()),
                        fields);
    }

    private static void copilot(ExpectedFacts f, int record, Json n) {
        for (ExpectedFacts.Fact fact : List.copyOf(f.facts)) {
            Json name = fact.fields().get("/tool_name");
            if (fact.recordIndex() != record
                    || !"tool_call".equals(fact.className())
                    || name == null) {
                continue;
            }
            int kind = toolKind(name.scalarText());
            Map<String, Json> fields = SourceFacts.fields("/tool_kind_id", kind);
            if (kind == 99) {
                fields.put("/tool_kind", FactComparison.value("other"));
            }
            new SourceFacts.Target(f, record, "tool_call", fact.activityId())
                    .event(
                            fact.support().get(0),
                            "source.native_details/v1",
                            fact.identity(),
                            fields);
        }
        if (n.path("data", "error") instanceof Json.JObject error) {
            f.facts.add(
                    new ExpectedFacts.Fact(
                            record,
                            "tool_call",
                            2,
                            SourceFacts.fields("/tool_call_uid", n.path("data", "toolCallId")),
                            "/tool_result/errors",
                            SourceFacts.fields("/error_layer_id", 3),
                            SourceFacts.fields("/data", error),
                            List.of("/data/error"),
                            "source.tool_error_payload/v1"));
        }
    }

    private static final Map<String, Integer> TOOLKIND =
            Map.ofEntries(
                    Map.entry("read_file", 1),
                    Map.entry("view_file", 1),
                    Map.entry("list_dir", 1),
                    Map.entry("view", 1),
                    Map.entry("write_file", 2),
                    Map.entry("write_to_file", 2),
                    Map.entry("replace_file_content", 2),
                    Map.entry("multi_replace_file_content", 2),
                    Map.entry("edit", 2),
                    Map.entry("create", 2),
                    Map.entry("grep_search", 5),
                    Map.entry("find_by_name", 5),
                    Map.entry("grep", 5),
                    Map.entry("glob", 5),
                    Map.entry("run_command", 6),
                    Map.entry("bash", 6),
                    Map.entry("read_url_content", 8),
                    Map.entry("search_web", 8));

    static int toolKind(String name) {
        return TOOLKIND.getOrDefault(String.valueOf(name), 99);
    }
}
