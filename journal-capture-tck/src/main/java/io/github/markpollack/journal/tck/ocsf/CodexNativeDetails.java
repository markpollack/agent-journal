package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Exact native item, lifecycle and rollout metadata expectations. */
final class CodexNativeDetails {
    private CodexNativeDetails() {}

    static void stream(ExpectedFacts f, int record, Json n) {
        Json item = n.member("item");
        if (item == null
                || !List.of("item.started", "item.completed")
                        .contains(SourceProfile.text(n, "type"))) {
            return;
        }
        String type = SourceProfile.text(item, "type");
        if (!List.of("mcp_tool_call", "collab_tool_call", "file_change", "web_search")
                .contains(type)) {
            return;
        }
        int kind = Map.of("file_change", 2, "web_search", 5).getOrDefault(type, 99);
        int activity = "item.started".equals(SourceProfile.text(n, "type")) ? 4 : 2;
        Map<String, Json> fields = SourceFacts.fields("/tool_kind_id", kind);
        if (kind == 99) {
            fields.put("/tool_kind", FactComparison.value("other"));
        }
        mcp(f, record, item, activity, type, fields);
        if ("web_search".equals(type)) {
            fields.put("/arguments", item.member("action"));
        }
        if ("collab_tool_call".equals(type)) {
            fields.put("/tool_name", item.member("tool"));
        }
        new SourceFacts.Target(f, record, "tool_call", activity)
                .event(
                        "/item",
                        "source.native_details/v1",
                        SourceFacts.fields("/tool_call_uid", item.member("id")),
                        fields);
    }

    private static void mcp(
            ExpectedFacts f,
            int record,
            Json item,
            int activity,
            String type,
            Map<String, Json> fields) {
        if ("mcp_tool_call".equals(type)) {
            fields.putAll(
                    SourceFacts.fields(
                            "/tool_name",
                            item.member("tool"),
                            "/tool_namespace",
                            item.member("server"),
                            "/arguments",
                            item.member("arguments")));
            if (activity == 2 && item.member("error") instanceof Json.JObject error) {
                f.facts.add(
                        new ExpectedFacts.Fact(
                                record,
                                "tool_call",
                                2,
                                SourceFacts.fields("/tool_call_uid", item.member("id")),
                                "/tool_result/errors",
                                SourceFacts.fields("/error_layer_id", 3),
                                SourceFacts.fields("/data", error),
                                List.of("/item/error"),
                                "source.tool_error_payload/v1"));
            }
        }
    }

    static void rollout(ExpectedFacts f, int record, Json n) {
        Json p = n.member("payload");
        if (p == null) {
            return;
        }
        identities(f, record, n, p);
        accounting(f, record, p, n);
        customTool(f, record, p);
        prompt(f, record, n, p);
    }

    private static void identities(ExpectedFacts f, int record, Json n, Json p) {
        for (ExpectedFacts.Fact fact : List.copyOf(f.facts)) {
            if (fact.recordIndex() != record || fact.collection() != null) {
                continue;
            }
            Json turn = p.member("turn_id");
            if ("response_item".equals(SourceProfile.text(n, "type"))) {
                turn = p.path("internal_chat_message_metadata_passthrough", "turn_id");
            }
            if (turn != null && !fact.fields().containsKey("/turn_uid")) {
                new SourceFacts.Target(f, record, fact.className(), fact.activityId())
                        .event(
                                "/payload",
                                "source.native_details/v1",
                                fact.identity(),
                                SourceFacts.fields("/turn_uid", turn));
            }
        }
    }

    private static void accounting(ExpectedFacts f, int record, Json p, Json n) {
        if ("message".equals(SourceProfile.text(p, "type"))
                && "assistant".equals(SourceProfile.text(p, "role"))) {
            new SourceFacts.Target(f, record, "model_response", 2)
                    .event(
                            "/payload/status",
                            "source.native_details/v1",
                            SourceFacts.fields("/response_uid", p.member("id")),
                            SourceFacts.fields("/reported_state", p.member("status")));
        }
        if ("token_usage_record".equals(SourceProfile.text(n, "type"))) {
            new SourceFacts.Target(f, record, "session_context", 3)
                    .event(
                            "/payload/response_id",
                            "source.native_details/v1",
                            Map.of(),
                            SourceFacts.fields("/response_uid", p.member("response_id")));
        }
    }

    private static void customTool(ExpectedFacts f, int record, Json p) {
        if ("custom_tool_call".equals(SourceProfile.text(p, "type"))) {
            int kind =
                    switch (String.valueOf(SourceProfile.text(p, "name"))) {
                        case "exec", "shell" -> 6;
                        case "apply_patch" -> 2;
                        case "read_file", "view_image" -> 1;
                        case "web_search" -> 8;
                        default -> 99;
                    };
            new SourceFacts.Target(f, record, "tool_call", 1)
                    .event(
                            "/payload/name",
                            "source.native_details/v1",
                            SourceFacts.fields("/tool_call_uid", p.member("call_id")),
                            SourceFacts.fields("/tool_kind_id", kind));
            new SourceFacts.Target(f, record, "tool_call", 1)
                    .event(
                            "/payload",
                            "source.native_details/v1",
                            SourceFacts.fields("/tool_call_uid", p.member("call_id")),
                            SourceFacts.fields("/reported_state", p.member("status")));
        }
    }

    private static void prompt(ExpectedFacts f, int record, Json n, Json p) {
        Json item = p.member("item");
        if ("user".equals(SourceProfile.text(p, "role"))
                || "UserMessage".equals(SourceProfile.text(item, "type"))) {
            Json content = item == null ? p.member("content") : item.member("content");
            NativeContent.blocks(
                    new SourceFacts.Target(f, record, "prompt_turn", 1),
                    content,
                    item == null ? "/payload/content" : "/payload/item/content");
        }
        if ("world_state".equals(SourceProfile.text(n, "type"))
                && p.path("state", "collaboration_mode", "model") == null
                && p.path("state", "collaboration_mode", "settings") == null) {
            new SourceFacts.Target(f, record, "session_context", 1)
                    .event(
                            "/payload/state",
                            "source.native_details/v1",
                            Map.of(),
                            SourceFacts.fields("/settings", new Json.JObject(Map.of())));
        }
    }
}
