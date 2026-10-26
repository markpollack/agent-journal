package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Independent native step and result metadata expectations. */
final class AntigravityNativeDetails {
    private AntigravityNativeDetails() {}

    static void read(ExpectedFacts f, int record, Json n) {
        step(f, record, n.member("step_update"));
        result(f, record, n);
        errors(f, record, n.member("step_update"));
    }

    private static void step(ExpectedFacts f, int record, Json step) {

        if (step == null) {
            return;
        }
        String type = SourceProfile.text(step, "step_type");
        for (ExpectedFacts.Fact fact : List.copyOf(f.facts)) {
            if (fact.recordIndex() == record
                    && fact.fields().get("/identity_basis_id") instanceof Json.JNumber id
                    && id.value().intValue() == 99) {
                new SourceFacts.Target(f, record, fact.className(), fact.activityId())
                        .event(
                                "/step_update/step_index",
                                "source.native_details/v1",
                                fact.identity(),
                                SourceFacts.fields("/identity_basis", "Conversation step index"));
            }
        }
        tool(f, record, step, type);
    }

    private static void tool(ExpectedFacts f, int record, Json step, String type) {
        if (("tool".equals(type) || "subagent".equals(type))
                && "ACTIVE".equals(SourceProfile.text(step, "state"))) {
            int kind = NativeDetails.toolKind(SourceProfile.text(step, "tool_name"));
            int activity = 4;
            Map<String, Json> fields = SourceFacts.fields("/tool_kind_id", kind);
            if (kind == 99) {
                fields.put("/tool_kind", FactComparison.value("other"));
            }
            if (f.requests.stream().anyMatch(r -> r.recordIndex() == record)
                    || f.results.stream().anyMatch(r -> r.recordIndex() == record)) {
                new SourceFacts.Target(f, record, "tool_call", activity)
                        .event(
                                "/step_update/tool_name",
                                "source.native_details/v1",
                                Map.of(),
                                fields);
            }
        }
    }

    private static void result(ExpectedFacts f, int record, Json n) {
        if ("result".equals(SourceProfile.text(n, "event"))) {
            NativeContent.text(
                    new SourceFacts.Target(f, record, "prompt_turn", 2),
                    n.path("result", "response"),
                    "response",
                    "/result/response");
            Json denied = n.path("result", "denied_actions");
            List<Json> actions = denied == null ? List.of() : denied.elements();
            for (int i = 0; i < actions.size(); i++) {
                new SourceFacts.Target(f, record, "prompt_turn", 2)
                        .event(
                                "/result/denied_actions/" + i,
                                "source.native_details/v1",
                                Map.of(),
                                SourceFacts.fields(
                                        "/permission_decisions/" + i + "/data", actions.get(i)));
            }
        }
    }

    private static void errors(ExpectedFacts f, int record, Json step) {
        for (ExpectedFacts.ToolError error : f.toolErrors) {
            if (error.recordIndex() == record && step != null) {
                f.facts.add(
                        new ExpectedFacts.Fact(
                                record,
                                "tool_call",
                                2,
                                SourceFacts.fields("/tool_call_uid", error.id()),
                                "/tool_result/errors",
                                SourceFacts.fields("/error_layer_id", error.layer()),
                                SourceFacts.fields("/data", step.path("tool_info", "error")),
                                List.of("/step_update/tool_info/error"),
                                "source.tool_error_payload/v1"));
            }
        }
    }
}
