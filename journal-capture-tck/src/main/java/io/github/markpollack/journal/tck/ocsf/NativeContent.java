package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Native content accompanying independently classified events. */
final class NativeContent {
    private NativeContent() {}

    static void text(SourceFacts.Target target, Json content, String type, String support) {
        if (content instanceof Json.JString) {
            target.event(
                    support,
                    "source.native_details/v1",
                    Map.of(),
                    SourceFacts.textBlock(content, type));
        } else if (content instanceof Json.JArray) {
            blocks(target, content, support);
        }
    }

    static void blocks(SourceFacts.Target target, Json content, String support) {
        List<Json> blocks = content == null ? List.of() : content.elements();
        for (int i = 0; i < blocks.size(); i++) {
            Json block = blocks.get(i);
            String type = SourceProfile.text(block, "type");
            if (!List.of("text", "Text", "input_text", "output_text")
                    .contains(String.valueOf(type))) {
                continue;
            }
            String path = "/content_blocks/" + i;
            target.event(
                    support + "/" + i,
                    "source.native_details/v1",
                    Map.of(),
                    SourceFacts.fields(
                            path + "/block_index",
                            i,
                            path + "/block_type",
                            type,
                            path + "/block_kind_id",
                            1,
                            path + "/text",
                            block.member("text"),
                            path + "/data",
                            block));
        }
    }
}
