package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The incremental Java writer of the OCSF-based run package (layout {@code 0.3.0-prototype}, NON-PRODUCTION). One
 * writer records one run: it opens the root core, accepts declared sources through {@link SourceInput} (the same
 * entry point for a replay driver and for a live capture), maps each native record as it arrives into OCSF
 * events or conservative fallback, routes a record's events to the root core or a linked child core by the owner
 * the mapper names, and keeps the persisted package readable before the input ends.
 *
 * <p>Each core's {@code events.jsonl} is independently portable: it opens with its {@code format}, {@code schema}
 * and {@code run} declarations, declares every contract, source, definition and session before the first event that
 * references it, records linked children, each source's end and its own finalization as further declarations, and
 * never rewrites a line ({@link Declarations}). {@code run.json} beside it is an ancillary index and summary (final
 * hash, counts, failure detail, extension source, semantic contract, mapping audit), never an interpretation
 * dependency.
 *
 * <p>Lifecycle: {@code RECORDING} from {@link #open} until {@link #complete()} ({@code COMPLETED}, requires every
 * declared source to have ended), {@link #interrupt} ({@code INTERRUPTED}, ends open sources as interrupted) or a
 * persistence failure ({@code FAILED}, the persisted boundary is recorded). Finalization is idempotent: a second
 * call returns the state already reached. Sources admitted after the live output ended (a saved session file) are
 * appended after the events already persisted; nothing written earlier changes.
 */
public final class OcsfRunWriter implements AutoCloseable, CoreWriter.Context {

    public enum State {
        RECORDING, COMPLETED, INTERRUPTED, FAILED
    }

    private final Path dir;
    private final RunDescriptor run;
    private final SchemaPin pin;
    private final WriterOptions options;
    private final MappingContracts contracts = new MappingContracts();
    private final Compatibility compatibility = Compatibility.bundled();
    private final CoreWriter root;
    private final Map<String, CoreWriter> children = new LinkedHashMap<>();
    private final Map<String, Source> sources = new LinkedHashMap<>();
    private final Instant started;
    private Instant lastFlush;
    private Instant ended;
    private State state = State.RECORDING;
    private String interruptionReason;
    private Jv.Obj failure;
    private int recordsSinceFlush;
    private final Map<String, Jv> extensionSource = new TreeMap<>();
    private final String extensionSourceSha256;
    private final Jv semanticContract;
    private String sessionUid;
    private String reportedCliVersion;
    private Jv.Obj process;
    private Jv.Obj archive;

    private OcsfRunWriter(Path dir, RunDescriptor run, SchemaPin pin, WriterOptions options) throws IOException {
        this.dir = dir;
        this.run = run;
        this.pin = pin;
        this.options = options;
        this.sessionUid = run.sessionUid();
        this.started = options.clock().instant();
        this.extensionSourceSha256 = SchemaPin.extensionSource(extensionSource);
        this.semanticContract = loadSemanticContract();
        this.root = new CoreWriter(dir, run.runUid(), this, runDeclaration(run.runUid(), null, null));
        root.flush(c -> manifest(c, null, null, List.of()));
        this.lastFlush = started;
    }

    public static OcsfRunWriter open(Path dir, RunDescriptor run, SchemaPin pin, WriterOptions options) {
        try {
            return new OcsfRunWriter(dir, run, pin, options);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path dir() {
        return dir;
    }

    public State state() {
        return state;
    }

    public RunDescriptor run() {
        return run;
    }

    /** The native session id as known so far (declared at launch or reported by the selected source), or {@code null}. */
    public String sessionUid() {
        return sessionUid;
    }

    /** The CLI version as known so far (declared at launch or reported by the selected source), or {@code null}. */
    public String knownCliVersion() {
        return cliVersion();
    }

    /**
     * The outcome of the CLI process a capture launched (how it ended, exit code, cancellation reason), recorded in
     * the {@code recording} declaration of every file and in the manifest. Storage finalization, acquisition extent
     * and process outcome stay distinct: a completely acquired stream of a CLI that exited non-zero is COMPLETED with
     * a failed process, not a truncated acquisition. Set before finalization; ignored afterwards.
     */
    public void processOutcome(Jv.Obj outcome) {
        if (state == State.RECORDING) {
            this.process = outcome;
        }
    }

    /**
     * The native session file archived unchanged beside the package (artifact, bytes, sha256, how it was located),
     * or why it could not be; recorded in the ancillary manifest only, outside the selected input and never read by
     * the writer. Set before finalization; ignored afterwards.
     */
    public void archive(Jv.Obj archive) {
        if (state == State.RECORDING) {
            this.archive = archive;
        }
    }

    // ------------------------------------------------------------------------------------------ declaration context

    @Override
    public SchemaPin pin() {
        return pin;
    }

    @Override
    public String extensionSourceSha256() {
        return extensionSourceSha256;
    }

    @Override
    public WriterOptions options() {
        return options;
    }

    @Override
    public String startedText() {
        return started.toString();
    }

    @Override
    public void cliVersionReported(String version) {
        if (reportedCliVersion == null) {
            reportedCliVersion = version;
            for (Source s : sources.values()) {
                s.reselect();
            }
        }
    }

    @Override
    public List<Jv.Obj> compatibilityDeclarations(String artifact) {
        Source s = sources.get(artifact);
        return s == null ? List.of() : List.copyOf(s.compatibilityDeclared);
    }

    /** The CLI version as known: declared at launch, else the first stream-reported one, else {@code null}. */
    private String cliVersion() {
        return run.cli().version() != null ? run.cli().version() : reportedCliVersion;
    }

    private String cliVersionBasis() {
        return run.cli().version() != null ? run.cli().declaredVersionBasis() : reportedCliVersion != null
                ? "reported by the selected source (cli_version declaration); not known at launch, no version command was run"
                : "not known at launch and not (yet) reported by the selected stream; no version command was run";
    }

    @Override
    public Jv.Obj contractDeclaration(String contractUid) {
        return contracts.declaration(contractUid);
    }

    @Override
    public Jv.Obj sourceDeclaration(String artifact) {
        Source s = sources.get(artifact);
        if (s == null) {
            return null;
        }
        Jv.Obj d = new Jv.Obj().put("artifact", s.descriptor.artifact()).put("kind", s.descriptor.kind()).put("profile", s.descriptor.profile())
                .put("route", s.descriptor.route() == null ? "unspecified" : s.descriptor.route()).put("surface", s.descriptor.surface());
        if (s.descriptor.pathAtCapture() != null) {
            d.put("path_at_capture", s.descriptor.pathAtCapture());
        }
        return d.put("contract_uid", contracts.uid(s.mapper.contractName()));
    }

    /** The {@code run} declaration of a core: identity, product, launch settings, parent/scope for a child, producer and capture time. */
    private Jv.Obj runDeclaration(String coreRunUid, Jv parent, Jv sourceScope) {
        Jv.Obj r = new Jv.Obj().put("run_uid", coreRunUid).put("invocation_index", run.invocationIndex()).put("capture_start", run.captureStart().toString());
        r.put("cli", new Jv.Obj().put("name", run.cli().name()).put("version", run.cli().version()).put("vendor_name", run.cli().vendorName())
                .put("type_id", run.cli().typeId()).put("type", run.cli().type()).put("product", run.cli().product())
                .put("version_basis", run.cli().version() != null ? run.cli().declaredVersionBasis() : "not known at launch; a cli_version declaration follows if the selected stream reports it; no version command is run"));
        if (run.launchSettings() != null) {
            r.put("launch_settings", run.launchSettings());
        }
        if (run.synthetic() != null && !run.synthetic().isNull()) {
            r.put("synthetic", run.synthetic());
        }
        if (parent != null) {
            r.put("parent", parent);
        }
        if (sourceScope != null) {
            r.put("source_scope", sourceScope);
        }
        r.put("producer", new Jv.Obj().put("name", "agent-journal").put("version", options.producerVersion()).put("vendor_name", "Agent Journal"));
        r.put("written_by", new Jv.Obj().put("name", Pins.WRITER_NAME).put("version", Pins.PACKAGE_VERSION)
                .put("mode", "incremental: declarations and events appended as native records arrived; no batch conversion or re-layout"));
        r.put("capture_time", started.toEpochMilli()).put("capture_time_text", started.toString()).put("started", started.toString());
        return r;
    }

    // ------------------------------------------------------------------------------------------ sources

    /** Declares a source and returns its input; a source may be declared at any time while recording. */
    public SourceInput source(SourceDescriptor descriptor) {
        requireRecording();
        if (sources.containsKey(descriptor.artifact())) {
            throw new IllegalArgumentException("source " + descriptor.artifact() + " is already declared");
        }
        Source s = new Source(descriptor);
        sources.put(descriptor.artifact(), s);
        root.declareSource(descriptor.artifact());
        return s;
    }

    private void requireRecording() {
        if (state == State.FAILED) {
            throw new CoreWriter.WriterFailedException("writer failed: " + failure.get("detail").text());
        }
        if (state != State.RECORDING) {
            throw new IllegalStateException("writer is " + state + "; no more input is accepted");
        }
    }

    /** One declared source: framing, classification, hashing and the mapper that interprets it. */
    private final class Source implements SourceInput, SourceContext.Sink {
        final SourceDescriptor descriptor;
        final SourceMapper mapper;
        final SourceContext ctx;
        final LineFramer framer = new LineFramer();
        final MessageDigest digest;
        String stateName = "PENDING";
        int records;
        int opaque;
        long bytes;
        boolean lastByteNewline;
        boolean mixedInput;
        boolean byteMode;
        Integer incompleteTail;
        SourceInput.End endedHow;
        Instant endedAt;
        Instant firstArrival;
        Instant lastArrival;
        boolean unterminatedSeen;
        Compatibility.Selection selection;
        final List<Jv.Obj> compatibilityDeclared = new ArrayList<>();

        Source(SourceDescriptor descriptor) {
            this.descriptor = descriptor;
            this.mapper = SourceMapper.forProfile(descriptor.profile());
            this.ctx = new SourceContext(descriptor, run, contracts, pin, this);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            }
            catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            this.selection = compatibility.select(run.cli().name(), descriptor.profile(), descriptor.route(), cliVersion());
            compatibilityDeclared.add(Compatibility.declaration(selection, descriptor.artifact(), cliVersionBasis(), 0, pin.compiledSha256()));
        }

        /** Repeats the selection once the version is known; the new selection is appended to every file that references this source. */
        void reselect() {
            if (selection.status() != Compatibility.Status.VERSION_UNKNOWN || cliVersion() == null) {
                return;
            }
            selection = compatibility.select(run.cli().name(), descriptor.profile(), descriptor.route(), cliVersion());
            Jv.Obj d = Compatibility.declaration(selection, descriptor.artifact(), cliVersionBasis(), records, pin.compiledSha256());
            compatibilityDeclared.add(d);
            int n = compatibilityDeclared.size();
            root.declareCompatibility(descriptor.artifact(), n, d);
            for (CoreWriter c : children.values()) {
                c.declareCompatibility(descriptor.artifact(), n, d);
            }
        }

        /** The latest compatibility declaration of this source (the one in force). */
        Jv.Obj compatibilityInForce() {
            return compatibilityDeclared.get(compatibilityDeclared.size() - 1);
        }

        @Override
        public void bytes(byte[] chunk, int offset, int length, Instant arrival) {
            requireOpen(arrival);
            if (!byteMode && records > 0) {
                mixedInput = true;
                throw new IllegalStateException("source " + descriptor.artifact() + " received complete records; byte chunks cannot be mixed in");
            }
            byteMode = true;
            digest.update(chunk, offset, length);
            bytes += length;
            if (length > 0) {
                lastByteNewline = chunk[offset + length - 1] == '\n';
            }
            for (LineFramer.Framed f : framer.push(chunk, offset, length)) {
                accept(NativeRecord.classify(++records, f.raw(), f.ending(), arrival));
            }
        }

        @Override
        public void record(byte[] raw, String ending, Instant arrival) {
            requireOpen(arrival);
            if (byteMode) {
                mixedInput = true;
                throw new IllegalStateException("source " + descriptor.artifact() + " receives byte chunks; complete records cannot be mixed in");
            }
            if (unterminatedSeen) {
                throw new IllegalStateException("source " + descriptor.artifact() + " already received its unterminated last record");
            }
            byte[] terminator = switch (ending) {
                case "LF" -> new byte[] {'\n'};
                case "CRLF" -> new byte[] {'\r', '\n'};
                case "none" -> new byte[0];
                default -> throw new IllegalArgumentException("line ending must be LF, CRLF or none: " + ending);
            };
            digest.update(raw);
            digest.update(terminator);
            bytes += raw.length + terminator.length;
            lastByteNewline = terminator.length > 0;
            unterminatedSeen = terminator.length == 0;
            accept(NativeRecord.classify(++records, raw, ending, arrival));
        }

        private void requireOpen(Instant arrival) {
            requireRecording();
            if (endedHow != null) {
                throw new IllegalStateException("source " + descriptor.artifact() + " has ended (" + endedHow + ")");
            }
            stateName = "RECORDING";
            if (firstArrival == null) {
                firstArrival = arrival;
            }
            lastArrival = arrival;
        }

        private void accept(NativeRecord r) {
            if (!r.parsed()) {
                opaque++;
                if (r.status() == NativeRecord.Status.INCOMPLETE) {
                    incompleteTail = r.index();
                }
            }
            try {
                if (!selection.normalizes()) {
                    retainUntested(r);
                }
                else {
                    mapper.map(r, ctx);
                }
            }
            catch (RuntimeException e) {
                // a mapper defect must not lose the record: retain it whole under the common opaque/fallback rules
                if (r.parsed()) {
                    ctx.family(r.json().member("type") == null ? "(untyped)" : String.valueOf(r.json().member("type").text()), r.raw().length,
                            "retained whole after a mapper exception: " + e);
                    emit(null, ctx.fallback(r, mapper.contractName(), "common.mapper_exception->source_record", "mapper_exception", null, null, sessionUid,
                            "mapper threw " + e.getClass().getName() + ": " + e.getMessage() + "; retained whole"));
                }
                else {
                    emit(null, ctx.opaque(r, mapper.contractName()));
                }
            }
            recordsSinceFlush++;
            if (options.autoFlushEveryRecords() > 0 && recordsSinceFlush >= options.autoFlushEveryRecords()) {
                flush();
            }
        }

        /**
         * RULE-33: the version, profile or route matched no compatibility entry, so the record is retained whole under
         * the untested-compatibility contract and nothing is normalized; the nearest entry's mapper is not consulted.
         */
        private void retainUntested(NativeRecord r) {
            String family;
            if (r.parsed()) {
                Jv o = r.json();
                String type = MapperSupport.text(o, "type");
                String sub = MapperSupport.text(o, "subtype");
                family = type == null ? "(untyped)" : sub == null ? type : type + "/" + sub;
                ctx.family(family, r.raw().length, "retained-only: untested compatibility (" + selection.status().name().toLowerCase() + "); not normalized");
                emit(null, ctx.fallback(r, Compatibility.UNTESTED_CONTRACT, "compat.no_match->source_record", family, null, null, sessionUid,
                        "retained whole: " + selection.detail()));
            }
            else {
                ctx.family("opaque", r.raw().length, "retained-only: not one JSON object (bytes kept verbatim); untested compatibility");
                emit(null, ctx.opaque(r, Compatibility.UNTESTED_CONTRACT));
            }
        }

        @Override
        public void end(End how, Instant at) {
            requireRecording();
            if (endedHow != null) {
                return; // idempotent
            }
            if (byteMode) {
                byte[] tail = framer.tail();
                if (tail.length > 0) {
                    LineFramer.Framed f = framer.end();
                    accept(NativeRecord.classify(++records, f.raw(), f.ending(), at));
                }
            }
            endedHow = how;
            endedAt = at;
            stateName = how == End.EOF ? "ENDED" : "INTERRUPTED";
            mapper.end(how, ctx);
            declareEnd();
            flush();
        }

        /** Appends this source's {@code source_end} declaration to every core that declared the source. */
        void declareEnd() {
            Jv.Obj end = endDeclaration();
            root.declareSourceEnd(descriptor.artifact(), end);
            for (CoreWriter c : children.values()) {
                c.declareSourceEnd(descriptor.artifact(), end);
            }
        }

        Jv.Obj endDeclaration() {
            Jv.Obj e = new Jv.Obj().put("artifact", descriptor.artifact()).put("how", endedHow.name()).put("at", endedAt.toString())
                    .put("records", records).put("bytes", bytes).put("sha256", sha256()).put("opaque_records", opaque).put("trailing_newline", lastByteNewline);
            if (incompleteTail != null) {
                e.put("incomplete_tail_record", incompleteTail);
            }
            if (firstArrival != null) {
                e.put("first_arrival", firstArrival.toString()).put("last_arrival", lastArrival.toString());
            }
            e.put("family_inventory", familyInventory());
            return e;
        }

        Jv.Obj familyInventory() {
            Jv.Obj fam = new Jv.Obj();
            for (Map.Entry<String, SourceContext.Family> f : ctx.families.entrySet()) {
                fam.put(f.getKey(), new Jv.Obj().put("records", f.getValue().records).put("bytes", f.getValue().bytes).put("disposition", f.getValue().disposition));
            }
            return fam;
        }

        @Override
        public int records() {
            return records;
        }

        @Override
        public void emit(String owner, Jv.Obj event) {
            if (event.get("session_uid") instanceof Jv.Str s && sessionUid == null) {
                sessionUid = s.value();
            }
            if (owner == null) {
                root.append(event);
            }
            else {
                childFor(owner).append(event);
            }
        }

        String sha256() {
            try {
                return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
            }
            catch (CloneNotSupportedException e) {
                throw new IllegalStateException(e);
            }
        }

        Jv manifestEntry() {
            boolean ended = endedHow != null;
            Jv.Obj s = new Jv.Obj().put("artifact", descriptor.artifact()).put("kind", descriptor.kind()).put("profile", descriptor.profile())
                    .put("route", descriptor.route() == null ? "unspecified" : descriptor.route()).put("surface", descriptor.surface());
            if (descriptor.pathAtCapture() != null) {
                s.put("path_at_capture", descriptor.pathAtCapture());
            }
            s.put("contract_uid", contracts.uid(mapper.contractName())).put("compatibility", compatibilityInForce()).put("state", stateName).put("final", ended)
                    .put("records", records).put("bytes", bytes).put("opaque_records", opaque).put("trailing_newline", lastByteNewline);
            if (ended) {
                s.put("sha256", sha256());
                s.put("ended", new Jv.Obj().put("how", endedHow.name()).put("at", endedAt.toString()));
            }
            else {
                s.put("note", "recording: records, bytes and opaque_records are counts so far; sha256 is present only when the source has ended");
            }
            if (incompleteTail != null) {
                s.put("incomplete_tail_record", incompleteTail);
            }
            if (firstArrival != null) {
                s.put("first_arrival", firstArrival.toString()).put("last_arrival", lastArrival.toString());
            }
            s.put("family_inventory", familyInventory());
            return s;
        }
    }

    private CoreWriter childFor(String owner) {
        return children.computeIfAbsent(owner, o -> {
            try {
                String path = "children/" + o.replaceAll("[^A-Za-z0-9_.-]", "_");
                String childRunUid = run.runUid() + "/child/" + o;
                Jv.Obj parent = new Jv.Obj().put("run_uid", run.runUid()).put("spawn_tool_call_uid", o).put("invocation_index", run.invocationIndex());
                CoreWriter c = new CoreWriter(dir.resolve(path), childRunUid, this, runDeclaration(childRunUid, parent, scope(o)));
                root.declareChild(childRunUid, path, o, CHILD_BASIS, scope(null));
                return c;
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static final String CHILD_BASIS = "records whose owner path (parent_tool_use_id) equals the spawning tool call id, routed by the mapper as they arrived (labelled split; not live child support)";

    /** The owner scope of a core: {@code ownerValue} null for the root (records without an owner). */
    private Jv.Obj scope(String ownerValue) {
        return new Jv.Obj().put("artifact", ownerArtifact()).put("owner_path", "parent_tool_use_id").put("owner_value", ownerValue == null ? Jv.NULL : new Jv.Str(ownerValue))
                .put("basis", "this core owns the records of the artifact whose owner_path member equals owner_value (null: records without one)");
    }

    // ------------------------------------------------------------------------------------------ lifecycle

    /** Persists buffered events of every core and replaces every manifest; readable by another process afterwards. */
    public void flush() {
        if (state == State.FAILED) {
            throw new CoreWriter.WriterFailedException("writer failed: " + failure.get("detail").text());
        }
        if (state != State.RECORDING) {
            return;
        }
        try {
            lastFlush = options.clock().instant();
            for (Map.Entry<String, CoreWriter> c : children.entrySet()) {
                c.getValue().flush(cw -> childManifest(cw, c.getKey()));
            }
            root.flush(c -> manifest(c, null, null, childLinks()));
            recordsSinceFlush = 0;
        }
        catch (IOException e) {
            fail("flush", e);
            throw new UncheckedIOException(e);
        }
    }

    /** Normal completion: every declared source must have ended. Idempotent once finalized. */
    public State complete() {
        if (state != State.RECORDING) {
            return state;
        }
        for (Source s : sources.values()) {
            if (s.endedHow == null) {
                throw new IllegalStateException("source " + s.descriptor.artifact() + " has not ended; end it or interrupt the writer");
            }
        }
        finish(State.COMPLETED, null);
        return state;
    }

    /** Interruption: open sources end as interrupted, the package is finalized as INTERRUPTED. Idempotent once finalized. */
    public State interrupt(String reason) {
        if (state != State.RECORDING) {
            return state;
        }
        Instant now = options.clock().instant();
        for (Source s : sources.values()) {
            if (s.endedHow == null) {
                if (s.byteMode && s.framer.tail().length > 0) {
                    LineFramer.Framed f = s.framer.end();
                    s.accept(NativeRecord.classify(++s.records, f.raw(), f.ending(), now));
                }
                s.endedHow = SourceInput.End.INTERRUPTED;
                s.endedAt = now;
                s.stateName = "INTERRUPTED";
                s.mapper.end(SourceInput.End.INTERRUPTED, s.ctx);
                s.declareEnd();
            }
        }
        finish(State.INTERRUPTED, reason);
        return state;
    }

    private void finish(State outcome, String reason) {
        try {
            ended = options.clock().instant();
            state = outcome;
            interruptionReason = reason;
            Jv acquisition = acquisition();
            for (CoreWriter c : children.values()) {
                c.declareRecording(outcome.name(), started.toString(), ended.toString(), reason, acquisition, process);
            }
            root.declareRecording(outcome.name(), started.toString(), ended.toString(), reason, acquisition, process);
            for (Map.Entry<String, CoreWriter> c : children.entrySet()) {
                c.getValue().finish(cw -> childManifest(cw, c.getKey()));
            }
            root.finish(c -> manifest(c, null, null, childLinks()));
        }
        catch (IOException e) {
            state = State.RECORDING; // fail() records the real outcome
            fail("finish", e);
            throw new UncheckedIOException(e);
        }
    }

    private void fail(String operation, IOException cause) {
        if (state == State.FAILED) {
            return;
        }
        state = State.FAILED;
        ended = options.clock().instant();
        root.fail(operation, cause);
        for (CoreWriter c : children.values()) {
            c.fail(operation, cause);
        }
        failure = new Jv.Obj().put("operation", operation).put("exception", cause.getClass().getName())
                .put("detail", cause.getMessage() == null ? cause.toString() : cause.getMessage()).put("at", ended.toString());
        for (Source s : sources.values()) {
            if (s.endedHow == null) {
                s.endedHow = SourceInput.End.INTERRUPTED;
                s.endedAt = ended;
                s.stateName = "INTERRUPTED";
            }
        }
        try {
            for (Map.Entry<String, CoreWriter> c : children.entrySet()) {
                c.getValue().writeFailureManifest(cw -> childManifest(cw, c.getKey()));
            }
            root.writeFailureManifest(c -> manifest(c, null, null, childLinks()));
        }
        catch (IOException e) {
            failure.put("manifest_write_failed", e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    /** Closing without completion is an interruption; a finalized writer closes without change. */
    @Override
    public void close() {
        if (state == State.RECORDING) {
            interrupt("writer closed without complete()");
        }
    }

    public Jv.Obj failure() {
        return failure;
    }

    // ------------------------------------------------------------------------------------------ manifest

    private List<Jv> childLinks() {
        List<Jv> links = new ArrayList<>();
        for (Map.Entry<String, CoreWriter> c : children.entrySet()) {
            links.add(new Jv.Obj().put("run_uid", c.getValue().runUid).put("path", dir.relativize(c.getValue().dir).toString().replace('\\', '/'))
                    .put("spawn_tool_call_uid", c.getKey()).put("basis", CHILD_BASIS));
        }
        return links;
    }

    private Jv childManifest(CoreWriter core, String owner) {
        Jv.Obj parent = new Jv.Obj().put("run_uid", run.runUid()).put("spawn_tool_call_uid", owner).put("invocation_index", run.invocationIndex());
        return manifest(core, parent, scope(owner), List.of());
    }

    private String ownerArtifact() {
        for (Source s : sources.values()) {
            if ("claude_stream".equals(s.descriptor.profile())) {
                return s.descriptor.artifact();
            }
        }
        return null;
    }

    private Jv manifest(CoreWriter core, Jv parent, Jv sourceScope, List<Jv> childLinks) {
        boolean isFinal = core.finalized();
        Jv.Obj m = new Jv.Obj();
        Jv.Obj pkg = new Jv.Obj();
        pkg.put("kind", Pins.PACKAGE_KIND).put("version", Pins.PACKAGE_VERSION).put("non_production", true);
        pkg.put("layout", new Jv.Obj().put("events", "events.jsonl").put("manifest", "run.json (ancillary)")
                .put("children", "children/<name>/ (each an independently portable events.jsonl with its own ancillary run.json)")
                .put("discovery", "events.jsonl is self-describing: its declaration records carry the format, schema, run context, contracts, sources, definitions, children, source ends and finalization; "
                        + "a reader given events.jsonl needs nothing else; this run.json is an ancillary index and summary and may be absent"));
        pkg.put("reader_compat", new Jv.Obj().put("reader_contract", Pins.READER_CONTRACT).put("reader_contract_version", Pins.READER_CONTRACT_VERSION)
                .put("semantic_operations_version", Pins.SEMANTIC_OPERATIONS_VERSION)
                .put("note", "reader/software compatibility is pinned separately from the OCSF version, the extension version, the mapping contract versions and this package layout version"));
        String pkgState = core.failure() != null ? "FAILED" : isFinal ? state.name() : "RECORDING";
        pkg.put("state", pkgState);
        pkg.put("events_count", core.persistedEvents()).put("declarations_count", core.persistedDeclarations()).put("events_bytes", core.persistedBytes());
        pkg.put("persisted_prefix", new Jv.Obj().put("bytes", core.persistedBytes()).put("sha256", core.persistedPrefixSha256())
                .put("meaning", "sha256 of the first `bytes` bytes of events.jsonl as persisted at the last flush (declaration and event lines); a reader of an open package can verify this prefix"));
        if (isFinal && core.finalSha256() != null) {
            pkg.put("events_jsonl_sha256", core.finalSha256());
        }
        else {
            pkg.put("events_jsonl_sha256", Jv.NULL);
            pkg.put("provisional", new Jv.Obj().put("note", pkgState.equals("FAILED")
                    ? "the writer failed; events_count/declarations_count/events_bytes are what was confirmed written before the failure; no final hash exists and the file carries no recording declaration"
                    : "recording: events_count/declarations_count/events_bytes are the lines persisted at the last flush; the final hash is written at finalization"));
        }
        m.put("package", pkg);
        if (run.synthetic() != null && !run.synthetic().isNull()) {
            m.put("synthetic", run.synthetic());
        }
        Jv.Obj rec = new Jv.Obj().put("started", started.toString()).put("last_flush", lastFlush == null ? Jv.NULL : new Jv.Str(lastFlush.toString()))
                .put("flush_count", core.flushCount()).put("state", pkgState);
        if (ended != null) {
            rec.put("ended", ended.toString());
        }
        if (interruptionReason != null) {
            rec.put("interruption_reason", interruptionReason);
        }
        if (core.failure() != null) {
            rec.put("failure", core.failure());
        }
        rec.put("acquisition", acquisition());
        if (process != null) {
            rec.put("process", process);
        }
        rec.put("durability", "flush(): buffered event bytes are written to events.jsonl and run.json is replaced atomically (rename); they are then visible to other processes on this host through the OS page cache. "
                + "fsync (FileChannel.force) is requested only at finalization and on failure, before the final hash is computed from the file as it is on disk. Nothing is claimed about power loss between a flush and finalization.");
        m.put("recording", rec);

        Jv.Obj runObj = new Jv.Obj().put("run_uid", core.runUid).put("invocation_index", run.invocationIndex());
        runObj.put("session_uid", sessionUid == null ? Jv.NULL : new Jv.Str(sessionUid));
        runObj.put("capture_start", run.captureStart().toString());
        runObj.put("cli", new Jv.Obj().put("name", run.cli().name()).put("version", cliVersion()).put("vendor_name", run.cli().vendorName())
                .put("type_id", run.cli().typeId()).put("type", run.cli().type()).put("version_basis", cliVersionBasis()));
        if (run.launchSettings() != null) {
            runObj.put("launch_settings", run.launchSettings());
        }
        if (parent != null) {
            runObj.put("parent", parent);
        }
        runObj.put("children", new Jv.Arr(childLinks));
        if (sourceScope != null) {
            runObj.put("source_scope", sourceScope);
        }
        else if (!children.isEmpty() && ownerArtifact() != null) {
            runObj.put("source_scope", new Jv.Obj().put("artifact", ownerArtifact()).put("owner_path", "parent_tool_use_id").put("owner_value", Jv.NULL)
                    .put("basis", "this core owns the records of the artifact whose owner_path member equals owner_value (null: records without one)"));
        }
        m.put("run", runObj);

        Jv.Obj ocsf = new Jv.Obj().put("version", pin.ocsfVersion()).put("core_commit", Pins.OCSF_SCHEMA_COMMIT).put("compiler_commit", Pins.OCSF_COMPILER_COMMIT)
                .put("toolkit", Pins.ocsfPins()).put("compiled_schema_sha256", pin.compiledSha256());
        if (pin.excerptSha256() != null) {
            ocsf.put("compiled_excerpt_sha256", pin.excerptSha256());
        }
        ocsf.put("compiled_schema_location", "referenced by hash; the definitions this core needs are declared in events.jsonl before use");
        m.put("ocsf", ocsf);

        m.put("extension", new Jv.Obj().put("name", SchemaPin.EXTENSION).put("uid", pin.extensionUid()).put("uid_status", Pins.EXTENSION_UID_STATUS)
                .put("version", pin.extensionVersion()).put("source_sha256", extensionSourceSha256).put("classes", pin.classTable())
                .put("reader_rule", "resolve class names through this pin; refuse a package whose pinned uid/class table differs from the reader's compiled schema"));

        Jv closure = core.closure(pin.classUids(), DefinitionClosure.Policy.WRITTEN);
        Jv full = core.closure(pin.classUids(), DefinitionClosure.Policy.FULL_REACHED);
        m.put("definitions", new Jv.Obj().put("kind", "declared in events.jsonl (definition records, before use) + extension-source here")
                .put("declarations_count", core.declarationsAppended()).put("declaration_bytes", core.declarationBytes()).put("declarations_sha256", core.declarationsSha256())
                .put("written_closure_bytes_measured", DefinitionClosure.bytes(closure).length)
                .put("full_reached_closure_bytes_not_embedded", DefinitionClosure.bytes(full).length)
                .put("extension_source", new Jv.Obj(extensionSource)));

        Jv.Obj shared = new Jv.Obj();
        Jv.Obj agent = new Jv.Obj().put("name", run.cli().name()).put("version", cliVersion()).put("type_id", run.cli().typeId()).put("type", run.cli().type());
        if (sessionUid != null) {
            agent.put("instance_uid", sessionUid);
        }
        agent.put("product", run.cli().product());
        shared.put("cli_agent", agent);
        shared.put("producer", new Jv.Obj().put("name", "agent-journal").put("version", options.producerVersion()).put("vendor_name", "Agent Journal"));
        shared.put("capture_time", started.toEpochMilli()).put("capture_time_text", started.toString());
        shared.put("written_by", new Jv.Obj().put("name", Pins.WRITER_NAME).put("version", Pins.PACKAGE_VERSION)
                .put("mode", "incremental: declarations and events appended as native records arrived; no batch conversion or re-layout"));
        shared.put("contracts", contracts.manifestSection());
        m.put("shared", shared);

        List<Jv> src = new ArrayList<>();
        for (Source s : sources.values()) {
            src.add(s.manifestEntry());
        }
        m.put("sources", new Jv.Arr(src));
        m.put("natives", "native artifacts are Level 0 and are not part of this core; the oracle receives them separately");
        if (archive != null) {
            m.put("archive", archive);
        }

        Jv.Obj sc = new Jv.Obj(((Jv.Obj) semanticContract).members());
        sc.put("sha256", SchemaPin.sha256(Jv.write(semanticContract.member("declarations")).getBytes(StandardCharsets.UTF_8)));
        m.put("semantic_contract", sc);
        m.put("time_policy", new Jv.Obj().put("time", "source-reported time when the record has one (time_basis 1); else the latest preceding reported time in the same artifact, else capture start (time_basis 2); metadata.original_time holds the source text verbatim; ordering is journal.sequence, never time")
                .put("severity_id", "always 0 Unknown: Journal convention, not a vendor assertion"));
        m.put("events", new Jv.Obj().put("count", core.persistedEvents()).put("appended", core.eventsAppended()).put("by_type", core.eventsByType())
                .put("declarations_appended", core.declarationsAppended()));
        return m;
    }

    private Jv acquisition() {
        List<String> problems = new ArrayList<>();
        boolean pending = false;
        for (Source s : sources.values()) {
            if (s.endedHow == null) {
                pending = true;
                problems.add(s.descriptor.artifact() + ": " + s.stateName.toLowerCase());
            }
            else if (s.endedHow == SourceInput.End.INTERRUPTED) {
                problems.add(s.descriptor.artifact() + ": interrupted after " + s.records + " record(s)");
            }
            if (s.incompleteTail != null) {
                problems.add(s.descriptor.artifact() + ": record " + s.incompleteTail + " is incomplete at source end");
            }
            if (s.mixedInput) {
                problems.add(s.descriptor.artifact() + ": mixed byte and record input was rejected");
            }
        }
        Jv.Obj a = new Jv.Obj();
        a.put("status", sources.isEmpty() ? "no sources declared" : pending ? "pending" : problems.isEmpty() ? "complete" : "incomplete");
        Jv.Arr p = new Jv.Arr();
        for (String s : problems) {
            p.add(new Jv.Str(s));
        }
        a.put("detail", p);
        a.put("meaning", "complete: every declared source ended by EOF with its last record whole; this is relative to the declared sources and does not prove the vendor produced nothing else");
        return a;
    }

    private static Jv loadSemanticContract() {
        try (InputStream in = OcsfRunWriter.class.getResourceAsStream(Pins.SEMANTIC_CONTRACT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + Pins.SEMANTIC_CONTRACT_RESOURCE);
            }
            return Jv.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
