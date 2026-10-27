package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/**
 * Expands the established source readers' family records into attributable attribute expectations.
 */
final class FamilyFacts {

    private FamilyFacts() {}

    static void expand(ExpectedFacts f) {
        f.requests.forEach(r -> request(f, r));
        f.results.forEach(r -> result(f, r));
        f.responses.forEach(r -> ResponseFacts.read(f, r));
        f.prompts.forEach(p -> prompt(f, p));
        for (ExpectedFacts.TurnStarted t : f.started) {
            new SourceFacts.Target(f, t.recordIndex(), "prompt_turn", 3)
                    .event(
                            "",
                            "source." + "turn_started" + "/v1",
                            Map.of(),
                            SourceFacts.fields("/turn_uid", t.turnUid()));
        }
        f.completed.forEach(t -> completed(f, t));
        f.contexts.forEach(c -> context(f, c));
        f.operations.forEach(o -> operation(f, o));
        f.costs.forEach(c -> cost(f, c));
        for (ExpectedFacts.Attribute a : f.attributes) {
            new SourceFacts.Target(f, a.recordIndex(), a.className(), a.activityId())
                    .event(
                            "",
                            "source." + "attribute" + "/v1",
                            Map.of(),
                            SourceFacts.fields("/" + a.attribute(), a.value()));
        }
        for (ExpectedFacts.Decision d : f.decisions) {
            decision(f, d);
        }
    }

    private static void request(ExpectedFacts f, ExpectedFacts.Request r) {
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/tool_name",
                        r.name(),
                        "/arguments",
                        r.arguments(),
                        "/tool_family",
                        r.family(),
                        "/tool_namespace",
                        r.namespace(),
                        "/identity_basis_id",
                        r.identityBasisId(),
                        "/response_uid",
                        r.responseUid(),
                        "/reported_state",
                        r.reportedState());
        if (r.kindId() != null) {
            fields.put("/tool_kind_id", FactComparison.value(r.kindId()));
        }
        new SourceFacts.Target(f, r.recordIndex(), "tool_call", r.activityId())
                .event(
                        "",
                        "source." + "request" + "/v1",
                        SourceFacts.fields("/tool_call_uid", r.id()),
                        fields);
    }

    private static void result(ExpectedFacts f, ExpectedFacts.Result r) {
        boolean reported = r.isError() instanceof Json.JBool;
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/tool_result/data",
                        r.content(),
                        "/tool_result/error_reported",
                        r.content() == null ? null : r.isError(),
                        "/tool_result/structured_result",
                        r.structured(),
                        "/identity_basis_id",
                        r.identityBasisId(),
                        "/tool_family",
                        r.family(),
                        "/status_id",
                        reported ? ((Json.JBool) r.isError()).value() ? 2 : 1 : 0,
                        "/status_basis_id",
                        reported ? 1 : 3);
        if (r.reportedState() != null) {
            fields.put("/reported_state", FactComparison.value(r.reportedState()));
        }
        new SourceFacts.Target(f, r.recordIndex(), "tool_call", 2)
                .event(
                        "",
                        "source." + "result" + "/v1",
                        SourceFacts.fields("/tool_call_uid", r.id()),
                        fields);
    }

    private static void prompt(ExpectedFacts f, ExpectedFacts.Prompt p) {
        Map<String, Json> fields = SourceFacts.fields("/turn_uid", p.turnUid());
        texts(fields, p.texts());
        new SourceFacts.Target(f, p.recordIndex(), "prompt_turn", 1)
                .event("", "source." + "prompt" + "/v1", Map.of(), fields);
    }

    private static void completed(ExpectedFacts f, ExpectedFacts.TurnCompleted t) {
        Map<String, Json> fields =
                SourceFacts.fields("/status_id", t.statusId(), "/stop_reason", t.stopReason());
        texts(fields, t.texts());
        new SourceFacts.Target(f, t.recordIndex(), "prompt_turn", 2)
                .event("", "source." + "turn_completed" + "/v1", Map.of(), fields);
    }

    static void fallbacks(
            ExpectedFacts f,
            NativeArtifact artifact,
            String profile,
            java.util.Set<Integer> unselected) {
        for (NativeArtifact.Record r : artifact.records()) {
            if (!f.owned.contains(r.index())
                    || (r.parsed() && f.normalized.contains(r.index()))
                    || unselected.contains(r.index())) {
                continue;
            }
            fallback(f, r, profile);
        }
    }

    private static void fallback(ExpectedFacts f, NativeArtifact.Record r, String profile) {
        int status =
                switch (r.status()) {
                    case PARSED -> 1;
                    case MALFORMED -> 2;
                    case UNDECODABLE -> 3;
                    case BLANK -> 4;
                };
        if (!r.parsed()
                && r.status() != NativeArtifact.Status.BLANK
                && "none".equals(r.lineEnding())) {
            status = 5;
        }
        new SourceFacts.Target(f, r.index(), "source_record", r.parsed() ? 1 : 2)
                .event(
                        "",
                        "source." + "fallback" + "/v1",
                        Map.of(),
                        SourceFacts.fields(
                                "/parse_status_id",
                                status,
                                "/record_type",
                                recordType(r, profile)));
    }

    private static String recordType(NativeArtifact.Record r, String profile) {
        if (!r.parsed()) {
            return "opaque";
        }
        if ("junie_session_events".equals(profile)
                && r.json().path("event", "agentEvent", "kind") != null) {
            return SourceProfile.text(r.json(), "event", "agentEvent", "kind");
        }
        return SourceVariant.name(profile, r.json());
    }

    static void protocolFacts(ExpectedFacts f) {
        for (ExpectedFacts.Mcp m : f.mcp) {
            new SourceFacts.Target(f, m.recordIndex(), "tool_call", m.activityId())
                    .event(
                            "",
                            "source." + "mcp.target" + "/v1",
                            SourceFacts.fields("/tool_call_uid", m.id()),
                            SourceFacts.fields(
                                    "/mcp/target_server_name",
                                    m.server(),
                                    "/mcp/target_server_basis_id",
                                    m.serverBasis(),
                                    "/mcp/target_tool_name",
                                    m.tool(),
                                    "/mcp/target_tool_basis_id",
                                    m.toolBasis(),
                                    "/mcp/target_arguments_pointer",
                                    m.argumentsPointer()));
        }
        for (ExpectedFacts.ToolError e : f.toolErrors) {
            f.facts.add(
                    new ExpectedFacts.Fact(
                            e.recordIndex(),
                            "tool_call",
                            2,
                            SourceFacts.fields("/tool_call_uid", e.id()),
                            "/tool_result/errors",
                            SourceFacts.fields("/error_layer_id", e.layer()),
                            SourceFacts.fields(
                                    "/error_layer_id",
                                    e.layer(),
                                    "/error_code",
                                    e.code(),
                                    "/message",
                                    e.message()),
                            List.of(""),
                            "source.tool_error/v1"));
        }
    }

    private static void texts(Map<String, Json> fields, List<String> texts) {
        if (texts == null) {
            fields.put("/content_blocks", null);
        } else {
            for (int i = 0; i < texts.size(); i++) {
                fields.put("/content_blocks/" + i + "/text", FactComparison.value(texts.get(i)));
            }
        }
    }

    private static void context(ExpectedFacts f, ExpectedFacts.Context c) {
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/turn_uid",
                        c.turnUid(),
                        "/rendered_role",
                        c.renderedRole(),
                        "/tool_call_uid",
                        f.contextToolCalls.get(c.recordIndex() + ":" + c.contextKind()));
        if (!c.settings().isEmpty()) {
            c.settings()
                    .forEach(
                            (name, value) ->
                                    fields.put("/settings/" + FactComparison.escape(name), value));
        }
        if (c.blocks() == null) {
            fields.put("/content_blocks", null);
        } else {
            for (int i = 0; i < c.blocks().size(); i++) {
                ExpectedFacts.Block b = c.blocks().get(i);
                String p = "/content_blocks/" + i;
                fields.putAll(
                        SourceFacts.fields(
                                p + "/block_index",
                                i,
                                p + "/block_type",
                                b.blockType(),
                                p + "/block_kind_id",
                                "rendered".equals(b.blockType()) || "string".equals(b.blockType())
                                        ? 1
                                        : ResponseFacts.blockKind(b.blockType()),
                                p + "/text",
                                b.text(),
                                p + "/data",
                                b.data()));
            }
        }
        new SourceFacts.Target(f, c.recordIndex(), "session_context", c.activityId())
                .event(
                        "",
                        "source." + "context" + "/v1",
                        SourceFacts.fields("/context_kind", c.contextKind()),
                        fields);
    }

    private static void operation(ExpectedFacts f, ExpectedFacts.Operation o) {
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/tool_operation/command_line",
                        o.commandLine(),
                        "/tool_operation/identity_basis_id",
                        o.identityBasisId(),
                        "/tool_operation/reported_state",
                        o.reportedState(),
                        "/tool_operation/exit_code",
                        o.exitCode() == null || o.exitCode().isNull() ? null : o.exitCode(),
                        "/status_id",
                        o.statusId(),
                        "/tool_operation/attribution/outcome_id",
                        o.outcomeId(),
                        "/tool_operation/attribution/method_id",
                        o.methodId(),
                        "/tool_operation/attribution/candidates",
                        o.candidates(),
                        "/tool_call_uid",
                        o.outcomeId() == 1 ? o.candidates().get(0) : null);
        if (o.argv() != null) {
            fields.put("/tool_operation/argv", o.argv());
        }
        new SourceFacts.Target(f, o.recordIndex(), "tool_call", 3)
                .event(
                        "",
                        "source." + "operation" + "/v1",
                        SourceFacts.fields("/tool_operation/uid", o.uid()),
                        fields);
    }

    private static void cost(ExpectedFacts f, ExpectedFacts.Cost c) {
        boolean context =
                c.model() != null
                        || f.completed.stream().noneMatch(x -> x.recordIndex() == c.recordIndex());
        String cls = context ? "session_context" : "prompt_turn";
        int activity = context ? 3 : 2;
        Map<String, Json> identity =
                c.model() == null ? Map.of() : SourceFacts.fields("/model_ref/name", c.model());
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/total_cost",
                        c.amount(),
                        "/currency",
                        c.currency(),
                        "/scope_id",
                        c.scopeId(),
                        "/cost_basis",
                        c.basis());
        fields.put("/cost_basis_window", c.basis() == null ? null : FactComparison.value("most_recent_request"));
        if (c.quantityKindId() != null) {
            fields.put("/quantity_kind_id", FactComparison.value(c.quantityKindId()));
        }
        if (c.aggregationId() != null) {
            fields.put("/aggregation_id", FactComparison.value(c.aggregationId()));
        }
        new SourceFacts.Target(f, c.recordIndex(), cls, activity)
                .observation(
                        c.model() == null ? "/cost_observation" : "/cost_observations",
                        "",
                        "source.cost/v2",
                        identity,
                        fields);
    }

    private static void decision(ExpectedFacts f, ExpectedFacts.Decision d) {
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/reported_decision",
                        d.reportedDecision(),
                        "/decision_id",
                        d.decisionId(),
                        "/decision_source",
                        d.source(),
                        "/reason_type",
                        d.reasonType(),
                        "/scope_id",
                        d.scopeId(),
                        "/attribution/outcome_id",
                        d.outcomeId(),
                        "/attribution/method_id",
                        d.methodId(),
                        "/attribution/candidates",
                        d.candidates());
        boolean result = f.results.stream().anyMatch(r -> r.recordIndex() == d.recordIndex());
        new SourceFacts.Target(f, d.recordIndex(), result ? "tool_call" : "prompt_turn", 2)
                .observation(
                        result ? "/permission_decision" : "/permission_decisions",
                        "",
                        "source.permission/v1",
                        Map.of(),
                        fields);
    }
}
