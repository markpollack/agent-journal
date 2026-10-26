package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.util.List;
import java.util.Map;

/** Event-local declaration, enum and sequence checks. */
record EventOrigin(
        OracleReport report,
        String owner,
        Event event,
        io.github.markpollack.journal.tck.verify.Check.Category category) {
    EventOrigin(OracleReport report, String owner, Event event) {
        this(
                report,
                owner,
                event,
                io.github.markpollack.journal.tck.verify.Check.Category.NORMALIZATION);
    }

    void envelope(OcsfPackage core) {
        SchemaCatalog.Definition cls = core.catalog.classNamed(event.className);
        if (cls == null) {
            return;
        }
        int uid = cls.uid();
        field("/class_uid", uid, "journal.class/v1");
        field("/class_name", cls.caption(), "journal.class/v1");
        field("/category_uid", uid / 1000, "journal.category/v1");
        field("/category_name", "Agent Execution", "journal.category/v1");
        String activity =
                cls.attributes()
                        .get("activity_id")
                        .enumCaptions()
                        .get(String.valueOf(event.activityId));
        field("/activity_name", activity, "journal.activity/v1");
        field("/type_uid", ((long) uid) * 100 + event.activityId, "journal.type/v1");
        field("/type_name", cls.caption() + ": " + activity, "journal.type/v1");
        field("/metadata/profiles", List.of("datetime"), "journal.datetime_profile/v1");
        Json extension =
                new Json.JObject(
                        Map.of(
                                "name",
                                FactComparison.value(core.manifest.extensionName()),
                                "uid",
                                FactComparison.value(String.valueOf(core.manifest.extensionUid())),
                                "version",
                                FactComparison.value(core.manifest.extensionVersion())));
        field("/metadata/extensions", new Json.JArray(List.of(extension)), "journal.extension/v1");
        int position = core.events.indexOf(event);
        long previous = position == 0 ? 0 : core.events.get(position - 1).sequence;
        boolean ordered = event.sequence > previous;
        report.add(
                new io.github.markpollack.journal.tck.verify.Check(
                        category,
                        "fact.origin",
                        owner,
                        ordered
                                ? io.github.markpollack.journal.tck.verify.Check.Outcome.VERIFIED
                                : io.github.markpollack.journal.tck.verify.Check.Outcome.DEFECTIVE,
                        "Journal sequence is positive and strictly increases; interrupted source"
                                + " prefixes may leave gaps",
                        1,
                        new io.github.markpollack.journal.tck.verify.Check.Evidence(
                                "journal.sequence/v1",
                                event.line + "/journal/sequence",
                                List.of("journal:event_order"),
                                owner + ":line=" + event.line + "#/journal/sequence",
                                FactComparison.value(true),
                                FactComparison.value(ordered),
                                event.json.path("journal", "sequence"))));
    }

    void captions(
            Json value,
            SchemaCatalog.Definition definition,
            SchemaCatalog catalog,
            String pointer) {
        if (!(value instanceof Json.JObject object) || definition == null) {
            return;
        }
        for (Map.Entry<String, Json> entry : object.members().entrySet()) {
            SchemaCatalog.AttributeDef attribute = definition.attributes().get(entry.getKey());
            if (attribute == null) {
                continue;
            }
            String path = pointer + "/" + FactComparison.escape(entry.getKey());
            caption(value, entry, attribute, pointer);
            children(entry.getValue(), attribute, catalog, path);
        }
    }

    private void caption(
            Json value,
            Map.Entry<String, Json> entry,
            SchemaCatalog.AttributeDef attribute,
            String pointer) {
        if (attribute.enumCaptions() != null && entry.getKey().endsWith("_id")) {
            String caption = attribute.enumCaptions().get(entry.getValue().scalarText());
            String sibling = entry.getKey().substring(0, entry.getKey().length() - 3);
            // Other carries source-defined captions, checked with the native fact rather than the
            // schema's generic label.
            if (caption != null
                    && !"99".equals(entry.getValue().scalarText())
                    && value.member(sibling) != null) {
                field(pointer + "/" + sibling, caption, "journal.enum_caption/v1");
            }
        }
    }

    private void children(
            Json value, SchemaCatalog.AttributeDef attribute, SchemaCatalog catalog, String path) {
        if (attribute.objectType() != null) {
            SchemaCatalog.Definition child = catalog.object(attribute.objectType());
            if (child == null) {
                child =
                        catalog.objects().values().stream()
                                .filter(d -> attribute.objectType().equals(d.name()))
                                .findFirst()
                                .orElse(null);
            }
            if (value instanceof Json.JArray array) {
                for (int i = 0; i < array.elements().size(); i++) {
                    captions(array.elements().get(i), child, catalog, path + "/" + i);
                }
            } else {
                captions(value, child, catalog, path);
            }
        }
    }

    void field(String pointer, Object value, String meaning) {
        FactComparison.compare(
                report,
                "fact.origin",
                owner,
                new io.github.markpollack.journal.tck.verify.Check.Evidence(
                        meaning,
                        event.recordIndex()
                                + ":"
                                + event.className
                                + "/"
                                + event.activityId
                                + pointer,
                        List.of(event.artifact() + ":record=" + event.recordIndex() + "#"),
                        owner + ":line=" + event.line + "#" + pointer,
                        FactComparison.value(value),
                        FactComparison.at(event.json, pointer)),
                category);
    }
}
