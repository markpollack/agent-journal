package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Frozen caller acquisition, validated before opening the derived output. No transport or tool is executed. */
final class AcquiredExecution {
    private final Jv document;
    private final AssemblyProfile profile;
    private final List<Acquired> sources = new ArrayList<>();
    private boolean complete;

    @SuppressWarnings("ArrayRecordComponent") // private frozen byte buffer; never compared by record equality or exposed to callers
    private record Acquired(Jv source, byte[] bytes) {
    }

    private AcquiredExecution(Path manifest) throws IOException {
        document = Jv.parse(Files.readString(manifest));
        require("agent-journal/acquired-execution".equals(text(document, "kind")) && "0.1".equals(text(document, "version")), "unsupported acquisition format");
        require(document.member("complete") instanceof Jv.Bool, "complete must be explicit");
        require(document.member("process") instanceof Jv.Obj, "process outcome must be explicit");
        text(document, "acquisition_uid");
        validateRun(document.member("run"));
        profile = AssemblyProfile.bundled(text(document, "profile"));
        require(profile.id().equals(text(document, "profile_id")), "profile identity mismatch");
        complete = bool(document, "complete");
        Path base = manifest.toAbsolutePath().getParent().toRealPath();
        readSources(base);
        if (CodexExecFamily.admits(profile.id())) CodexOriginalQualification.validate(document, base);
    }

    private static void validateRun(Jv run) {
        require(run instanceof Jv.Obj, "run descriptor required");
        text(run, "run_uid");
        text(run, "capture_start");
        require(run.member("cli") instanceof Jv.Obj, "CLI product required");
        text(run.member("cli"), "name");
        text(run.member("cli"), "vendor_name");
    }

    private void readSources(Path base) throws IOException {
        Set<String> artifacts = new HashSet<>();
        require(document.member("sources") instanceof Jv.Arr, "sources must be an array");
        for (Jv source : document.member("sources").elements()) {
            require(artifacts.add(text(source, "artifact")), "duplicate artifact");
            require(source.member("required") instanceof Jv.Bool, "required must be explicit");
            if ("missing".equals(text(source, "state"))) {
                text(source, "reason");
                if (bool(source, "required")) { complete = false; }
            }
            else { acquire(base, source); }
        }
    }

    private void acquire(Path base, Jv source) throws IOException {
        require("acquired".equals(text(source, "state")), "unknown source state");
        text(source, "origin");
        text(source, "acquisition");
        String end = text(source, "end");
        require("EOF".equals(end) || "INTERRUPTED".equals(end), "unknown source end");
        if (!"EOF".equals(end)) { complete = false; }
        Path relative = Path.of(text(source, "path"));
        require(!relative.isAbsolute(), "source path must be relative");
        Path file = base.resolve(relative).toRealPath();
        require(file.startsWith(base), "source path escapes acquisition directory");
        byte[] bytes = Files.readAllBytes(file);
        require(source.member("bytes") instanceof Jv.Num n && n.value().longValueExact() == bytes.length, "source byte extent mismatch");
        require(SchemaPin.sha256(bytes).equals(text(source, "sha256")), "source hash mismatch");
        if (!bool(source, "archive_only")) { validateRole(source, bytes); }
        sources.add(new Acquired(source, bytes));
    }

    private void validateRole(Jv source, byte[] bytes) {
        AssemblyProfile.Role role = profile.role(text(source, "role"));
        require(role.profile().equals(text(source, "profile")) && role.route().equals(text(source, "route"))
                && role.framing().equals(text(source, "framing")), "source binding differs from profile role");
        int count = "document".equals(role.framing()) ? (bytes.length == 0 ? 0 : 1) : Replay.lines(bytes).size();
        require(source.member("records") instanceof Jv.Num n && n.value().intValueExact() == count, "source record extent mismatch");
    }

    static OcsfRunWriter.State assemble(Path manifest, Path out) {
        try {
            require(!Files.exists(out), "output directory exists");
            return new AcquiredExecution(manifest).write(out, null, WriterOptions.defaults());
        }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    private RunDescriptor run() {
        RunDescriptor original = RunDescriptor.fromSpec(document.member("run"));
        Jv.Obj settings = original.launchSettings() instanceof Jv.Obj s ? new Jv.Obj(s.members()) : new Jv.Obj();
        settings.put("acquired_execution", document).put("acquired_execution_basis", "caller-supplied immutable acquisition manifest; not a vendor assertion");
        return new RunDescriptor(original.runUid(), original.invocationIndex(), original.sessionUid(), original.captureStart(), original.cli(), settings, original.synthetic());
    }

    private AssemblyPlan plan() {
        List<AssemblyPlan.Binding> bindings = new ArrayList<>();
        for (Acquired input : sources) {
            Jv s = input.source();
            if (!bool(s, "archive_only")) { bindings.add(new AssemblyPlan.Binding(text(s, "artifact"), text(s, "role"), text(s, "origin"), text(s, "acquisition"), optional(s, "copy_of"))); }
        }
        return new AssemblyPlan(profile, bindings, new Jv.Arr());
    }

    static OcsfRunWriter.State assembleTerminal(Path manifest, Path out, Jv.Obj binding, WriterOptions options) throws IOException {
        return new AcquiredExecution(manifest).write(out, binding, options);
    }

    private OcsfRunWriter.State write(Path out, Jv.Obj binding, WriterOptions options) throws IOException {
        AssemblyPlan plan = plan();
        List<String> order = AssemblyProfile.strings(profile.document().member("feed_order"));
        sources.sort(java.util.Comparator.comparingInt(i -> order.indexOf(optional(i.source(), "role"))));
        try (OcsfRunWriter writer = binding == null ? OcsfRunWriter.openAcquiredAssembly(out, run(), SchemaPin.bundled(), options, plan)
                : OcsfRunWriter.openTerminalAssembly(out, run(), options, plan, binding)) {
            writer.processOutcome((Jv.Obj) document.member("process"));
            if (!complete) { writer.acquisitionIncomplete("caller acquisition incomplete; see launch_settings.acquired_execution"); }
            for (Acquired input : sources) {
                if (!bool(input.source(), "archive_only")) { feed(writer, options, input); }
            }
            return complete ? writer.complete() : writer.interrupt("caller acquisition incomplete; see launch_settings.acquired_execution");
        }
    }

    private void feed(OcsfRunWriter writer, WriterOptions options, Acquired input) {
        Jv s = input.source();
        AssemblyProfile.Role role = profile.role(text(s, "role"));
        String owner = s.member("child_binding") == null ? null : optional(s.member("child_binding"), "spawn_tool_call_uid");
        SourceInput target = writer.source(new SourceDescriptor(text(s, "artifact"), role.kind(), role.profile(), role.route(),
                "frozen acquisition from " + text(s, "origin"), text(s, "path"), role.framing(), owner));
        Replay.feedBytes(target, input.bytes(), 65536, options.clock(), input.bytes().length, false);
        target.end(SourceInput.End.valueOf(text(s, "end")), options.clock().instant());
    }

    private static boolean bool(Jv value, String member) { return value.member(member) instanceof Jv.Bool b && b.value(); }
    private static String optional(Jv value, String member) { return value.member(member) == null ? null : value.member(member).text(); }
    private static String text(Jv value, String member) {
        String result = optional(value, member);
        require(result != null && !result.isBlank(), "missing " + member);
        return result;
    }
    private static void require(boolean condition, String message) { if (!condition) { throw new IllegalArgumentException(message); } }
}
