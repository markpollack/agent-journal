package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A committed fixture's evidence class, from its own provenance and nothing else. The origin says what the native bytes
 * are: a sanitized recording of a live run through Journal's capture (its frozen manifest records the live process and
 * a provenance manifest names the capture), a sanitized real stream recorded elsewhere, or labelled synthetic input. A
 * test run over any committed fixture is a replay; only the live attempt behind a live recording was live execution. The
 * classes are exclusive, and an artifact claiming two is inconsistent.
 */
final class FixtureEvidenceClass {

    enum Origin {
        /** a sanitized recording of a live run through the capture; its live attempt was live execution, its tests replay it */
        LIVE_RECORDING("live"),
        /** a sanitized real stream not recorded by a live run of this capture; tests replay it */
        RECORDED_STREAM("replay"),
        /** labelled synthetic input, authored rather than recorded */
        SYNTHETIC("synthetic");

        /** the live-scenario attempt vocabulary (results.json evidence_class) */
        final String attemptClass;

        Origin(String attemptClass) {
            this.attemptClass = attemptClass;
        }
    }

    private FixtureEvidenceClass() {
    }

    /** The origin the fixture's spec label states, or {@code null} when the label states none of the three. */
    static Origin origin(Path dir) {
        String label = label(dir);
        if (label == null) {
            return null;
        }
        if (label.startsWith("SANITIZED REAL LIVE RECORDING")) {
            return Origin.LIVE_RECORDING;
        }
        if (label.startsWith("SANITIZED REAL")) {
            return Origin.RECORDED_STREAM;
        }
        return label.contains("SYNTHETIC") ? Origin.SYNTHETIC : null;
    }

    /** Everything that contradicts the stated origin: a live recording without its live process or provenance, or another class carrying them. */
    static List<String> problems(Path dir) {
        List<String> problems = new ArrayList<>();
        String name = dir.getFileName().toString();
        Origin origin = origin(dir);
        if (origin == null) {
            problems.add(name + ": spec.json synthetic.label states no evidence class: " + label(dir));
            return problems;
        }
        boolean liveProcess = Files.exists(dir.resolve("native/expected-sources.json"))
                && NeutralJson.readDocument(dir.resolve("native/expected-sources.json")).path("frozen", "live_process") != null;
        Path provenance = dir.resolve("provenance.json");
        String provenanceOrigin = Files.exists(provenance) ? SchemaCatalog.text(NeutralJson.readDocument(provenance), "origin") : null;
        boolean liveProvenance = provenanceOrigin != null && provenanceOrigin.startsWith("SANITIZED REAL LIVE RECORDING");
        if (origin == Origin.LIVE_RECORDING && !(liveProcess && liveProvenance)) {
            problems.add(name + ": labelled a live recording without " + (liveProcess ? "" : "the frozen live process ") + (liveProvenance ? "" : "a live provenance manifest"));
        }
        if (origin != Origin.LIVE_RECORDING && (liveProcess || liveProvenance)) {
            problems.add(name + ": labelled " + origin + " but carries " + (liveProcess ? "a frozen live process " : "") + (liveProvenance ? "a live provenance manifest" : ""));
        }
        return problems;
    }

    private static String label(Path dir) {
        Path spec = dir.resolve("spec.json");
        if (!Files.exists(spec)) {
            return null;
        }
        Json synthetic = NeutralJson.readDocument(spec).member("synthetic");
        return synthetic == null ? null : SchemaCatalog.text(synthetic, "label");
    }
}
