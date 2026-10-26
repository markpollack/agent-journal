package io.github.markpollack.journal.tck.ocsf;

import static io.github.markpollack.journal.tck.ocsf.SourceProfile.text;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native discriminators shared by the occurrence inventory and source-record checks. */
public final class SourceVariant {
    private SourceVariant() {}

    /**
     * The variant name of a parsed record under this profile, with the discriminator values that
     * produced it.
     */
    public static Map<String, String> discriminators(String profile, Json o) {
        if ("agy_stream".equals(profile)) {
            return agyDiscriminators(o);
        }
        if ("junie_session_events".equals(profile)) {
            return junieDiscriminators(o);
        }
        Map<String, String> d = new LinkedHashMap<>();
        String type = text(o, "type");
        d.put("type", type);
        switch (profile) {
            case "claude_stream", "claude_transcript" -> claude(d, o, type, profile);
            case "codex_stream" -> {
                if (o.member("item") != null) {
                    d.put("item.type", text(o, "item", "type"));
                }
            }
            case "copilot_otel" -> d.putAll(otelDiscriminators(o, type));
            case "codex_rollout" -> rollout(d, o);
            default -> {}
        }
        return d;
    }

    private static void claude(Map<String, String> d, Json o, String type, String profile) {
        if ("claude_stream".equals(profile)) {
            if ("system".equals(type)) d.put("subtype", text(o, "subtype"));
            if ("user".equals(type))
                d.put("message.content[].type", hasBlock(o, "tool_result") ? "tool_result" : null);
            return;
        }
        {
            if ("attachment".equals(type)) d.put("attachment.type", text(o, "attachment", "type"));
            if ("user".equals(type))
                d.put(
                        "message.content[].type",
                        hasBlock(o, "tool_result") ? "tool_result" : "prompt");
        }
    }

    private static void rollout(Map<String, String> d, Json o) {

        if (o.path("payload", "type") != null) {
            d.put("payload.type", text(o, "payload", "type"));
        }
        if ("message".equals(text(o, "payload", "type"))) {
            d.put("payload.role", text(o, "payload", "role"));
        }
        if (o.path("payload", "item", "type") != null) {
            d.put("payload.item.type", text(o, "payload", "item", "type"));
        }
    }

    /** Junie's session events: the event kind, then the agent event's kind. */
    private static Map<String, String> junieDiscriminators(Json o) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("kind", text(o, "kind"));
        d.put("event.agentEvent.kind", text(o, "event", "agentEvent", "kind"));
        return d;
    }

    /** The OTel file exporter: a span by its GenAI operation, a metric by its name. */
    private static Map<String, String> otelDiscriminators(Json o, String type) {
        Map<String, String> d = new LinkedHashMap<>();
        if ("span".equals(type)) {
            d.put(
                    "attributes.gen_ai.operation.name",
                    text(o, "attributes", "gen_ai.operation.name"));
        }
        if ("metric".equals(type)) {
            d.put("name", text(o, "name"));
        }
        return d;
    }

    /**
     * Antigravity's documented discriminator is event; a step_update is further discriminated by
     * its step type and state.
     */
    private static Map<String, String> agyDiscriminators(Json o) {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("event", text(o, "event"));
        if (o.member("step_update") != null) {
            d.put("step_update.step_type", text(o, "step_update", "step_type"));
            d.put("step_update.state", text(o, "step_update", "state"));
        }
        return d;
    }

    public static String name(String profile, Json o) {
        StringBuilder sb = new StringBuilder();
        for (String v : discriminators(profile, o).values()) {
            if (v != null) {
                sb.append(sb.length() == 0 ? "" : "/").append(v);
            }
        }
        return sb.length() == 0 ? "(untyped)" : sb.toString();
    }

    private static boolean hasBlock(Json o, String blockType) {
        Json content = o.path("message", "content");
        for (Json b : content == null ? List.<Json>of() : content.elements()) {
            if (blockType.equals(text(b, "type"))) {
                return true;
            }
        }
        return false;
    }
}
