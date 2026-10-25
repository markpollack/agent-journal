package io.github.markpollack.journal.ocsf.writer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Final Level 1 assembly: writes a new, independently portable {@code events.jsonl} from the declared sources of one
 * execution under a reviewed {@link AssemblyProfile}, through the one writer and its source mappers. Each source is fed
 * in the profile's feed order (the source that reports the version or the session identity first), framed as the profile
 * declares (lines, or one document), mapped by its own mapper, held, and at completion gated, grouped and consolidated
 * ({@link Assembler}). Original packages are only named in the lineage; nothing is written beside them, and the native
 * inputs are only read. The same mechanism serves every CLI: what differs is the profile.
 */
public final class FinalAssembly {

    /**
     * One acquired input.
     *
     * @param artifact the artifact name the final file declares
     * @param role the profile's source role it fills
     * @param file the acquired bytes (an independent copy, a snapshot of a native file)
     * @param origin where it was acquired, the source's import identity together with its sha256
     * @param acquisition the acquisition the caller asserts it belongs to, or {@code null} (see {@link AssemblyPlan.Binding})
     * @param copyOf the earlier input's artifact this one is a declared copy of, or {@code null}
     */
    public record Input(String artifact, String role, Path file, String origin, String acquisition, String copyOf) {

        public Input(String artifact, String role, Path file, String origin, String acquisition) {
            this(artifact, role, file, origin, acquisition, null);
        }
    }

    private FinalAssembly() {
    }

    /**
     * Assembles the inputs into {@code out} and finalizes it; a required role without an input is declared missing and
     * makes the assembly incomplete, never silently complete.
     *
     * @param lineage the original packages of the same acquisition ({@code [{path, events_jsonl_sha256, state}]}), or an empty array
     */
    public static OcsfRunWriter.State assemble(Path out, RunDescriptor run, AssemblyProfile profile, List<Input> inputs, Jv lineage, WriterOptions options) {
        List<AssemblyPlan.Binding> bindings = new ArrayList<>();
        inputs.forEach(i -> bindings.add(new AssemblyPlan.Binding(i.artifact(), i.role(), i.origin(), i.acquisition(), i.copyOf())));
        AssemblyPlan plan = new AssemblyPlan(profile, bindings, lineage == null ? new Jv.Arr() : lineage);
        List<String> order = AssemblyProfile.strings(profile.document().member("feed_order"));
        List<Input> fed = new ArrayList<>(inputs);
        fed.sort(Comparator.comparingInt(i -> order.contains(i.role()) ? order.indexOf(i.role()) : order.size()));
        try (OcsfRunWriter w = OcsfRunWriter.openAssembly(out, run, SchemaPin.bundled(), options, plan)) {
            for (Input i : fed) {
                AssemblyProfile.Role r = profile.role(i.role());
                SourceDescriptor d = new SourceDescriptor(i.artifact(), r.kind(), r.profile(), r.route(),
                        "final assembly input for role " + r.role() + " of profile " + profile.id() + ", acquired from " + i.origin(), null, r.framing());
                Replay.feedBytes(w.source(d), i.file(), 65536, options.clock());
            }
            return w.complete();
        }
    }

    /** The lineage entry of an original package: its location, its final events.jsonl hash and its state. */
    public static Jv.Obj lineage(Path packageDir, String label) {
        Path events = packageDir.resolve("events.jsonl");
        Jv.Obj e = new Jv.Obj().put("package", label);
        if (Files.isRegularFile(events)) {
            e.put("events_jsonl_sha256", SchemaPin.sha256(Replay.read(events)));
        }
        Path manifest = packageDir.resolve("run.json");
        if (Files.isRegularFile(manifest)) {
            Jv m = Jv.parse(new String(Replay.read(manifest), java.nio.charset.StandardCharsets.UTF_8));
            e.put("state", m.path("package", "state"));
        }
        return e.put("relation", "the original package of the same acquisition; unchanged by this assembly");
    }
}
