package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent response and content-block expectations. */
final class ResponseFacts {
    private static final Map<String, Integer> KINDS =
            Map.ofEntries(
                    Map.entry("text", 1),
                    Map.entry("Text", 1),
                    Map.entry("input_text", 1),
                    Map.entry("output_text", 1),
                    Map.entry("thinking", 2),
                    Map.entry("reasoning", 2),
                    Map.entry("tool_use", 3),
                    Map.entry("tool_result", 4),
                    Map.entry("image", 5),
                    Map.entry("input_image", 5));

    private ResponseFacts() {}

    static void read(ExpectedFacts f, ExpectedFacts.Response r) {
        Map<String, Json> fields =
                SourceFacts.fields(
                        "/identity_basis_id",
                        r.itemIdentityBasisId(),
                        "/stop_reason",
                        r.stopReason());
        if (r.model() != null) {
            fields.put("/model_ref/name", FactComparison.value(r.model()));
        }
        if (r.blocks() == null) {
            fields.put("/content_blocks", null);
        } else {
            for (int i = 0; i < r.blocks().size(); i++) {
                fields.putAll(block(r, i));
            }
        }
        Map<String, Json> identity =
                SourceFacts.fields(
                        r.itemIdentityBasisId() == null ? "/response_uid" : "/item_uid", r.id());
        new SourceFacts.Target(f, r.recordIndex(), "model_response", r.activityId())
                .event("", "source." + "response" + "/v1", identity, fields);
    }

    private static Map<String, Json> block(ExpectedFacts.Response r, int i) {
        Map<String, Json> fields = new LinkedHashMap<>();
        Json block = r.blocks().get(i);
        String pointer = "/content_blocks/" + i;
        fields.putAll(
                SourceFacts.fields(
                        pointer + "/block_index",
                        i,
                        pointer + "/block_type",
                        type(r, block),
                        pointer + "/data",
                        block,
                        pointer + "/block_kind_id",
                        kind(r, block),
                        pointer + "/reasoning_form_id",
                        r.reasoningForm(),
                        pointer + "/text_update_id",
                        r.textUpdate()));
        if ("tool_use".equals(SourceProfile.text(block, "type"))) {
            fields.put(pointer + "/tool_call_uid", block.member("id"));
        }
        if ("tool_result".equals(SourceProfile.text(block, "type"))) {
            fields.put(pointer + "/tool_call_uid", block.member("tool_use_id"));
        }
        if (block.member("text") != null) {
            fields.put(pointer + "/text", block.member("text"));
        }
        if (block.member("thinking") != null) {
            fields.put(pointer + "/text", block.member("thinking"));
        }
        if (block instanceof Json.JString) {
            fields.put(pointer + "/text", block);
        }
        return fields;
    }

    private static Json type(ExpectedFacts.Response response, Json block) {
        if (!(block instanceof Json.JString)) {
            return block.member("type");
        }
        if (response.textUpdate() != null) {
            return FactComparison.value("text_delta");
        }
        if (response.itemIdentityBasisId() == null) {
            return FactComparison.value("string");
        }
        return FactComparison.value(
                response.reasoningForm() == null ? "agent_message.text" : "reasoning.text");
    }

    private static int kind(ExpectedFacts.Response response, Json block) {
        return block instanceof Json.JString
                ? response.reasoningForm() == null ? 1 : 2
                : blockKind(SourceProfile.text(block, "type"));
    }

    static int blockKind(String type) {
        return KINDS.getOrDefault(String.valueOf(type), 99);
    }
}
