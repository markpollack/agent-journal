package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;

import java.math.BigDecimal;
import java.util.List;

/**
 * One saved event line, read with the neutral JSON model. {@code className} is resolved through the package's pin
 * table and is {@code null} for a class the pin does not name; such an event is retained and reported, never
 * interpreted.
 */
public final class Event {

    public final int line;
    public final Json json;
    public final String className;
    public final Integer classUid;
    public final Integer activityId;
    public final Integer sequence;

    Event(int line, Json json, String className) {
        this.line = line;
        this.json = json;
        this.className = className;
        this.classUid = integer(json.member("class_uid"));
        this.activityId = integer(json.member("activity_id"));
        this.sequence = integer(json.path("journal", "sequence"));
    }

    static Integer integer(Json v) {
        return v instanceof Json.JNumber n && n.value().stripTrailingZeros().scale() <= 0 ? n.value().intValueExact() : null;
    }

    static BigDecimal number(Json v) {
        return v instanceof Json.JNumber n ? n.value() : null;
    }

    public Json get(String... path) {
        return json.path(path);
    }

    public String text(String... path) {
        Json v = json.path(path);
        return v == null ? null : v.text();
    }

    public Integer integer(String... path) {
        return integer(json.path(path));
    }

    public List<Json> elements(String... path) {
        Json v = json.path(path);
        return v == null ? List.of() : v.elements();
    }

    public boolean is(String className, int activityId) {
        return className.equals(this.className) && this.activityId != null && this.activityId == activityId;
    }

    public Json sourceRef() {
        return json.member("source_ref");
    }

    public String artifact() {
        return text("source_ref", "artifact");
    }

    public Integer recordIndex() {
        return integer("source_ref", "record_index");
    }

    public boolean wholeRecord() {
        return json.path("source_ref", "is_whole_record") instanceof Json.JBool b && b.value();
    }

    public String contractUid() {
        return text("journal", "contract_uid");
    }

    public String runUid() {
        return text("journal", "run_uid");
    }

    /**
     * The event's role in a finally assembled file ({@code assembly.assembly_role_id}): 1 canonical single observation,
     * 2 supporting observation (evidence, never counted again), 3 consolidated canonical; {@code null} outside one.
     */
    public Integer assemblyRole() {
        return integer("assembly", "assembly_role_id");
    }

    /** Whether a consumer counts this event: every event outside an assembled file, and roles 1 and 3 inside one. */
    public boolean canonical() {
        Integer role = assemblyRole();
        return role == null || role != 2;
    }

    /** Whether this event is one source's own observation (not an event an assembly consolidated from several). */
    public boolean observation() {
        Integer role = assemblyRole();
        return role == null || role != 3;
    }

    /** {@code <artifact>:<record_index>} for reports. */
    public String occurrence() {
        return artifact() + ":" + recordIndex();
    }

    @Override
    public String toString() {
        return "event line " + line + " " + className + "/" + activityId + " " + occurrence();
    }
}
