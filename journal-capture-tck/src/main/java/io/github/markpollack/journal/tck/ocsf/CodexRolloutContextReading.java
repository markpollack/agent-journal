package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static io.github.markpollack.journal.tck.ocsf.SourceProfile.text;

/** Independent native declarations, settings and per-block reasoning meanings. */
final class CodexRolloutContextReading {
    private CodexRolloutContextReading() { }
    static boolean read(ExpectedFacts f, NativeArtifact.Record record, String turn) {
        Json n = record.json(), p = n.member("payload");
        String type = text(n, "type"), kind = text(p, "type");
        int index = record.index();
        if ("response_item".equals(type) && "additional_tools".equals(kind)) {
            declarations(f, index, p, turn); return true;
        }
        if ("event_msg".equals(type) && "thread_settings_applied".equals(kind)) {
            return settings(f, index, p);
        }
        boolean response = "response_item".equals(type) && "reasoning".equals(kind);
        if (response || completedReasoning(type, kind, p)) {
            reasoning(f, index, p, response); return true;
        }
        return false;
    }
    /** Native 0.166.0 reports {@code thread_id} and {@code thread_settings} on every thread_settings_applied record (fresh and resumed,
     * observed 2026-11-04). A record without thread_id (emulated evidence accepted under the frozen /1 profile) is owned by the rollout
     * file's one thread, read from its session_meta exactly as the mapper reads the run's session; nothing beyond the file is inferred. */
    private static boolean settings(ExpectedFacts f, int index, Json p) {
        Json reported = p.member("thread_id");
        Json owner = reported != null && !reported.isNull() ? reported : f.sessionOf.containsKey(index) ? new Json.JString(f.sessionOf.get(index)) : null;
        if (owner == null) return false; // no thread identity anywhere: no declared meaning, stays non-passing
        f.normalized.add(index);
        Map<String, Json> settings = ClaudeTranscriptProfile.settings(p.member("thread_settings"), "cwd", "working_directory", "model", "model",
                "model_provider_id", "provider", "approval_policy", "approval_policy");
        Json effort = effort(p.member("thread_settings"));
        if (effort != null) settings.put("reasoning_effort", effort);
        f.contexts.add(new ExpectedFacts.Context(index, 1, "thread_settings_applied", null, settings, null, null));
        Map<String, Json> fields = p.member("thread_settings") == null
                ? SourceFacts.fields("/session_uid", owner, "/settings", new Json.JObject(Map.of())) // no settings reported: the empty object is the whole assertion
                : SourceFacts.fields("/session_uid", owner);
        new SourceFacts.Target(f, index, "session_context", 1).event("/payload/thread_id", "codex.native.settings_owner/v2", Map.of(), fields);
        f.sessionOf.put(index, owner.text());
        return true;
    }

    /** The reported top-level member first, else collaboration_mode.settings (native 0.166.0 nests it, null when unset): the mapper's order. */
    private static Json effort(Json s) {
        if (s == null || s.isNull()) return null;
        Json top = s.member("reasoning_effort");
        if (top != null && !top.isNull()) return top;
        Json nested = s.path("collaboration_mode", "settings", "reasoning_effort");
        return nested == null || nested.isNull() ? null : nested;
    }

    private static boolean completedReasoning(String type, String kind, Json payload) {
        return "event_msg".equals(type) && "item_completed".equals(kind) && "Reasoning".equals(text(payload, "item", "type"));
    }
    private static void declarations(ExpectedFacts f, int record, Json p, String turn) {
        f.normalized.add(record);
        f.contexts.add(new ExpectedFacts.Context(record, 2, "additional_tools", turn, Map.of(), text(p, "role"), null));
        int count = 0;
        List<Json> declarations = elements(p.member("tools"));
        for (int i = 0; i < declarations.size(); i++) {
            Json declaration = declarations.get(i);
            String support = "/payload/tools/" + i;
            if ("namespace".equals(text(declaration, "type"))) {
                List<Json> tools = elements(declaration.member("tools"));
                for (int j = 0; j < tools.size(); j++) {
                    Json tool = tools.get(j);
                    count += definition(f, record, tool, declaration.member("name"), support + "/tools/" + j);
                    if (text(tool, "name") != null) new SourceFacts.Target(f, record, "session_context", 2).observation("/tool_definitions", support + "/name", "codex.native.tool_namespace/v1",
                            SourceFacts.fields("/name", tool.member("name"), "/tool_namespace", declaration.member("name")),
                            SourceFacts.fields("/tool_namespace", declaration.member("name")));
                }
            }
            else count += definition(f, record, declaration, null, support);
        }
        new SourceFacts.Target(f, record, "session_context", 2).event("/payload/tools", "codex.native.declaration_count/v1", Map.of(),
                SourceFacts.fields("/tool_definitions/" + count, null));
    }
    private static int definition(ExpectedFacts f, int record, Json tool, Json namespace, String support) {
        if (text(tool, "name") == null) return 0;
        boolean function = "function".equals(text(tool, "type"));
        new SourceFacts.Target(f, record, "session_context", 2).observation("/tool_definitions", support, "codex.native.declared_tool/v1",
                SourceFacts.fields("/name", tool.member("name"), "/tool_namespace", namespace),
                SourceFacts.fields("/tool_description", tool.member("description"), "/definition_format_id", function ? 2 : 99,
                        "/definition_format", function ? "Function declaration" : "Other",
                        "/input_schema/schema_document", function ? tool.member("parameters") : null, "/data", tool));
        return 1;
    }
    private static void reasoning(ExpectedFacts f, int index, Json p, boolean response) {
        Json item = response ? p : p.member("item");
        List<Json> blocks = new ArrayList<>(); List<ExpectedFacts.ReasoningContent> content = new ArrayList<>();
        parts(blocks, content, item.member(response ? "summary" : "summary_text"), 2, "summary");
        parts(blocks, content, item.member(response ? "content" : "raw_content"), 1, "reasoning");
        Json encrypted = item.member("encrypted_content");
        if (response && encrypted != null && !encrypted.isNull()) {
            blocks.add(encrypted); content.add(new ExpectedFacts.ReasoningContent("encrypted_content", null, 3));
        }
        if (blocks.isEmpty()) new SourceFacts.Target(f, index, "model_response", 3).event("/payload", "codex.native.empty_reasoning/v1", Map.of(), SourceFacts.fields("/content_blocks", new Json.JArray(List.of())));
        f.normalized.add(index);
        f.responses.add(new ExpectedFacts.Response(index, text(item, "id"), blocks, null, true, null, null, 99, null, null, content));
        new SourceFacts.Target(f, index, "model_response", 3).event("/payload", "codex.native.reasoning_item/v1", Map.of(),
                SourceFacts.fields("/response_uid", null, "/identity_basis", "Other", "/turn_uid", response ? p.path("internal_chat_message_metadata_passthrough", "turn_id") : p.member("turn_id"),
                        "/content_blocks/" + blocks.size(), null));
    }
    private static void parts(List<Json> blocks, List<ExpectedFacts.ReasoningContent> meanings, Json values, int form, String type) {
        for (Json value : elements(values)) {
            blocks.add(value);
            meanings.add(new ExpectedFacts.ReasoningContent(text(value, "type") == null ? type : text(value, "type"), value instanceof Json.JString ? value.text() : text(value, "text"), form));
        }
    }
    private static List<Json> elements(Json value) { return value instanceof Json.JArray a ? a.elements() : List.of(); }
}
