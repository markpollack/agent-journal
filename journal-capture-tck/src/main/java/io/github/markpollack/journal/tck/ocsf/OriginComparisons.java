package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.time.Instant;
import java.util.List;

/** Checks native provenance and permitted origin claims without using labels as exemptions. */
record OriginComparisons(
        OracleReport report,
        OcsfPackage core,
        ExpectedFacts facts,
        NativeArtifact artifact,
        String owner,
        String profile) {
    void check(List<Event> events) {
        events.forEach(this::event);
    }

    private void event(Event event) {
        Integer index = event.recordIndex();
        NativeArtifact.Record record =
                index != null && index > 0 && index <= artifact.records().size()
                        ? artifact.records().get(index - 1)
                        : null;
        if (record == null || !facts.owned.contains(index)) {
            return;
        }
        ownerRouting(record, index);
        EventOrigin origin = origin(event);
        origin.envelope(core);
        if (facts.classUnjudged) retainedRecord(origin, record);
        provenance(origin, record);
        timestamp(origin, index);
        identity(origin, record);
        new SourceReferences(core, facts, artifact, origin).walk(event.json, "");
        origin.captions(event.json, core.catalog.classNamed(event.className), core.catalog, "");
    }

    private EventOrigin origin(Event event) {
        return new EventOrigin(
                report,
                owner,
                event,
                facts.classUnjudged
                        ? io.github.markpollack.journal.tck.verify.Check.Category.PRESERVATION
                        : io.github.markpollack.journal.tck.verify.Check.Category.NORMALIZATION);
    }

    private void ownerRouting(NativeArtifact.Record record, int index) {
        if (!facts.classUnjudged
                && "claude_stream".equals(profile)
                && record.parsed()
                && record.json().member("parent_tool_use_id") != null) {
            FactComparison.compare(
                    report,
                    "fact.owner",
                    owner,
                    new io.github.markpollack.journal.tck.verify.Check.Evidence(
                            "source.owner_routing/v1",
                            "owner:" + index,
                            List.of(facts.artifact + ":record=" + index + "#/parent_tool_use_id"),
                            "declaration:run.source_scope",
                            FactComparison.value(
                                    SourceProfile.text(record.json(), "parent_tool_use_id")),
                            FactComparison.value(
                                    SourceProfile.text(
                                            core.manifest.sourceScope(), "owner_value"))));
        }
    }

    private void provenance(EventOrigin origin, NativeArtifact.Record record) {
        Event event = origin.event();
        int index = record.index();
        Json source = core.manifest.source(facts.artifact);
        String rule = NativeRule.expected(profile, record, event);
        CompatibilityHistory history = CompatibilityHistory.of(source);
        String status = history.statusOf(index);
        boolean unselected = history.declared() && !CompatibilityHistory.normalizing(status);
        if (unselected) {
            rule =
                    "compat."
                            + ("version_unknown".equals(status) ? "version_pending" : status)
                            + "->source_record";
        }
        if (rule != null) {
            origin.field("/journal/rule_uid", rule, "journal.native_rule/v1");
        }
        origin.field("/source_ref/artifact", facts.artifact, "source.address/v1");
        origin.field("/source_ref/record_index", index, "source.address/v1");
        origin.field("/source_ref/record_bytes", record.raw().length, "source.framing/v1");
        origin.field("/source_ref/line_ending", record.lineEnding(), "source.framing/v1");
        if (source != null) {
            origin.field(
                    "/source_ref/source_kind_id", source.member("kind"), "caller.source_kind/v1");
            origin.field(
                    "/journal/contract_uid",
                    unselected
                            ? FactComparison.value("agent-journal/untested-compatibility/0.2")
                            : source.member("contract_uid"),
                    "caller.source_contract/v1");
        }
        origin.field("/journal/run_uid", core.manifest.runUid(), "journal.run/v1");
        origin.field(
                "/journal/invocation_index",
                core.manifest.json().path("run", "invocation_index"),
                "caller.invocation/v1");
        origin.field(
                "/metadata/product",
                core.manifest.shared().path("cli_agent", "product"),
                "caller.cli_product/v1");
        origin.field(
                "/metadata/version",
                core.manifest.json().path("ocsf", "version"),
                "journal.schema/v1");
        if (event.get("cli_agent") != null) {
            origin.field(
                    "/cli_agent",
                    core.manifest.shared().member("cli_agent"),
                    "caller.cli_declaration/v1");
        }
        origin.field("/severity_id", 0, "journal.unspecified_severity/v1");
    }

    private void timestamp(EventOrigin origin, int index) {
        String time = facts.nativeTime.get(index);
        origin.field("/metadata/original_time", time, "source.timestamp/v1");
        origin.field("/journal/time_basis_id", time == null ? 2 : 1, "source.timestamp/v1");
        String prior = time;
        for (int before = index - 1; prior == null && before > 0; before--) {
            prior = facts.nativeTime.get(before);
        }
        Json capture = core.manifest.json().path("run", "capture_start");
        Long millis =
                prior == null
                        ? capture instanceof Json.JString t
                                ? Instant.parse(t.value()).toEpochMilli()
                                : null
                        : Instant.parse(prior).toEpochMilli();
        if (millis != null) {
            origin.field("/time", millis, "source.timestamp_milliseconds/v1");
            origin.field(
                    "/time_dt",
                    new java.time.format.DateTimeFormatterBuilder()
                            .appendInstant(3)
                            .toFormatter()
                            .format(Instant.ofEpochMilli(millis)),
                    "journal.datetime/v1");
        }
        origin.field("/source_ref/native_time", time, "source.timestamp/v1");
    }

    private void identity(EventOrigin origin, NativeArtifact.Record record) {
        Event event = origin.event();
        int index = record.index();
        if (facts.sessionOf.containsKey(index)
                && (record.parsed() || !"source_record".equals(event.className))) {
            origin.field("/session_uid", facts.sessionOf.get(index), "source.session/v1");
        }
        if (facts.nativeUid.containsKey(index)
                && (!"codex_stream".equals(profile) || !"source_record".equals(event.className))) {
            origin.field(
                    "/source_ref/native_uid",
                    facts.nativeUid.get(index),
                    "source.record_identity/v1");
            origin.field(
                    "/metadata/original_event_uid",
                    facts.nativeUid.get(index),
                    "source.record_identity/v1");
        }
        preserved(origin, record);
    }

    private static void preserved(EventOrigin origin, NativeArtifact.Record record) {
        Event event = origin.event();
        if (event.wholeRecord() && record.parsed()) {
            origin.field("/data", record.json(), "source.whole_record/v1");
        }
        if (!record.parsed() && event.get("raw_data") != null) {
            origin.field("/raw_data", record.text(), "source.opaque_text/v1");
        }
    }

    void retained(List<Event> events, java.util.Set<Integer> unselected) {
        ExpectedFacts retained = new ExpectedFacts(facts.artifact);
        retained.classUnjudged = true;
        facts.owned.stream().filter(unselected::contains).forEach(retained.owned::add);
        // Fallback records inherit the last selected native clock, but assert no native time themselves.
        facts.nativeTime.forEach((index, time) -> {
            if (!unselected.contains(index)) retained.nativeTime.put(index, time);
        });
        new OriginComparisons(report, core, retained, artifact, owner, profile).check(events);
    }

    private static void retainedRecord(EventOrigin origin, NativeArtifact.Record record) {
        int status = parseStatus(record);
        String family = retainedType(record);
        origin.field("/activity_id", record.parsed() ? 1 : 2, "journal.retention_activity/v1");
        origin.field("/parse_status_id", status, "source.framing/v1");
        origin.field("/record_type", family, "source.framing/v1");
        origin.field("/source_ref/is_whole_record", true, "source.framing/v1");
    }

    private static int parseStatus(NativeArtifact.Record record) {
        int status =
                switch (record.status()) {
                    case PARSED -> 1;
                    case MALFORMED -> 2;
                    case UNDECODABLE -> 3;
                    case BLANK -> 4;
                };
        if (!record.parsed()
                && record.status() != NativeArtifact.Status.BLANK
                && "none".equals(record.lineEnding())) status = 5;
        return status;
    }

    private static String retainedType(NativeArtifact.Record record) {
        String type = record.parsed() ? SourceProfile.text(record.json(), "type") : "opaque";
        String sub = record.parsed() ? SourceProfile.text(record.json(), "subtype") : null;
        String family = type == null ? "(untyped)" : sub == null ? type : type + "/" + sub;
        return family;
    }

    static void preservation(
            OracleReport report,
            ExpectedFacts facts,
            NativeArtifact artifact,
            List<Event> events,
            String owner) {
        for (Event event : events) {
            Integer index = event.recordIndex();
            if (index == null
                    || index < 1
                    || index > artifact.records().size()
                    || !event.wholeRecord()) {
                continue;
            }
            NativeArtifact.Record record = artifact.record(index);
            if (record.parsed()) {
                new EventOrigin(report, owner, event)
                        .field("/data", record.json(), "source.whole_record/v1");
            } else if (record.text() != null) {
                new EventOrigin(report, owner, event)
                        .field("/raw_data", record.text(), "source.opaque_text/v1");
            }
        }
    }
}
