package io.github.markpollack.journal.ocsf.writer;

import java.util.ArrayList;
import java.util.List;

import static io.github.markpollack.journal.ocsf.writer.MapperSupport.text;

/**
 * Codex CLI rollout, the saved session file (contract {@code codex-cli-0.160.1-rollout}). Scoped state: the turn in
 * force (from {@code turn_context}), the {@code custom_tool_call}s seen (for the derived operation attribution) and
 * the {@code token_count} records of the current turn (whose usage rides on {@code task_complete}). User-role
 * messages and UserMessage items are prompt records (the view decides which are submissions), assistant messages
 * and AgentMessage items are responses sharing one id, custom_tool_call/_output are request/result by call_id,
 * CommandExecution items are operations with a derived attribution, session_meta/turn_context/world_state are
 * settings, developer messages are injected context, token_usage_record is response/turn/thread accounting,
 * task_started/task_complete start and complete the turn. Everything else is retained whole.
 */
final class CodexRolloutMapper implements SourceMapper {

    static final String CONTRACT = "codex-cli-0.160.1-rollout";

    private record Call(String turn, String callId, String name, String input) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final List<NativeRecord> tokenCounts = new ArrayList<>();
    private String currentTurn;

    @Override
    public String contractName() {
        return CONTRACT;
    }

    @Override
    public void map(NativeRecord r, SourceContext ctx) {
        if (!r.parsed()) {
            ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim)");
            ctx.emit(ctx.opaque(r, CONTRACT));
            return;
        }
        Jv o = r.json();
        String type = text(o, "type");
        String ts = text(o, "timestamp");
        Jv p = o.member("payload") instanceof Jv.Obj po ? po : new Jv.Obj();
        String pt = text(p, "type");
        String session = ctx.run.sessionUid();
        String family = pt == null ? String.valueOf(type) : type + "/" + pt;
        if ("session_meta".equals(type)) {
            ctx.family(family, r.raw().length, "normalized: session_context Settings reported (cwd, cli version, provider, git branch)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj settings = new Jv.Obj().put("working_directory", text(p, "cwd")).put("version", text(p, "cli_version")).put("provider", text(p, "model_provider"))
                    .put("git_branch", text(p, "git", "branch"));
            Jv.Obj ev = ctx.event("session_context", 1, r, t, CONTRACT, "codex.rollout.session_meta->session_context.settings", ts, text(p, "id"), true, null);
            ev.put("session_uid", text(p, "id") != null ? text(p, "id") : session).put("context_kind", "session_meta").put("settings", settings)
                    .put("basis", "codex.rollout.settings.v1: session_meta reports the session's launch facts once").put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("turn_context".equals(type)) {
            currentTurn = text(p, "turn_id");
            ctx.family(family, r.raw().length, "normalized: session_context Settings reported at turn scope (cwd, model, approval and sandbox policy)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj settings = new Jv.Obj().put("working_directory", text(p, "cwd")).put("model", text(p, "model")).put("approval_policy", text(p, "approval_policy"));
            if (p.member("sandbox_policy") != null) {
                settings.put("sandbox_policy", p.member("sandbox_policy"));
            }
            Jv.Obj ev = ctx.event("session_context", 1, r, t, CONTRACT, "codex.rollout.turn_context->session_context.settings", ts, null, true, null);
            ev.put("session_uid", session).put("turn_uid", currentTurn).put("context_kind", "turn_context").put("settings", settings)
                    .put("basis", "codex.rollout.settings.v1: turn_context reports the settings in force for the turn").put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("world_state".equals(type)) {
            ctx.family(family, r.raw().length, "normalized: session_context Settings reported (configuration snapshot; model from collaboration_mode)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj settings = new Jv.Obj().put("model", text(p, "state", "collaboration_mode", "model"));
            Jv.Obj ev = ctx.event("session_context", 1, r, t, CONTRACT, "codex.rollout.world_state->session_context.settings", ts, null, true, null);
            ev.put("session_uid", session).put("context_kind", "world_state").put("settings", settings)
                    .put("basis", "codex.rollout.settings.v1: world_state is the CLI's configuration snapshot; only the model is normalized, the rest is retained in data").put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("response_item".equals(type) && "message".equals(pt)) {
            String role = text(p, "role");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Arr blocks = MapperSupport.blocks(ctx.contracts, CONTRACT, p.member("content"));
            if ("user".equals(role)) {
                ctx.family(family + "/user", r.raw().length, "normalized: prompt_turn Prompt submitted (the view classifies environment context)");
                Jv.Obj ev = ctx.event("prompt_turn", 1, r, t, CONTRACT, "codex.rollout.user_message->prompt_turn.submitted", ts, text(p, "id"), true, null);
                ev.put("session_uid", session).put("turn_uid", currentTurn).put("content_blocks", blocks).put("data", o);
                ctx.emit(ev);
            }
            else if ("assistant".equals(role)) {
                ctx.family(family + "/assistant", r.raw().length, "normalized: model_response Completed (same id as the AgentMessage item)");
                Jv.Obj ev = ctx.event("model_response", 2, r, t, CONTRACT, "codex.rollout.assistant_message->model_response", ts, text(p, "id"), true, null);
                ev.put("session_uid", session).put("turn_uid", currentTurn).put("response_uid", text(p, "id")).put("content_blocks", blocks);
                if (text(p, "status") != null) {
                    ev.put("reported_state", text(p, "status"));
                }
                ev.put("basis", "codex.rollout.activity.v1: a response_item message is written once after the response").put("data", o);
                ctx.emit(ev);
            }
            else if ("developer".equals(role)) {
                ctx.family(family + "/developer", r.raw().length, "normalized: session_context Context injected (developer message)");
                Jv.Obj ev = ctx.event("session_context", 2, r, t, CONTRACT, "codex.rollout.developer_message->session_context.context", ts, text(p, "id"), true, null);
                ev.put("session_uid", session).put("turn_uid", currentTurn).put("context_kind", "response_item/message/developer").put("rendered_role", "developer")
                        .put("content_blocks", blocks).put("basis", "codex.rollout.context.v1: a developer-role message is context the CLI injected into the model input").put("data", o);
                ctx.emit(ev);
            }
            else {
                ctx.family(family + "/" + role, r.raw().length, "retained-only: message role without a rule");
                ctx.emit(ctx.fallback(r, CONTRACT, "codex.rollout.fallback->source_record", family + "/" + role, ts, text(p, "id"), session, null));
            }
            return;
        }
        if ("response_item".equals(type) && "custom_tool_call".equals(pt)) {
            ctx.family(family, r.raw().length, "normalized: tool_call Requested (call_id)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            String turn = text(p, "internal_chat_message_metadata_passthrough", "turn_id");
            calls.add(new Call(turn, text(p, "call_id"), text(p, "name"), text(p, "input")));
            int k = ctx.contracts.toolKind(CONTRACT, text(p, "name"));
            Jv.Obj ev = ctx.event("tool_call", 1, r, t, CONTRACT, "codex.rollout.custom_tool_call->tool_call.requested", ts, text(p, "id"), true, null);
            ev.put("session_uid", session).put("turn_uid", turn).put("tool_call_uid", text(p, "call_id")).put("tool_name", text(p, "name"))
                    .put("tool_kind_id", k).put("tool_kind", MapperSupport.TOOL_KIND.get(k)).put("tool_kind_basis", ctx.contracts.toolKindRule(CONTRACT))
                    .put("arguments", p.member("input") == null ? Jv.Null.INSTANCE : p.member("input"));
            if (text(p, "status") != null) {
                ev.put("reported_state", text(p, "status"));
            }
            ev.put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("response_item".equals(type) && "custom_tool_call_output".equals(pt)) {
            ctx.family(family, r.raw().length, "normalized: tool_call Result observed (call_id)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj ev = ctx.event("tool_call", 2, r, t, CONTRACT, "codex.rollout.custom_tool_call_output->tool_call.result", ts, text(p, "id"), true,
                    new MapperSupport.Status(0, 3, "custom_tool_call_output reports no call-level status; per-command outcomes are on operations"));
            ev.put("session_uid", session).put("turn_uid", text(p, "internal_chat_message_metadata_passthrough", "turn_id")).put("tool_call_uid", text(p, "call_id"))
                    .put("tool_result", new Jv.Obj().put("data", p.member("output") == null ? Jv.Null.INSTANCE : p.member("output"))).put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("event_msg".equals(type) && "task_started".equals(pt)) {
            ctx.family(family, r.raw().length, "normalized: prompt_turn Turn started");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj ev = ctx.event("prompt_turn", 3, r, t, CONTRACT, "codex.rollout.task_started->prompt_turn.started", ts, null, true, null);
            ev.put("session_uid", session).put("turn_uid", text(p, "turn_id")).put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("event_msg".equals(type) && "item_completed".equals(pt)) {
            Jv it = p.member("item") instanceof Jv.Obj io ? io : new Jv.Obj();
            String itype = text(it, "type");
            String turn = text(p, "turn_id");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            if ("UserMessage".equals(itype)) {
                ctx.family(family + "/UserMessage", r.raw().length, "normalized: prompt_turn Prompt submitted (second representation of the user message)");
                Jv.Obj ev = ctx.event("prompt_turn", 1, r, t, CONTRACT, "codex.rollout.item_completed.UserMessage->prompt_turn.submitted", ts, text(it, "id"), true, null);
                ev.put("session_uid", session).put("turn_uid", turn).put("content_blocks", MapperSupport.blocks(ctx.contracts, CONTRACT, it.member("content"))).put("data", o);
                ctx.emit(ev);
                return;
            }
            if ("AgentMessage".equals(itype)) {
                ctx.family(family + "/AgentMessage", r.raw().length, "normalized: model_response Completed (same id as the response_item message)");
                Jv.Obj ev = ctx.event("model_response", 2, r, t, CONTRACT, "codex.rollout.item_completed.AgentMessage->model_response", ts, text(it, "id"), true, null);
                ev.put("session_uid", session).put("turn_uid", turn).put("response_uid", text(it, "id"))
                        .put("content_blocks", MapperSupport.blocks(ctx.contracts, CONTRACT, it.member("content")))
                        .put("basis", "item_completed is terminal for the item").put("data", o);
                ctx.emit(ev);
                return;
            }
            if ("CommandExecution".equals(itype)) {
                ctx.family(family + "/CommandExecution", r.raw().length, "normalized: tool_call Operation observed (derived attribution to the wrapping exec call)");
                List<Jv> argv = it.member("command") == null ? List.of() : it.member("command").elements();
                String last = argv.isEmpty() ? "" : argv.get(argv.size() - 1).text();
                List<String> candidates = new ArrayList<>();
                for (Call c : calls) {
                    if (turn != null && turn.equals(c.turn()) && "exec".equals(c.name()) && c.input() != null && last != null && c.input().contains(last)) {
                        candidates.add(c.callId());
                    }
                }
                int outcome = candidates.size() == 1 ? 1 : candidates.size() > 1 ? 2 : 3;
                Jv.Obj op = new Jv.Obj().put("uid", text(it, "id"));
                if (it.member("command") != null) {
                    op.put("argv", it.member("command"));
                }
                if (MapperSupport.isIntegral(it.member("exit_code"))) {
                    op.put("exit_code", it.member("exit_code"));
                }
                if (text(it, "status") != null) {
                    op.put("reported_state", text(it, "status"));
                }
                if (text(it, "stdout") != null) {
                    op.put("stdout", text(it, "stdout"));
                }
                if (text(it, "stderr") != null) {
                    op.put("stderr", text(it, "stderr"));
                }
                op.put("attribution", MapperSupport.attribution(outcome, 2, candidates,
                        "codex.operation_attribution.v1: same turn_id, custom_tool_call.name == exec, command text occurs in call input", turn)).put("data", it);
                String state = text(it, "status");
                int status = "completed".equals(state) ? 1 : "failed".equals(state) ? 2 : 0;
                Jv.Obj ev = ctx.event("tool_call", 3, r, t, CONTRACT, "codex.rollout.CommandExecution->tool_call.operation", ts, text(it, "id"), true,
                        new MapperSupport.Status(status, 1, "reported: item.status"));
                ev.put("session_uid", session).put("turn_uid", turn).put("tool_operation", op);
                if (outcome == 1) {
                    ev.put("tool_call_uid", candidates.get(0));
                }
                ev.put("data", o);
                ctx.emit(ev);
                return;
            }
            ctx.family(family + "/" + itype, r.raw().length, "retained-only: item type without a rule");
            ctx.emit(ctx.fallback(r, CONTRACT, "codex.rollout.fallback->source_record", family + "/" + itype, ts, text(it, "id"), session, null));
            return;
        }
        if ("token_usage_record".equals(type)) {
            ctx.family(family, r.raw().length, "normalized: session_context Accounting reported (response-scope final usage; turn and thread cumulative usage provisional)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Obj ev = ctx.event("session_context", 3, r, t, CONTRACT, "codex.rollout.token_usage_record->session_context.accounting", ts, null, true, null);
            Jv.Arr obs = new Jv.Arr();
            Jv.Obj resp = MapperSupport.usage(2, 2, "codex.rollout.token_usage_record.v1: usage is the cited response's own usage, reported once", ctx.sourceRef(r, null, ts, false), null, p.member("usage"));
            UsageTranches.codex(resp, p.member("usage"), "");
            obs.add(resp);
            Jv.Obj turn = MapperSupport.usage(3, 1, "codex.rollout.token_usage_record.v1: turn_token_usage is cumulative so far in the turn; the turn's final is the last token_count before task_complete", ctx.sourceRef(r, null, ts, false), null, p.member("turn_token_usage"));
            UsageTranches.codex(turn, p.member("turn_token_usage"), "");
            obs.add(turn);
            Jv.Obj thread = MapperSupport.usage(5, 1, "codex.rollout.token_usage_record.v1: thread_token_usage is cumulative so far in the thread (session)", ctx.sourceRef(r, null, ts, false), null, p.member("thread_token_usage"));
            UsageTranches.codex(thread, p.member("thread_token_usage"), "");
            obs.add(thread);
            ev.put("session_uid", text(p, "session_id") != null ? text(p, "session_id") : session).put("turn_uid", text(p, "turn_id")).put("response_uid", text(p, "response_id"))
                    .put("context_kind", "token_usage_record").put("usage_observations", obs).put("basis", "codex.rollout.token_usage_record.v1").put("data", o);
            ctx.emit(ev);
            return;
        }
        if ("event_msg".equals(type) && "token_count".equals(pt)) {
            tokenCounts.add(r);
            ctx.family(family, r.raw().length, "retained whole; its total_token_usage rides on the turn completion as prompt-turn usage (last one final)");
            ctx.emit(ctx.fallback(r, CONTRACT, "codex.rollout.fallback->source_record", family, ts, null, session,
                    "retained whole; info.total_token_usage is cited by the prompt_turn Turn completed event of this turn"));
            return;
        }
        if ("event_msg".equals(type) && "task_complete".equals(pt)) {
            ctx.family(family, r.raw().length, "normalized: prompt_turn Turn completed with the turn's token_count usage (last final)");
            MapperSupport.Time t = ctx.timeFor(r, ts);
            Jv.Arr obs = new Jv.Arr();
            for (int i = 0; i < tokenCounts.size(); i++) {
                NativeRecord tc = tokenCounts.get(i);
                Jv info = tc.json().path("payload", "info");
                Jv total = info == null ? null : info.member("total_token_usage");
                boolean last = i == tokenCounts.size() - 1;
                Jv.Obj ob = MapperSupport.usage(3, last ? 2 : 1, "codex.rollout.token_count.v1: " + (last ? "last cumulative token_count before task_complete" : "superseded by a later token_count in the same turn"),
                        ctx.sourceRef(tc, null, text(tc.json(), "timestamp"), false), null, info);
                UsageTranches.codex(ob, total, "/total_token_usage");
                obs.add(ob);
            }
            tokenCounts.clear();
            Jv.Obj ev = ctx.event("prompt_turn", 2, r, t, CONTRACT, "codex.rollout.task_complete->prompt_turn.completed", ts, null, true,
                    new MapperSupport.Status(0, 3, "task_complete carries no status or error field"));
            ev.put("session_uid", session).put("turn_uid", text(p, "turn_id")).put("usage_observations", obs).put("data", o);
            ctx.emit(ev);
            return;
        }
        ctx.family(family, r.raw().length, "retained-only: no normalization rule");
        ctx.emit(ctx.fallback(r, CONTRACT, "codex.rollout.fallback->source_record", family, ts, null, session, null));
    }
}
