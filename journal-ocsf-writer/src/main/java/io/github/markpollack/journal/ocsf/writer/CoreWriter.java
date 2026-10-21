package io.github.markpollack.journal.ocsf.writer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Persistence of one core: the standalone {@code events.jsonl} (declarations and events) plus its ancillary
 * {@code run.json}, for the root or a linked child. Lines are serialized as they are appended and buffered;
 * {@link #flush} hands the buffered bytes to the file and replaces the manifest atomically, so a reader opening the
 * directory at any moment sees a consistent provisional prefix. Finalization appends the {@code recording}
 * declaration, forces the file to storage, hashes what is actually on disk and writes the final manifest once.
 *
 * <p>Self-description: the file opens with the {@code format}, {@code schema} and {@code run} declarations. Before
 * each event is buffered, every contract, source, session, class, object and scalar type the event references and
 * that this file has not yet declared is declared first ({@link #append}); an attribute of an already declared
 * definition that appears for the first time is added by an amending {@code definition} record. Nothing written
 * earlier changes.
 *
 * <p>Visibility/durability as implemented and tested: after {@code flush()} the appended bytes are readable by other
 * processes on this host (OS page cache); {@code fsync} ({@link FileChannel#force}) is requested only at
 * finalization and failure. No claim is made about power loss between a flush and finalization.
 */
final class CoreWriter {

    /** What a core needs to declare things it did not create itself: the run's shared registries. */
    interface Context {
        SchemaPin pin();

        String extensionSourceSha256();

        RunDescriptor run();

        WriterOptions options();

        /** The contract document and hashes for a contract uid, or {@code null} when the run never loaded it. */
        Jv.Obj contractDeclaration(String contractUid);

        /** The source declaration payload for an artifact, or {@code null} when the run never declared it. */
        Jv.Obj sourceDeclaration(String artifact);

        /** The compatibility selections declared so far for an artifact, oldest first (the last is the one in force). */
        java.util.List<Jv.Obj> compatibilityDeclarations(String artifact);

        /** The selected stream reported the CLI version (first report wins; later reports are not re-declared). */
        void cliVersionReported(String version);

        String startedText();
    }

    final Path dir;
    final String runUid;
    private final Path eventsFile;
    private final FileChannel channel;
    private final WriterOptions options;
    private final Context context;
    private final Jv compiled;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private final DefinitionClosure.Collector collector;
    private final MessageDigest prefixDigest;
    private int sequence;
    private int pendingEvents;
    private int pendingLines;
    private int persistedEvents;
    private int persistedDeclarations;
    private long persistedBytes;
    private int flushCount;
    private final Map<String, Integer> byType = new TreeMap<>();
    private boolean finalized;
    private String finalSha256;
    private Jv.Obj failure;

    // declaration state of this file
    private int declarationIndex;
    private final Set<String> declaredUids = new LinkedHashSet<>();
    private final Map<String, Set<String>> declaredAttributes = new LinkedHashMap<>();
    private final Map<String, Integer> amendments = new LinkedHashMap<>();
    private final Set<String> declaredTypes = new LinkedHashSet<>();
    private final Set<String> declaredSources = new LinkedHashSet<>();
    private long declarationBytes;
    private final MessageDigest declarationDigest;

    CoreWriter(Path dir, String runUid, Context context, Jv.Obj runDeclaration) throws IOException {
        this.dir = dir;
        this.runUid = runUid;
        this.context = context;
        this.options = context.options();
        this.compiled = context.pin().compiled();
        this.collector = new DefinitionClosure.Collector(compiled);
        try {
            this.prefixDigest = MessageDigest.getInstance("SHA-256");
            this.declarationDigest = MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Files.createDirectories(dir);
        this.eventsFile = dir.resolve("events.jsonl");
        this.channel = FileChannel.open(eventsFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        declare("format", "format", Declarations.format());
        declare("schema", "schema", Declarations.schema(context.pin(), context.extensionSourceSha256()));
        declare("run", "run:" + runUid, runDeclaration);
    }

    // ------------------------------------------------------------------------------------------ declarations

    private void declare(String kind, String uid, Jv.Obj payload) {
        if (!declaredUids.add(uid)) {
            throw new IllegalStateException("declaration " + uid + " is already written in " + runUid);
        }
        byte[] line = (Jv.write(Declarations.record(kind, uid, ++declarationIndex, payload)) + "\n").getBytes(StandardCharsets.UTF_8);
        pending.writeBytes(line);
        pendingLines++;
        declarationBytes += line.length;
        declarationDigest.update(line);
    }

    boolean declared(String uid) {
        return declaredUids.contains(uid);
    }

    /** Declares the contract (once) and the source (once) this file will reference. */
    void declareSource(String artifact) {
        if (declaredSources.contains(artifact)) {
            return;
        }
        Jv.Obj source = context.sourceDeclaration(artifact);
        if (source == null) {
            throw new IllegalStateException("event references undeclared source " + artifact);
        }
        declareContract(source.get("contract_uid").text());
        declare("source", "source:" + artifact, source);
        declaredSources.add(artifact);
        int n = 0;
        for (Jv.Obj c : context.compatibilityDeclarations(artifact)) {
            declareCompatibility(artifact, ++n, c);
        }
    }

    /**
     * Declares the n-th compatibility selection of a source this file references (the first with the source, a
     * later one when the version became known); a no-op for a source this file never declared.
     */
    void declareCompatibility(String artifact, int n, Jv.Obj payload) {
        String uid = "compatibility:" + artifact + "#" + n;
        if (declaredSources.contains(artifact) && !declared(uid)) {
            declare("compatibility", uid, payload);
        }
    }

    void declareContract(String contractUid) {
        String uid = "contract:" + contractUid;
        if (declared(uid)) {
            return;
        }
        Jv.Obj contract = context.contractDeclaration(contractUid);
        if (contract == null) {
            throw new IllegalStateException("event references unknown contract " + contractUid);
        }
        declare("contract", uid, contract);
    }

    void declareSession(String sessionUid) {
        String uid = "session:" + sessionUid;
        if (!declared(uid)) {
            declare("session", uid, new Jv.Obj().put("session_uid", sessionUid).put("basis", "reported by the source in the first event of this file that carries session_uid"));
        }
    }

    void declareChild(String childRunUid, String path, String spawnToolCallUid, String basis, Jv.Obj parentScope) {
        declare("child", "child:" + spawnToolCallUid, new Jv.Obj().put("run_uid", childRunUid).put("path", path).put("spawn_tool_call_uid", spawnToolCallUid)
                .put("basis", basis).put("parent_scope", parentScope));
    }

    /** Declares how a source this file references ended; a no-op for a source this file never declared. */
    void declareSourceEnd(String artifact, Jv.Obj end) {
        if (declaredSources.contains(artifact) && !declared("source_end:" + artifact)) {
            declare("source_end", "source_end:" + artifact, end);
        }
    }

    boolean referencesSource(String artifact) {
        return declaredSources.contains(artifact);
    }

    void declareRecording(String state, String started, String ended, String interruptionReason, Jv acquisition) {
        Jv.Obj r = new Jv.Obj().put("state", state).put("started", started).put("ended", ended);
        if (interruptionReason != null) {
            r.put("interruption_reason", interruptionReason);
        }
        r.put("acquisition", acquisition).put("events_before", sequence).put("declarations_before", declarationIndex);
        declare("recording", "recording:end", r);
    }

    /** Declares, before the event, every definition, contract, source and session it references and this file lacks. */
    private void declareFor(Jv.Obj event) {
        Jv journal = event.get("journal");
        Jv sourceRef = event.get("source_ref");
        String artifact = sourceRef == null || sourceRef.member("artifact") == null ? null : sourceRef.member("artifact").text();
        if (artifact != null) {
            declareSource(artifact);
        }
        String contract = journal == null || journal.member("contract_uid") == null ? null : journal.member("contract_uid").text();
        if (contract != null) {
            declareContract(contract);
        }
        if (event.get("session_uid") instanceof Jv.Str s) {
            declareSession(s.value());
        }
        if (event.path("settings", "version") instanceof Jv.Str v && !declared("cli_version:reported")) {
            Jv.Obj cv = new Jv.Obj().put("version", v.value()).put("basis", "reported by the selected source in its session settings record; no version command was run");
            if (sourceRef != null && sourceRef.member("record_index") instanceof Jv.Num n) {
                cv.put("record_index", n);
            }
            if (artifact != null) {
                cv.put("artifact", artifact);
            }
            declare("cli_version", "cli_version:reported", cv);
            context.cliVersionReported(v.value());
        }
        Integer classUid = event.get("class_uid") instanceof Jv.Num n ? (int) n.value().longValueExact() : null;
        String className = null;
        for (Map.Entry<String, Integer> c : context.pin().classUids().entrySet()) {
            if (c.getValue().equals(classUid)) {
                className = c.getKey();
            }
        }
        if (className == null) {
            return; // not one of the pinned classes: no definition can be declared for it
        }
        DefinitionClosure.Collector one = new DefinitionClosure.Collector(compiled);
        one.collect(event);
        Jv closure = DefinitionClosure.build(compiled, Map.of(className, classUid), one.profiles, one.present, DefinitionClosure.Policy.WRITTEN);
        for (String section : new String[] {"classes", "objects"}) {
            for (Map.Entry<String, Jv> d : ((Jv.Obj) closure.member(section)).members().entrySet()) {
                declareDefinition(section.equals("classes") ? "class" : "object", d.getKey(), Declarations.body(d.getValue()));
            }
        }
        for (Map.Entry<String, Jv> t : ((Jv.Obj) closure.member("types")).members().entrySet()) {
            if (declaredTypes.add(t.getKey())) {
                Jv.Obj body = new Jv.Obj().put("caption", t.getValue());
                declare("definition", "definition:type/" + t.getKey(), new Jv.Obj().put("kind_of", "type").put("key", t.getKey()).put("sha256", Declarations.sha256(body)).put("body", body));
            }
        }
    }

    private void declareDefinition(String kindOf, String key, Jv.Obj body) {
        Set<String> have = declaredAttributes.get(key);
        Set<String> names = Declarations.attributeNames(body);
        if (have == null) {
            declare("definition", "definition:" + key, new Jv.Obj().put("kind_of", kindOf).put("key", key).put("sha256", Declarations.sha256(body)).put("body", body));
            declaredAttributes.put(key, new LinkedHashSet<>(names));
            return;
        }
        Set<String> added = new LinkedHashSet<>(names);
        added.removeAll(have);
        if (added.isEmpty()) {
            return;
        }
        int n = amendments.merge(key, 1, Integer::sum) + 1;
        Jv.Obj amendment = Declarations.restrict(body, added);
        declare("definition", "definition:" + key + "#" + n, new Jv.Obj().put("kind_of", kindOf).put("key", key).put("amends", "definition:" + key)
                .put("sha256", Declarations.sha256(amendment)).put("body", amendment));
        have.addAll(added);
    }

    // ------------------------------------------------------------------------------------------ events

    /** Declares what the event needs, assigns the next sequence (and this core's run uid) and buffers the event; nothing reaches the file until a flush. */
    void append(Jv.Obj event) {
        if (finalized || failure != null) {
            throw new WriterFailedException("core " + runUid + " is " + (finalized ? "finalized" : "failed") + "; no more events can be appended");
        }
        declareFor(event);
        Jv.Obj journal = (Jv.Obj) event.get("journal");
        journal.members().put("sequence", Jv.Num.of(++sequence));
        journal.members().put("run_uid", new Jv.Str(runUid));
        collector.collect(event);
        byType.merge(event.get("type_name").text(), 1, Integer::sum);
        pending.writeBytes((Jv.write(event) + "\n").getBytes(StandardCharsets.UTF_8));
        pendingEvents++;
        pendingLines++;
    }

    int pendingEvents() {
        return pendingEvents;
    }

    /** Writes the buffered lines to the file and replaces the manifest; returns the number of events now persisted. */
    int flush(Function<CoreWriter, Jv> manifest) throws IOException {
        if (finalized) {
            return persistedEvents;
        }
        if (failure != null) {
            throw new WriterFailedException("core " + runUid + " failed earlier: " + failure.get("detail").text());
        }
        try {
            if (pendingLines > 0) {
                byte[] bytes = pending.toByteArray();
                options.faults().before("append", runUid + " " + pendingEvents + " event(s), " + bytes.length + " bytes");
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
                prefixDigest.update(bytes);
                pending.reset();
                persistedEvents += pendingEvents;
                persistedDeclarations += pendingLines - pendingEvents;
                persistedBytes += bytes.length;
                pendingEvents = 0;
                pendingLines = 0;
            }
            flushCount++;
            writeManifest(manifest.apply(this));
            return persistedEvents;
        }
        catch (IOException e) {
            fail("flush", e);
            throw e;
        }
    }

    /** Forces the lines to storage, hashes the file as it is on disk and writes the final manifest; idempotent. */
    void finish(Function<CoreWriter, Jv> manifest) throws IOException {
        if (finalized) {
            return;
        }
        if (failure != null) {
            return; // the failure manifest already describes the persisted boundary
        }
        try {
            flush(manifest);
            options.faults().before("force", runUid);
            channel.force(true);
            finalSha256 = SchemaPin.sha256(Files.readAllBytes(eventsFile));
            finalized = true;
            writeManifest(manifest.apply(this));
            channel.close();
        }
        catch (IOException e) {
            fail("finish", e);
            throw e;
        }
    }

    /** Records the failure, keeps what the file holds, writes a FAILED manifest when it can, and closes the channel. */
    void fail(String operation, Exception cause) {
        if (failure != null || finalized) {
            return;
        }
        long onDisk;
        try {
            onDisk = channel.size();
        }
        catch (IOException e) {
            onDisk = -1;
        }
        failure = new Jv.Obj().put("operation", operation).put("exception", cause.getClass().getName())
                .put("detail", cause.getMessage() == null ? cause.toString() : cause.getMessage())
                .put("events_confirmed_persisted", persistedEvents)
                .put("events_not_confirmed", pendingEvents)
                .put("bytes_confirmed_persisted", persistedBytes)
                .put("bytes_on_disk_at_failure", onDisk)
                .put("note", "events_not_confirmed may be partly on disk; the reader judges the file as it is and the manifest carries no final hash; the file carries no recording declaration");
        try {
            channel.force(true);
        }
        catch (IOException ignored) {
            // the failure is already recorded; nothing more can be promised
        }
        try {
            channel.close();
        }
        catch (IOException ignored) {
            // same
        }
    }

    /** Writes the failure manifest (best effort; a second failure here is reported to the caller). */
    void writeFailureManifest(Function<CoreWriter, Jv> manifest) throws IOException {
        writeManifest(manifest.apply(this));
    }

    private void writeManifest(Jv manifest) throws IOException {
        options.faults().before("manifest", runUid);
        Path tmp = dir.resolve("run.json.tmp");
        Files.writeString(tmp, Jv.write(manifest) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("run.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ------------------------------------------------------------------------------------------ manifest inputs

    boolean finalized() {
        return finalized;
    }

    Jv.Obj failure() {
        return failure;
    }

    int persistedEvents() {
        return persistedEvents;
    }

    int persistedDeclarations() {
        return persistedDeclarations;
    }

    long persistedBytes() {
        return persistedBytes;
    }

    /** SHA-256 of the bytes persisted so far (the flushed prefix), for the ancillary manifest. */
    String persistedPrefixSha256() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) prefixDigest.clone()).digest());
        }
        catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    int flushCount() {
        return flushCount;
    }

    String finalSha256() {
        return finalSha256;
    }

    Jv eventsByType() {
        Jv.Obj o = new Jv.Obj();
        byType.forEach(o::put);
        return o;
    }

    int eventsAppended() {
        return sequence;
    }

    int declarationsAppended() {
        return declarationIndex;
    }

    long declarationBytes() {
        return declarationBytes;
    }

    /** SHA-256 over the declaration lines appended so far, in order. */
    String declarationsSha256() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) declarationDigest.clone()).digest());
        }
        catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The measured (not embedded) closure over everything written so far, for the ancillary size comparison. */
    Jv closure(Map<String, Integer> classUids, DefinitionClosure.Policy policy) {
        return collector.build(classUids, policy);
    }

    /** Thrown when a writer or core that failed or was finalized is used again. */
    static final class WriterFailedException extends IllegalStateException {
        WriterFailedException(String message) {
            super(message);
        }
    }
}
