package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.IDENTITY_BASIS;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.TOOL_KIND;
import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * GitHub Copilot CLI 1.0.100 session events (contract {@code github-copilot-cli-1.0.100-events}, EXPERIMENTAL): the
 * same event model on two routes, the {@code --output-format json} standard output (persisted events plus ephemeral
 * updates and a final {@code result}) and the native session file {@code events.jsonl} (persisted events plus session
 * start, system message, per-call usage records and shutdown). Each event carries its own {@code id}; a persisted event
 * keeps that id on both routes. An assistant message completes a model response identified by the provider call id
 * ({@code apiCallId}) and requests its tools by model-facing call id; tool execution start and completion are separate
 * stages of the same call; a usage record is that response's per-call usage and cost; a usage checkpoint or the
 * shutdown's accounting snapshot is the session's cumulative accounting; the result is the CLI's outcome and exit code.
 * Records the stream reports only as progress (deltas, partial results, shell output, model-call progress, background
 * task changes) are retained whole with their meaning stated. A session is reported only by the session file's
 * {@code session.start} and standard output's {@code result}; the mapper states those to the assembly gate, which judges
 * each artifact on what it reported. An event whose record reports none carries the session its artifact reported, else
 * the one the launch declared, else the run's (the class requires a session_uid); that value is never evidence of
 * identity.
 */
final class CopilotEventsMapper implements SourceMapper {

    static final String CONTRACT = "github-copilot-cli-1.0.100-events";

    /** Settings families: session_context Settings reported with the event's data as settings. */
    static final Set<String> SETTINGS = Set.of("session.model_change", "session.auto_mode_resolved", "session.tools_updated", "session.mcp_servers_loaded",
            "session.mcp_server_status_changed", "session.mcp_server_tools_loaded");

    /** Families retained whole, with the meaning the contract states for each. */
    static final Map<String, String> RETAINED = Map.ofEntries(
            Map.entry("assistant.turn_start", "a model turn of the interaction starts (data.turnId)"),
            Map.entry("assistant.turn_end", "the model turn ends"),
            Map.entry("assistant.message_start", "ephemeral: an assistant message starts streaming"),
            Map.entry("assistant.message_delta", "ephemeral: an increment of the assistant message text"),
            Map.entry("assistant.tool_call_delta", "ephemeral: an increment of a tool call's input"),
            Map.entry("assistant.idle", "ephemeral: the agent is idle"),
            Map.entry("model.call_start", "ephemeral: a model call starts"),
            Map.entry("model.call_finished", "ephemeral: a model call finished dispatching"),
            Map.entry("model.call_final_result", "ephemeral: a model call's final result summary"),
            Map.entry("tool.shell_output", "ephemeral: a chunk of a shell tool's output"),
            Map.entry("tool.execution_partial_result", "ephemeral: a partial tool result"),
            Map.entry("session.background_tasks_changed", "ephemeral: the background task list changed"));

    private String reportedSession;
    /** The MCP server and tool each started call named in this source, by its toolCallId. */
    private final Map<String, String[]> mcpCalls = new java.util.HashMap<>();

    @Override
    public String contractName() {
        return CONTRACT;
    }

    /** The handler of each normalized event type; any other type is a setting or retained whole ({@link #other}). */
    private final Map<String, java.util.function.Consumer<Event>> handlers = Map.ofEntries(
            Map.entry("session.start", e -> settings(e, "copilot.events.session_start->session_context.settings", "the session's identity, CLI version and working context")),
            Map.entry("user.message", this::prompt),
            Map.entry("system.message", this::systemMessage),
            Map.entry("assistant.message", this::assistantMessage),
            Map.entry("tool.execution_start", e -> toolStage(e, 4, "copilot.events.tool_execution_start->tool_call.started")),
            Map.entry("tool.execution_complete", e -> toolStage(e, 2, "copilot.events.tool_execution_complete->tool_call.result")),
            Map.entry("session.usage_record", CopilotAccounting::usageRecord),
            Map.entry("session.usage_checkpoint", e -> CopilotAccounting.snapshot(e, e.data(), "copilot.events.usage_checkpoint->session_context.accounting")),
            Map.entry("session.shutdown", e -> CopilotAccounting.snapshot(e, e.data().member("accountingSnapshot"), "copilot.events.shutdown->session_context.accounting")),
            Map.entry("result", CopilotAccounting::result));

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        ctx.reportSession(null);
        if (!r.parsed()) {
            ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim)");
            ctx.emit(ctx.opaque(r, CONTRACT));
            return;
        }
        Jv o = r.json();
        String type = text(o, "type");
        Jv data = o.member("data") == null ? new Jv.Obj() : o.member("data");
        if ("session.start".equals(type)) {
            reportedSession = text(data, "sessionId");
            ctx.reportSession(reportedSession);
        }
        String own = text(o, "sessionId");
        ctx.reportSession(own);
        Event e = new Event(r, ctx, o, type, data, text(o, "id"), text(o, "timestamp"), ctx.sessionFor(own != null ? own : reportedSession));
        handlers.getOrDefault(type == null ? "" : type, this::other).accept(e);
    }

    /**
     * One event being mapped: the record, its type and data, its own id and reported time, and the session its artifact
     * reports for it ({@code null} when none is reported yet).
     */
    record Event(NativeRecord r, SourceContext ctx, Jv o, String type, Jv data, String id, String ts, String session) {

        MapperSupport.Time time() {
            return ctx.timeFor(r, ts);
        }

        Jv.Obj event(String className, int activity, String rule, boolean whole, MapperSupport.Status status) {
            return ctx.event(className, activity, r, time(), CONTRACT, rule, ts, id, whole, status);
        }
    }

    private void settings(Event e, String rule, String meaning) {
        e.ctx().family(e.type(), e.r().raw().length, "normalized: session_context Settings reported (" + meaning + ")");
        Jv.Obj ev = e.event("session_context", 1, rule, true, null);
        Jv.Obj settings = McpObservations.members("working_directory", e.data().path("context", "cwd"), "version", e.data().member("copilotVersion"),
                "model", first(e.data(), "chosenModel", "newModel", "model"));
        ev.put("session_uid", e.session()).put("settings", settings)
                .put("basis", "copilot.events.settings.v1: " + e.type() + " reports " + meaning + "; the canonical settings are its working directory, CLI version and model "
                        + "where it states them, the rest stays in the record").put("data", e.o());
        e.ctx().emit(ev);
    }

    private static Jv first(Jv data, String... members) {
        for (String m : members) {
            if (data.member(m) instanceof Jv.Str s) {
                return s;
            }
        }
        return null;
    }

    private void prompt(Event e) {
        e.ctx().family(e.type(), e.r().raw().length, "normalized: prompt_turn Prompt submitted (data.content; transformedContent kept in data)");
        Jv.Obj ev = e.event("prompt_turn", 1, "copilot.events.user_message->prompt_turn.submitted", true, null);
        Jv.Arr blocks = new Jv.Arr();
        if (text(e.data(), "content") != null) {
            blocks.add(new Jv.Obj().put("block_index", 0).put("block_type", "user.message.content").put("block_kind_id", 1).put("block_kind", "Text")
                    .put("text", text(e.data(), "content")).put("basis", "data.content is the prompt as submitted").put("data", e.data().member("content")));
        }
        ev.put("session_uid", e.session()).put("turn_uid", text(e.data(), "interactionId")).put("content_blocks", blocks).put("data", e.o());
        e.ctx().emit(ev);
    }

    private void systemMessage(Event e) {
        e.ctx().family(e.type(), e.r().raw().length, "normalized: session_context Context injected (the system message as the session file records it)");
        Jv.Obj ev = e.event("session_context", 2, "copilot.events.system_message->session_context.context", true, null);
        Jv.Arr blocks = new Jv.Arr();
        blocks.add(new Jv.Obj().put("block_index", 0).put("block_type", "system.message.content").put("block_kind_id", 1).put("block_kind", "Text")
                .put("text", text(e.data(), "content")).put("basis", "data.content is the system message").put("data", e.data().member("content")));
        ev.put("session_uid", e.session()).put("turn_uid", text(e.data(), "interactionId")).put("context_kind", "system message").put("rendered_role", text(e.data(), "role"))
                .put("content_blocks", blocks).put("data", e.o());
        e.ctx().emit(ev);
    }

    /** An assistant message: the model response it completes (by provider call id) and one tool request per toolRequests element. */
    private void assistantMessage(Event e) {
        e.ctx().family(e.type(), e.r().raw().length, "normalized: model_response Completed (response_uid apiCallId) and tool_call Requested per toolRequests element");
        String response = text(e.data(), "apiCallId");
        Jv.Obj ev = e.event("model_response", 2, "copilot.events.assistant_message->model_response", true, null);
        Jv.Arr blocks = new Jv.Arr();
        String content = text(e.data(), "content");
        if (content != null && !content.isEmpty()) {
            blocks.add(new Jv.Obj().put("block_index", 0).put("block_type", "assistant.message.content").put("block_kind_id", 1).put("block_kind", "Text").put("text", content)
                    .put("basis", "data.content is the message text").put("data", e.data().member("content")));
        }
        ev.put("session_uid", e.session()).put("turn_uid", text(e.data(), "interactionId")).put("response_uid", response).put("item_uid", text(e.data(), "messageId"))
                .put("identity_basis_id", 99).put("identity_basis", "Provider call identifier (apiCallId)")
                .put("model_ref", MapperSupport.modelRef(text(e.data(), "model"), 1, text(e.data(), "providerId"))).put("content_blocks", blocks)
                .put("basis", "copilot.events.assistant_message.v1: an assistant message completes the model response of its provider call (data.apiCallId); "
                        + "data.messageId is the CLI's message id").put("data", e.o());
        e.ctx().emit(ev);
        int index = 0;
        for (Jv request : e.data().member("toolRequests") == null ? List.<Jv>of() : e.data().member("toolRequests").elements()) {
            toolRequest(e, response, request, index++);
        }
    }

    private void toolRequest(Event e, String response, Jv request, int index) {
        String name = text(request, "name");
        MappingContracts.Kind kind = e.ctx().contracts.familyKind(CONTRACT, name, null, name);
        Jv.Obj ev = e.event("tool_call", 1, "copilot.events.tool_request->tool_call.requested", false, null);
        ev.put("session_uid", e.session()).put("turn_uid", text(e.data(), "interactionId")).put("response_uid", response).put("tool_call_uid", text(request, "toolCallId"))
                .put("identity_basis_id", 1).put("identity_basis", IDENTITY_BASIS.get(1)).put("tool_name", name).put("tool_family", text(request, "type"))
                .put("tool_kind_id", kind.id()).put("tool_kind", TOOL_KIND.get(kind.id())).put("tool_kind_basis", kind.rule() + "; copilot.events.tool_request.v1: data.toolRequests["
                        + index + "] is a tool call the response requests by its model-facing call id").put("arguments", request.member("arguments"))
                .put("data", request);
        e.ctx().emit(ev);
    }

    /** A tool execution stage: started (4) with the invoked tool and arguments, or the observed result (2) with its success flag. */
    private void toolStage(Event e, int activity, String rule) {
        e.ctx().family(e.type(), e.r().raw().length, "normalized: tool_call " + (activity == 4 ? "Started" : "Result observed") + " (toolCallId)");
        boolean started = activity == 4;
        Jv.Obj ev = e.event("tool_call", activity, rule, true, started ? null : CopilotTools.status(e.data()));
        ev.put("session_uid", e.session()).put("turn_uid", text(e.data(), "interactionId")).put("tool_call_uid", text(e.data(), "toolCallId"))
                .put("identity_basis_id", 1).put("identity_basis", IDENTITY_BASIS.get(1));
        toolName(e, ev);
        String call = text(e.data(), "toolCallId");
        if (started && text(e.data(), "mcpServerName") != null && call != null) {
            mcpCalls.put(call, new String[] {text(e.data(), "mcpServerName"), text(e.data(), "mcpToolName")});
        }
        ev.put("arguments", e.data().member("arguments")).put("tool_result", started ? null : CopilotTools.result(e.data()))
                .put("mcp", CopilotTools.mcp(e.data(), started, call == null ? null : mcpCalls.get(call)))
                .put("data", e.o());
        e.ctx().emit(ev);
    }

    /** The tool name the stage reports, with its kind (an MCP tool's from its namespace binding, never its name). */
    private static void toolName(Event e, Jv.Obj ev) {
        String name = text(e.data(), "toolName");
        if (name == null) {
            return;
        }
        String server = text(e.data(), "mcpServerName");
        MappingContracts.Kind kind = e.ctx().contracts.familyKind(CONTRACT, server != null ? "mcp" : name, server, text(e.data(), "mcpToolName"));
        ev.put("tool_name", name).put("tool_kind_id", kind.id()).put("tool_kind", TOOL_KIND.get(kind.id())).put("tool_kind_basis", kind.rule());
    }

    private void other(Event e) {
        if (SETTINGS.contains(e.type())) {
            settings(e, "copilot.events.session_settings->session_context.settings", "a session setting as the event reports it");
            return;
        }
        String meaning = RETAINED.get(e.type());
        e.ctx().family(e.type(), e.r().raw().length, meaning == null ? "retained-only: no normalization rule" : "retained whole: " + meaning);
        e.ctx().emit(e.ctx().fallback(e.r(), CONTRACT, "copilot.events.fallback->source_record", e.type(), e.ts(), e.id(), e.session(),
                meaning == null ? "no normalization rule for this event type; retained whole" : "copilot.events.retained.v1: " + meaning + "; retained whole"));
    }
}
