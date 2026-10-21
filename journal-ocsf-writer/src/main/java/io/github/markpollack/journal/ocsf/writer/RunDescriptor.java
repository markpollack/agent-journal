package io.github.markpollack.journal.ocsf.writer;

import java.time.Instant;

/**
 * What the capture knows about the run before any source record arrives: Journal run identity, invocation index,
 * the native session id when already known, the capture start, the CLI product, launch settings as reported by the
 * capture, and an optional synthetic label for labelled constructions (never present on a real capture).
 *
 * @param runUid the Journal run id
 * @param invocationIndex zero-based CLI invocation within the run
 * @param sessionUid the native session id, or {@code null} when not yet known
 * @param captureStart when the capture started (the earliest-available time fallback)
 * @param cli the CLI product: name, version, vendor_name, type_id, type
 * @param launchSettings launch settings as the capture reports them (free JSON object), or {@code null}
 * @param synthetic a label for a synthetic/labelled construction, or {@code null} for a real capture
 */
public record RunDescriptor(String runUid, int invocationIndex, String sessionUid, Instant captureStart, Cli cli, Jv launchSettings, Jv synthetic) {

    /** @param typeId the OCSF ai_agent type id (1 Native); {@code type} its caption */
    public record Cli(String name, String version, String vendorName, int typeId, String type) {
        public Jv.Obj product() {
            return new Jv.Obj().put("name", name).put("version", version).put("vendor_name", vendorName);
        }
    }

    static RunDescriptor fromSpec(Jv spec) {
        Jv cli = spec.member("cli");
        return new RunDescriptor(spec.member("run_uid").text(),
                spec.member("invocation_index") instanceof Jv.Num n ? (int) n.value().longValueExact() : 0,
                spec.member("session_uid") == null ? null : spec.member("session_uid").text(),
                Instant.parse(spec.member("capture_start").text()),
                new Cli(cli.member("name").text(), cli.member("version").text(), cli.member("vendor_name").text(),
                        cli.member("type_id") instanceof Jv.Num t ? (int) t.value().longValueExact() : 1,
                        cli.member("type") == null ? "Native" : cli.member("type").text()),
                spec.member("launch_settings"), spec.member("synthetic"));
    }
}
