package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/** Reported native context and reasoning items; no inferred model invocation or decoded ciphertext. */
final class CodexRolloutContext {
    private static final String CONTRACT = CodexRolloutMapper.CONTRACT;
    private CodexRolloutContext() { }

    static boolean map(NativeRecord record, SourceContext ctx, String turn) {
        Jv n = record.json(), p = n.member("payload");
        String type = text(n, "type"), kind = text(p, "type");
        if ("response_item".equals(type) && "additional_tools".equals(kind)) {
            declarations(record, ctx, p, turn); return true;
        }
        if ("event_msg".equals(type) && "thread_settings_applied".equals(kind)) {
            settings(record, ctx, p); return true;
        }
        boolean response = "response_item".equals(type) && "reasoning".equals(kind);
        boolean completed = "event_msg".equals(type) && "item_completed".equals(kind) && "Reasoning".equals(text(p, "item", "type"));
        if (response || completed) { reasoning(record, ctx, p, response); return true; }
        return false;
    }

    private static Jv.Obj event(NativeRecord r, SourceContext ctx, String cls, int activity, String rule, String uid) {
        String timestamp = text(r.json(), "timestamp");
        return ctx.event(cls, activity, r, ctx.timeFor(r, timestamp), CONTRACT, rule, timestamp, uid, true, null).put("data", r.json());
    }

    private static void declarations(NativeRecord r, SourceContext ctx, Jv p, String turn) {
        Jv.Arr definitions = new Jv.Arr();
        for (Jv declaration : elements(p.member("tools"))) {
            if ("namespace".equals(text(declaration, "type"))) {
                for (Jv tool : elements(declaration.member("tools"))) definition(r, ctx, definitions, tool, text(declaration, "name"));
            }
            else definition(r, ctx, definitions, declaration, null);
        }
        ctx.family("response_item/additional_tools", r.raw().length, "normalized: injected tool declarations; vendor grammar and containers retained exactly");
        ctx.emit(event(r, ctx, "session_context", 2, "codex.rollout.additional_tools->session_context.context", text(p, "id"))
                .put("session_uid", ctx.run.sessionUid()).put("turn_uid", turn).put("context_kind", "additional_tools")
                .put("rendered_role", text(p, "role")).put("tool_definitions", definitions));
    }

    private static void definition(NativeRecord r, SourceContext ctx, Jv.Arr out, Jv tool, String namespace) {
        if (text(tool, "name") == null) return;
        boolean function = "function".equals(text(tool, "type"));
        Jv.Obj definition = new Jv.Obj().put("name", text(tool, "name")).put("tool_namespace", namespace)
                .put("tool_description", text(tool, "description")).put("definition_format_id", function ? 2 : 99)
                .put("definition_format", function ? "Function declaration" : "Other")
                .put("source_ref", ctx.sourceRef(r, text(r.json(), "payload", "id"), text(r.json(), "timestamp"), false)).put("data", tool);
        if (function && tool.member("parameters") != null) definition.put("input_schema", new Jv.Obj().put("schema_document", tool.member("parameters")));
        out.add(definition);
    }

    private static void settings(NativeRecord r, SourceContext ctx, Jv p) {
        Jv s = p.member("thread_settings");
        Jv.Obj settings = new Jv.Obj().put("working_directory", text(s, "cwd")).put("model", text(s, "model"))
                .put("provider", text(s, "model_provider_id")).put("approval_policy", text(s, "approval_policy")).put("reasoning_effort", text(s, "reasoning_effort"));
        ctx.family("event_msg/thread_settings_applied", r.raw().length, "normalized: reported thread settings; other native settings retained exactly");
        ctx.emit(event(r, ctx, "session_context", 1, "codex.rollout.thread_settings_applied->session_context.settings", null)
                .put("session_uid", text(p, "thread_id")).put("context_kind", "thread_settings_applied").put("settings", settings));
    }

    private static void reasoning(NativeRecord r, SourceContext ctx, Jv p, boolean response) {
        Jv item = response ? p : p.member("item");
        Jv.Arr blocks = new Jv.Arr();
        blocks(blocks, item.member(response ? "summary" : "summary_text"), 2, "summary");
        blocks(blocks, item.member(response ? "content" : "raw_content"), 1, "reasoning");
        encrypted(blocks, item, response);
        String family = response ? "response_item/reasoning" : "event_msg/item_completed/Reasoning";
        String rule = response ? "codex.rollout.reasoning->model_response.item" : "codex.rollout.item_completed.Reasoning->model_response.item";
        ctx.family(family, r.raw().length, "normalized: reasoning content item; summary, full and opaque forms kept distinct");
        ctx.emit(event(r, ctx, "model_response", 3, rule, text(item, "id")).put("session_uid", ctx.run.sessionUid())
                .put("turn_uid", response ? text(p, "internal_chat_message_metadata_passthrough", "turn_id") : text(p, "turn_id"))
                .put("item_uid", text(item, "id")).put("identity_basis_id", 99).put("identity_basis", MapperSupport.IDENTITY_BASIS.get(99))
                .put("content_blocks", blocks).put("basis", "codex.rollout.reasoning_item.v1: native content item, no model invocation identity; opaque content is never decoded"));
    }

    private static void encrypted(Jv.Arr blocks, Jv item, boolean response) {
        if (response && item.member("encrypted_content") != null && !item.member("encrypted_content").isNull()) {
            block(blocks, item.member("encrypted_content"), 3, "encrypted_content", null);
        }
    }

    private static void blocks(Jv.Arr out, Jv values, int form, String kind) {
        for (Jv value : elements(values)) block(out, value, form, text(value, "type") == null ? kind : text(value, "type"), value instanceof Jv.Str ? value.text() : text(value, "text"));
    }
    private static void block(Jv.Arr out, Jv data, int form, String kind, String text) {
        out.add(new Jv.Obj().put("block_index", out.elements().size()).put("block_type", kind).put("block_kind_id", 2).put("block_kind", "Thought")
                .put("reasoning_form_id", form).put("reasoning_form", MapperSupport.REASONING_FORM.get(form)).put("text", text).put("data", data)
                .put("basis", "codex.rollout.reasoning_form.v1: native summary, content or encrypted_content member"));
    }
    private static List<Jv> elements(Jv value) { return value instanceof Jv.Arr a ? a.elements() : List.of(); }
}
