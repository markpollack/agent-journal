package io.github.markpollack.journal.tck.ocsf.inventory;

import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.Reconstruction;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent native ownership, exact content and canonical association for qualified persisted tool outputs. */
final class ToolOutputVerification {
    private ToolOutputVerification() { }

    static void check(OcsfPackage pkg, Json manifest, Path base, OracleReport report) throws IOException {
        Map<String, Json> bindings = ClaudeToolOutput.bindings(manifest, base);
        for (Json source : manifest.member("sources").elements()) {
            if (!Set.of("child_transcript", "sidecar").contains(RawInventory.text(source, "role"))
                    || !"acquired".equals(RawInventory.text(source, "state"))) { continue; }
            String artifact = RawInventory.text(source, "artifact");
            Json expected = bindings.get(artifact);
            if (expected == null) {
                report.add(Check.Category.COMPLETENESS, "acquisition.additional_native", artifact, Check.Outcome.UNRESOLVED,
                        "preserved input has no uniquely established native persisted-tool-output binding; child/other sidecar obligations remain unqualified");
            }
            else { compare(pkg, source, base, expected, report); }
        }
        inventory(manifest, base, report);
    }

    private static void inventory(Json manifest, Path base, OracleReport report) {
        for (String origin : ClaudeToolOutput.references(manifest, base)) {
            long count = manifest.member("sources").elements().stream().filter(s -> "sidecar".equals(RawInventory.text(s, "role"))
                    && "acquired".equals(RawInventory.text(s, "state")) && origin.equals(RawInventory.text(s, "origin"))).count();
            report.add(Check.Category.COMPLETENESS, "acquisition.tool_output_inventory", origin,
                    count == 1 ? Check.Outcome.VERIFIED : Check.Outcome.UNRESOLVED,
                    "each native persistedOutputPath owes exactly one acquired sidecar, observed " + count);
        }
    }

    private static void compare(OcsfPackage pkg, Json source, Path base, Json expected, OracleReport report) throws IOException {
        String artifact = RawInventory.text(source, "artifact");
        List<Event> content = pkg.canonical().stream().filter(e -> artifact.equals(e.artifact())).toList();
        Json recorded = recordedSource(pkg, artifact);
        boolean binding = recorded != null && Json.sameValue(expected, recorded.member("tool_result_binding"));
        check(report, "acquisition.tool_output_binding", artifact, binding, "native request UUID, result record, session/run and tool identity match the recorded ownership binding");
        byte[] bytes = Files.readAllBytes(base.resolve(RawInventory.text(source, "path")));
        boolean preserved = content.size() == 1 && Arrays.equals(bytes, Reconstruction.reconstruct(content, 1).bytes());
        check(report, "acquisition.tool_output_bytes", artifact, preserved, "full native sidecar compared byte-for-byte with the canonical whole document, separately from the preview and stdout prefix");
        boolean association = association(pkg, expected, 1, "request_ref") && association(pkg, expected, 2, "result_ref");
        check(report, "acquisition.tool_output_association", artifact, association, "exact native request/result locators resolve to one canonical request and one canonical result of this run/session/call");
        check(report, "acquisition.additional_native", artifact, binding && preserved && association,
                "qualified persisted output obligations: exact ownership, full bytes and unique canonical association; no new tool invocation inferred");
    }

    private static boolean association(OcsfPackage pkg, Json binding, int activity, String ref) {
        List<Event> events = pkg.canonical().stream().filter(e -> "tool_call".equals(e.className)
                && Integer.valueOf(activity).equals(e.activityId) && Json.sameValue(e.get("tool_call_uid"), binding.member("tool_call_uid"))
                && Json.sameValue(e.get("session_uid"), binding.member("session_uid"))).toList();
        if (events.size() != 1 || !pkg.manifest.runUid().equals(RawInventory.text(binding, "run_uid"))) { return false; }
        Event event = events.get(0);
        Json locator = binding.member(ref);
        if (located(event.json.member("source_ref"), locator)) { return true; }
        return event.elements("assembly", "observation_refs").stream().anyMatch(r -> located(r, locator));
    }

    private static boolean located(Json ref, Json expected) {
        return ref != null && Json.sameValue(ref.member("artifact"), expected.member("artifact"))
                && Json.sameValue(ref.member("record_index"), expected.member("record_index"));
    }

    private static Json recordedSource(OcsfPackage pkg, String artifact) {
        Json recorded = pkg.declarations.run().path("launch_settings", "acquired_execution", "sources");
        if (recorded == null) { return null; }
        List<Json> matches = recorded.elements().stream().filter(s -> artifact.equals(RawInventory.text(s, "artifact"))).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void check(OracleReport report, String property, String owner, boolean pass, String detail) {
        report.add(Check.Category.COMPLETENESS, property, owner, pass ? Check.Outcome.VERIFIED : Check.Outcome.DEFECTIVE, detail);
    }
}
