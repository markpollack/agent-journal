package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

/** Traverses only typed source references; payload boundaries are explicit. */
record SourceReferences(
        OcsfPackage core, ExpectedFacts facts, NativeArtifact artifact, EventOrigin origin) {
    void walk(Json value, String pointer) {
        if (value instanceof Json.JArray array) {
            for (int i = 0; i < array.elements().size(); i++) {
                walk(array.elements().get(i), pointer + "/" + i);
            }
        } else if (value instanceof Json.JObject object) {
            reference(object, pointer);
            if (pointer.endsWith("/model_ref")) {
                origin.field(
                        pointer + "/provider_basis_id",
                        object.get("provider_kind") == null ? 3 : 1,
                        "source.provider_basis/v1");
            }
            object.members()
                    .forEach(
                            (key, child) -> {
                                if (!java.util.Set.of(
                                                "data",
                                                "raw_data",
                                                "arguments",
                                                "structured_result",
                                                "schema_document",
                                                "mcp_arguments",
                                                "mcp_structured_content")
                                        .contains(key)) {
                                    walk(child, pointer + "/" + FactComparison.escape(key));
                                }
                            });
        }
    }

    private void reference(Json.JObject object, String pointer) {
        Json reference = object.get("source_ref");
        Integer index = reference == null ? null : Event.integer(reference.member("record_index"));
        if (valid(index, reference)) {
            NativeArtifact.Record record = artifact.record(index);
            String p = pointer + "/source_ref";
            origin.field(p + "/record_bytes", record.raw().length, "source.framing/v1");
            origin.field(p + "/line_ending", record.lineEnding(), "source.framing/v1");
            origin.field(
                    p + "/source_kind_id",
                    core.manifest.source(facts.artifact).member("kind"),
                    "caller.source_kind/v1");
            origin.field(p + "/native_time", facts.nativeTime.get(index), "source.timestamp/v1");
            if (facts.nativeUid.containsKey(index)
                    && (record.parsed() || !"source_record".equals(origin.event().className))) {
                origin.field(
                        p + "/native_uid", facts.nativeUid.get(index), "source.record_identity/v1");
            }
            origin.field(
                    p + "/is_whole_record",
                    whole(pointer, record, object),
                    "source.whole_record_marker/v1");
        }
    }

    private boolean valid(Integer index, Json reference) {
        return index != null
                && index > 0
                && index <= artifact.records().size()
                && facts.artifact.equals(SourceProfile.text(reference, "artifact"));
    }

    private static boolean whole(
            String pointer, NativeArtifact.Record record, Json.JObject object) {
        if (!pointer.isEmpty()) {
            return false;
        }
        if (record.parsed()) {
            return Json.sameValue(record.json(), object.get("data"));
        }
        return record.text() != null
                ? Json.sameValue(FactComparison.value(record.text()), object.get("raw_data"))
                : object.get("raw_bytes_b64") != null;
    }
}
