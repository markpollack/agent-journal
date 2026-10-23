package io.github.markpollack.journal.ocsf.writer;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-source state and services a mapper uses while interpreting one artifact: the source descriptor, the run facts,
 * the contract registry, the time-fallback state (latest preceding reported time in this artifact), the family
 * inventory (record type to count/bytes/disposition) and the event sink. Mapper-specific lifecycle state (open
 * responses, pending calls) lives in the mapper instance, scoped to the source.
 */
final class SourceContext {

    /** Where mapped events go. {@code owner} selects a child core ({@code null}: the root core). */
    interface Sink {
        void emit(String owner, Jv.Obj event);
    }

    final SourceDescriptor source;
    final RunDescriptor run;
    final MappingContracts contracts;
    final SchemaPin pin;
    private final Sink sink;
    private final long captureStartMs;
    private Long lastMs;
    private Integer lastIndex;
    final Map<String, Family> families = new TreeMap<>();

    static final class Family {
        int records;
        long bytes;
        String disposition;
        int unselected;
        String unselectedDisposition;
    }

    SourceContext(SourceDescriptor source, RunDescriptor run, MappingContracts contracts, SchemaPin pin, Sink sink) {
        this.source = source;
        this.run = run;
        this.contracts = contracts;
        this.pin = pin;
        this.sink = sink;
        this.captureStartMs = run.captureStart().toEpochMilli();
    }

    void emit(Jv.Obj event) {
        sink.emit(null, event);
    }

    void emit(String owner, Jv.Obj event) {
        sink.emit(owner, event);
    }

    /** Counts a record under its family with the mapping's disposition for that family. */
    void family(String recordType, long bytes, String disposition) {
        Family f = families.computeIfAbsent(recordType == null ? "(untyped)" : recordType, k -> new Family());
        f.records++;
        f.bytes += bytes;
        f.disposition = disposition;
    }

    /**
     * Counts a record retained whole and unnormalized because no compatible entry was selected when it arrived; the
     * family keeps the disposition its normalized records were given, and states the unselected ones apart.
     */
    void unselected(String recordType, long bytes, String disposition) {
        Family f = families.computeIfAbsent(recordType == null ? "(untyped)" : recordType, k -> new Family());
        f.records++;
        f.bytes += bytes;
        f.unselected++;
        f.unselectedDisposition = disposition;
    }

    /** Source time when reported; else the latest preceding reported time in this artifact; else the capture start. */
    MapperSupport.Time timeFor(NativeRecord r, String reportedText) {
        if (reportedText != null) {
            long ms;
            try {
                ms = MapperSupport.rfc3339ToMs(reportedText);
            }
            catch (RuntimeException e) {
                return fallback(r, "record " + r.index() + " reports an unparseable timestamp " + reportedText + "; ");
            }
            lastMs = ms;
            lastIndex = r.index();
            return new MapperSupport.Time(ms, 1, "record reports timestamp " + reportedText);
        }
        return fallback(r, "");
    }

    private MapperSupport.Time fallback(NativeRecord r, String prefix) {
        if (lastMs != null) {
            return new MapperSupport.Time(lastMs, 2, prefix + "no timestamp on record " + r.index() + "; latest preceding reported time is record " + lastIndex + " of " + source.artifact());
        }
        return new MapperSupport.Time(captureStartMs, 2, prefix + "no timestamp on record " + r.index() + " and none precedes; capture start " + run.captureStart());
    }

    Jv.Obj sourceRef(NativeRecord r, String nativeUid, String nativeTime, boolean whole) {
        Jv.Obj s = new Jv.Obj().put("source_kind_id", source.kind()).put("source_kind", source.kindCaption())
                .put("artifact", source.artifact()).put("record_index", r.index()).put("record_bytes", r.raw().length)
                .put("line_ending", r.ending()).put("is_whole_record", whole);
        if (nativeUid != null) {
            s.put("native_uid", nativeUid);
        }
        if (nativeTime != null) {
            s.put("native_time", nativeTime);
        }
        return s;
    }

    /**
     * The OCSF envelope plus Journal provenance for one event; the caller adds the class-specific attributes.
     * Numeric identifiers come from the pinned compiled schema only. The producing contract rule is
     * {@code journal.rule_uid} (extension 0.5.0); OCSF {@code unmapped} is left to unmapped source data.
     */
    Jv.Obj event(String className, int activity, NativeRecord r, MapperSupport.Time time, String contract, String ruleId,
            String originalTime, String nativeUid, boolean whole, MapperSupport.Status status) {
        SchemaPin.ClassDef c = pin.classNamed(className);
        Jv.Obj ev = new Jv.Obj();
        ev.put("class_uid", c.classUid()).put("class_name", c.caption()).put("category_uid", c.categoryUid()).put("category_name", c.categoryName())
                .put("activity_id", activity).put("activity_name", c.activityName(activity))
                .put("type_uid", (long) c.classUid() * 100 + activity).put("type_name", c.caption() + ": " + c.activityName(activity))
                .put("severity_id", 0).put("severity", "Unknown")
                .put("time", time.ms()).put("time_dt", MapperSupport.msToRfc3339(time.ms()));
        Jv.Obj metadata = new Jv.Obj().put("version", pin.ocsfVersion()).put("product", run.cli().product())
                .put("extensions", new Jv.Arr(List.of(new Jv.Obj().put("name", SchemaPin.EXTENSION).put("uid", Integer.toString(pin.extensionUid())).put("version", pin.extensionVersion()))))
                .put("profiles", Jv.arr("datetime"));
        if (originalTime != null) {
            metadata.put("original_time", originalTime);
        }
        if (nativeUid != null) {
            metadata.put("original_event_uid", nativeUid);
        }
        ev.put("metadata", metadata);
        ev.put("journal", new Jv.Obj().put("run_uid", run.runUid()).put("invocation_index", run.invocationIndex())
                .put("sequence", 0) // assigned by the core when appended
                .put("contract_uid", contracts.uid(contract)).put("rule_uid", contracts.rule(contract, ruleId))
                .put("time_basis_id", time.basisId()).put("time_basis", MapperSupport.TIME_BASIS.get(time.basisId()))
                .put("basis", time.detail()));
        ev.put("source_ref", sourceRef(r, nativeUid, originalTime, whole));
        if (status != null) {
            ev.put("status_id", status.id()).put("status", status.id() == 99 && status.label() != null ? status.label() : MapperSupport.STATUS.get(status.id()))
                    .put("status_basis_id", status.basisId()).put("status_basis", MapperSupport.STATUS_BASIS.get(status.basisId()));
            if (status.derivation() != null) {
                ev.put("derivation", status.derivation());
            }
        }
        return ev;
    }

    /** The whole-record fallback for a parsed record no rule normalizes (activity 1 Parsed). */
    Jv.Obj fallback(NativeRecord r, String contract, String ruleId, String recordType, String reportedTime, String nativeUid, String session, String why) {
        MapperSupport.Time t = timeFor(r, reportedTime);
        Jv.Obj ev = event("source_record", 1, r, t, contract, ruleId, reportedTime, nativeUid, true, null);
        if (session != null) {
            ev.put("session_uid", session);
        }
        ev.put("parse_status_id", 1).put("parse_status", "Parsed").put("record_type", recordType)
                .put("basis", why == null ? "no normalization rule for this record family; retained whole" : why).put("data", r.json());
        return ev;
    }

    /** The opaque fallback for a record that is not one JSON object (activity 2 Opaque), including an incomplete tail. */
    Jv.Obj opaque(NativeRecord r, String contract) {
        MapperSupport.Time t = timeFor(r, null);
        Jv.Obj ev = event("source_record", 2, r, t, contract, "common.opaque->source_record", null, null, true, null);
        ev.put("parse_status_id", r.status().id).put("parse_status", r.status().caption).put("basis", r.reason()).put("record_type", "opaque");
        if (r.text() == null) {
            ev.put("raw_bytes_b64", java.util.Base64.getEncoder().encodeToString(r.raw()));
        }
        else {
            ev.put("raw_data", r.text());
        }
        return ev;
    }
}
