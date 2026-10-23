package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Antigravity CLI headless {@code stream-json} live output, read from the vendor's headless-mode documentation
 * (antigravity.google/docs/cli/headless, fetched 2026-10-23) and its public CHANGELOG at tag 1.4.6, never from the
 * writer's mapper. No emitter source, typed schema or vendor test is published. The readings:
 *
 * <ul>
 * <li>Every record reports its conversation id (top-level on {@code init}, inside {@code step_update} and {@code result}):
 * the session of that record.</li>
 * <li>A step is identified by its {@code step_index}, documented as the zero-based index of the step: the conversation's
 * own numbering (identity basis 99 Other), neither a model-facing call id nor a stream-local item id.</li>
 * <li>{@code init} reports the run configuration: cwd, permission mode, model and agent when overridden, the tool names.</li>
 * <li>A DONE {@code user_input} step is a prompt submission without content.</li>
 * <li>An {@code agent_response} step is a content item of a model response the stream does not identify: while ACTIVE a
 * Partial view whose content is its {@code text_delta} fragment, when DONE an item completed with its last fragment; every
 * fragment is an Increment of the step's text (the documentation's {@code jq -j} example joins them in order, and the
 * result's response is their concatenation, not a further fragment). The DONE record carries the step's own usage
 * (documented as per-step usage), whose unit is the conversation step (scope 99 Other, "Conversation step"): nothing
 * establishes that one step is one model invocation, so it is never a model response's usage.</li>
 * <li>A tool step (and a {@code subagent} step completing an {@code invoke_subagent} call) is a started invocation while
 * ACTIVE, with the tool name and parameters it reports; when DONE or ERROR a result whose content is the reported
 * output, else the reported error, else (subagent step) the subagent_info. It failed only where {@code tool_info.error}
 * is reported (documented as present when the tool fails); otherwise the outcome is not derivable, because a soft-denied
 * command reports DONE without output or error.</li>
 * <li>{@code result} completes the turn: its status word (SUCCESS, ERROR, or another documented value), the turn's own
 * response text, the session's cumulative usage (scope Session, a cumulative snapshot) and each {@code denied_actions}
 * entry as a run-scope denial that names a permission category, never a step, so it is unattributed with no
 * candidates.</li>
 * </ul>
 * Usage: cache reads, output, thinking (as reasoning) and the total are reported; input excludes cache reads but no cache
 * writes are reported, so the uncached tranche is unavailable with input as its conditional value
 * ({@link TrancheReading#antigravity}), and whether thinking is part of output is not stated, so no relation is stated.
 * The session's cumulative usage covers the steps and can exceed their sum (an interrupted run); neither is derived from
 * the other. Checkpoint, system_message and other steps are retained whole. The stream carries no timestamps.
 */
final class AntigravityStreamProfile implements SourceProfile {

    /** identity_basis_id of a step index: 99 Other (a conversation step index). */
    private static final int STEP_INDEX = 99;
    /** The unit of a step's own usage: scope 99 Other named "Conversation step". */
    private static final String STEP_SCOPE = "Conversation step";
    /** text_update_id of a text fragment: 1 Increment. */
    private static final int INCREMENT = 1;

    @Override
    public ExpectedFacts expected(NativeArtifact artifact, Json source, Json scope) {
        ExpectedFacts f = new ExpectedFacts(SourceProfile.text(source, "artifact"));
        for (NativeArtifact.Record r : artifact.records()) {
            f.owned.add(r.index());
            if (!r.parsed()) {
                continue;
            }
            if (record(f, r.index(), r.json())) {
                f.normalized.add(r.index());
            }
        }
        return f;
    }

    /** The facts one parsed record states, with its session; {@code true} when a normalization rule covers it. */
    private static boolean record(ExpectedFacts f, int index, Json o) {
        String event = SourceProfile.text(o, "event");
        Json payload = event == null ? null : o.member(event);
        if (!(payload instanceof Json.JObject)) {
            return false;
        }
        // init reports its conversation id beside the payload; step_update and result inside it
        String conversation = SourceProfile.text("init".equals(event) ? o : payload, "conversation_id");
        if (conversation != null) {
            f.sessionOf.put(index, conversation);
        }
        return switch (event) {
            case "init" -> init(f, index, payload);
            case "step_update" -> step(f, index, payload);
            case "result" -> result(f, index, payload);
            default -> false;
        };
    }

    private static boolean init(ExpectedFacts f, int index, Json init) {
        Map<String, Json> settings = new LinkedHashMap<>();
        put(settings, "working_directory", init.member("cwd"));
        put(settings, "permission_mode", init.member("permission_mode"));
        put(settings, "model", init.member("model"));
        put(settings, "agent", init.member("agent"));
        if (init.member("tools") instanceof Json.JArray) {
            settings.put("tool_names", init.member("tools"));
        }
        f.contexts.add(new ExpectedFacts.Context(index, 1, "init", null, settings, null, null));
        return true;
    }

    private static void put(Map<String, Json> settings, String name, Json value) {
        if (value instanceof Json.JString) {
            settings.put(name, value);
        }
    }

    private static boolean step(ExpectedFacts f, int index, Json s) {
        String type = SourceProfile.text(s, "step_type");
        String state = SourceProfile.text(s, "state");
        if ("user_input".equals(type) && "DONE".equals(state)) {
            f.prompts.add(new ExpectedFacts.Prompt(index, null, null));
            return true;
        }
        if ("agent_response".equals(type) && ("ACTIVE".equals(state) || "DONE".equals(state))) {
            response(f, index, s, "DONE".equals(state));
            return true;
        }
        // a subagent step reporting a tool name completes the invoke_subagent call of the same step
        boolean toolStep = "tool".equals(type) || ("subagent".equals(type) && SourceProfile.text(s, "tool_name") != null);
        return toolStep && tool(f, index, s, state, "subagent".equals(type));
    }

    /** A tool step: a started invocation while ACTIVE, a result when DONE or ERROR; {@code false} for any other state. */
    private static boolean tool(ExpectedFacts f, int index, Json s, String state, boolean subagent) {
        if ("ACTIVE".equals(state)) {
            Json info = s.member("tool_info");
            f.requests.add(new ExpectedFacts.Request(index, id(s), SourceProfile.text(s, "tool_name"), info == null ? null : info.member("parameters"), null, state, null, STEP_INDEX,
                    4, null, null));
            return true;
        }
        if ("DONE".equals(state) || "ERROR".equals(state)) {
            toolResult(f, index, s, subagent);
            return true;
        }
        return false;
    }

    private static void response(ExpectedFacts f, int index, Json s, boolean done) {
        Json delta = s.member("text_delta");
        boolean text = delta instanceof Json.JString;
        f.responses.add(new ExpectedFacts.Response(index, id(s), text ? List.of(delta) : null, null, done, null, null, STEP_INDEX, done ? 3 : 1, text ? INCREMENT : null));
        if (s.member("usage") instanceof Json.JObject usage) {
            f.usages.add(new ExpectedFacts.Usage(index, index, STEP_SCOPE, done ? 2 : 1, TrancheReading.antigravity(usage), 1));
        }
    }

    /** A finished step's result: the reported output, else the error, else (subagent step) the subagent_info; failed only where the error is reported. */
    private static void toolResult(ExpectedFacts f, int index, Json s, boolean subagent) {
        Json info = s.member("tool_info");
        Json output = info == null ? null : info.member("output");
        Json error = info == null ? null : info.member("error");
        Json content = output != null ? output : error;
        if (content == null && subagent) {
            content = s.member("subagent_info");
        }
        f.results.add(new ExpectedFacts.Result(index, id(s), content, error != null ? new Json.JBool(true) : null, null, null, SourceProfile.text(s, "state"), STEP_INDEX, null));
    }

    /** The step's index as decimal text, or {@code null} when the record reports none. */
    private static String id(Json s) {
        return s.member("step_index") instanceof Json.JNumber n && SourceProfile.isIntegral(n) ? n.value().toBigIntegerExact().toString() : null;
    }

    private static boolean result(ExpectedFacts f, int index, Json res) {
        String status = SourceProfile.text(res, "status");
        int statusId = status == null ? 0 : switch (status) {
            case "SUCCESS" -> 1;
            case "ERROR" -> 2;
            default -> 99;
        };
        Json response = res.member("response");
        f.completed.add(new ExpectedFacts.TurnCompleted(index, statusId, null, response instanceof Json.JString t ? List.of(t.value()) : null));
        if (res.member("usage") instanceof Json.JObject usage) {
            f.usages.add(new ExpectedFacts.Usage(index, index, 5, 2, TrancheReading.antigravity(usage), null, null, null, 2));
        }
        int denied = res.member("denied_actions") == null ? 0 : res.member("denied_actions").elements().size();
        for (int i = 0; i < denied; i++) {
            f.decisions.add(new ExpectedFacts.Decision(index, "denied", 2, null, null, 4, 3, 2, List.of(), null));
        }
        return true;
    }
}
