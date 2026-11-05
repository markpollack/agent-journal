package io.github.markpollack.journal.ocsf.writer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.UUID;

/**
 * One synchronous delivery attempt for a frozen accepted Client decision. Call on the independent
 * recording lane, never the Client control/result lane. The configured namespace is trusted receiver
 * configuration. The store must be private, protected against concurrent external mutation and support
 * same-filesystem atomic no-replace hard links. There is no power-loss or eventual-retry guarantee.
 *
 * <p>The evidence-only profile preserves declared evidence and checks bytes/associations. The opt-in
 * Claude profile assembles a qualified one-shot native acquisition alongside the immutable decision.
 * Neither profile proves Client arbitration or replaces independent complete-archive acceptance.
 * Existing markers and prepared evidence are never repaired, replaced or deleted by this receiver.
 */
public final class TerminalReceiver {
    public static final String CONTRACT = TerminalContract.CONTRACT;
    public static final String ROUTE = TerminalContract.ROUTE;
    public static final String PROFILE = TerminalContract.PROFILE;
    public static final String CLAUDE_PROFILE = TerminalContract.CLAUDE_PROFILE;
    public static final String RETAINED_PROFILE = TerminalContract.RETAINED_PROFILE;
    /** Opt-in fresh-transcript-start retained profile; the frozen retained profile above is unchanged. */
    public static final String FRESH_RETAINED_PROFILE = TerminalContract.FRESH_RETAINED_PROFILE;
    /** Ordinary fresh-start retained profile: any sealed turn count; the frozen two-turn fresh profile above is unchanged. */
    public static final String FRESH_RETAINED_ORDINARY_PROFILE = TerminalContract.FRESH_RETAINED_ORDINARY_PROFILE;
    public static final String CODEX_PROFILE = TerminalCodexProfile.PROFILE;
    /** Frozen retained Codex profile: one turn of a sealed two-turn thread with its original per-process evidence. */
    public static final String CODEX_RETAINED_PROFILE = TerminalContract.CODEX_RETAINED_PROFILE;
    /** Ordinary retained Codex profile: one turn of a sealed thread of any turn count, the same per-turn rules. */
    public static final String CODEX_RETAINED_ORDINARY_PROFILE = TerminalContract.CODEX_RETAINED_ORDINARY_PROFILE;
    private final Path root;
    private final String namespace;
    private final WriterOptions options;

    /** Creates directories only; no provider, background worker, registry or shared configuration. */
    public TerminalReceiver(Path root, String namespace, WriterOptions options) throws IOException {
        if (namespace == null || namespace.isEmpty() || namespace.length() > 4096) throw new IllegalArgumentException("invalid producer namespace");
        this.root = root.toAbsolutePath().normalize();
        this.namespace = namespace;
        this.options = java.util.Objects.requireNonNull(options);
        directory(this.root); directory(this.root.resolve("terminal")); directory(this.root.resolve("revisions")); directory(this.root.resolve("prepared"));
    }

    /**
     * Validates exact canonical P/D/A, seals an evidence package and atomically publishes it. Identical
     * retries return the original canonical ACK string, including its original preparedAt and artifact
     * paths. Changed payload for the same execution throws {@link IllegalArgumentException}; an I/O
     * failure may leave acceptance unknown and must be resolved by retrying these same frozen bytes.
     * @param envelope exact journal-terminal-envelope/1 bytes
     * @param sourceRoot protected directory containing the immutable relative paths declared by A
     * @return exact stored canonical ACK bytes represented as UTF-8 text
     */
    public String receive(byte[] envelope, Path sourceRoot) throws IOException {
        TerminalContract.Input input = TerminalContract.envelope(envelope.clone(), namespace);
        String execution = TerminalValues.text(input.decision(), "executionId");
        Path marker = root.resolve("terminal").resolve(key(namespace, execution) + ".json");
        Jv.Obj payload = new Jv.Obj().put("terminalEnvelope", input.envelope()).put("association", TerminalJson.write(input.association()))
                .put("originalBindingId", Jv.Null.INSTANCE);
        return publish(marker, input, input.association(), payload, sourceRoot);
    }

    /**
     * Publishes an immutable evidence revision after the original ACK is known. The original decision
     * and marker remain unchanged. Each revision names the initial revision as parent. The Client
     * delivery policy bounds how many revisions it submits; the receiver starts no revision service.
     */
    public String receiveRevision(String originalBindingId, byte[] association, Path sourceRoot) throws IOException {
        Jv a = TerminalJson.read(association.clone());
        String execution = TerminalValues.text(a, "executionId");
        Jv original = readMarker(root.resolve("terminal").resolve(key(namespace, execution) + ".json"));
        TerminalValues.equal(original.member("ack"), "bindingId", originalBindingId);
        String envelope = TerminalValues.string(original.member("payload"), "terminalEnvelope");
        TerminalContract.Input input = TerminalContract.envelope(envelope.getBytes(StandardCharsets.UTF_8), namespace);
        TerminalAssociation.check(a, input.decision());
        TerminalValues.equal(a, "priorRevisionId", TerminalValues.text(input.association(), "revisionId"));
        String revision = TerminalValues.text(a, "revisionId");
        if (revision.equals(TerminalValues.text(input.association(), "revisionId"))) throw new IllegalArgumentException("revision reuses initial id");
        Path marker = root.resolve("revisions").resolve(key(namespace, execution, TerminalValues.text(input.decision(), "decisionId"), revision) + ".json");
        Jv.Obj payload = new Jv.Obj().put("terminalEnvelope", input.envelope()).put("association", TerminalJson.write(a)).put("originalBindingId", originalBindingId);
        return publish(marker, input, a, payload, sourceRoot);
    }

    private String publish(Path marker, TerminalContract.Input input, Jv association, Jv.Obj payload, Path sourceRoot) throws IOException {
        String exact = TerminalJson.write(payload);
        if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            Path candidate = Files.createTempDirectory(root.resolve("prepared"), "candidate-");
            Jv.Arr artifacts = copySources(candidate, association, sourceRoot);
            Path events = writePackage(candidate, payload, input, association);
            artifacts.add(artifact(events));
            String binding = UUID.randomUUID().toString();
            Jv.Obj ack = new Jv.Obj().put("receiverContract", CONTRACT).put("bindingId", binding).put("producerNamespace", namespace)
                    .put("executionId", TerminalValues.text(input.decision(), "executionId")).put("decisionId", TerminalValues.text(input.decision(), "decisionId"))
                    .put("revisionId", TerminalValues.text(association, "revisionId")).put("payloadSha256", payloadHash(payload))
                    .put("preparedAt", DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(java.time.ZoneOffset.UTC).format(options.clock().instant()))
                    .put("artifacts", artifacts);
            Path sealed = candidate.resolve("marker.json");
            writeForced(sealed, TerminalJson.write(new Jv.Obj().put("payload", payload).put("ack", ack)).getBytes(StandardCharsets.UTF_8));
            readMarker(sealed);
            options.faults().before("terminal-publish", marker.toString());
            try { Files.createLink(marker, sealed); }
            catch (FileAlreadyExistsException duplicate) { /* Exact validation of the winner below. */ }
            options.faults().before("terminal-reply", marker.toString());
        }
        Jv winner = readMarker(marker);
        if (!exact.equals(TerminalJson.write(winner.member("payload")))) throw new IllegalArgumentException("terminal publication conflict");
        return TerminalJson.write(winner.member("ack"));
    }

    private Path writePackage(Path candidate, Jv.Obj payload, TerminalContract.Input input, Jv association) throws IOException {
        if (TerminalCodexProfile.complete(input, association)) {
            Path manifest = candidate.resolve("sources/acquisition.json");
            TerminalCodexProfile.validate(manifest, input, association);
            AcquiredExecution.assembleTerminal(manifest, candidate, payload, options);
            return candidate.resolve("events.jsonl");
        }
        if (TerminalNativeProfile.complete(input, association)) {
            Path manifest = candidate.resolve("sources/acquisition.json");
            TerminalRetainedProfile.validate(input, manifest);
            TerminalNativeProfile.validate(manifest, input.decision(), association);
            AcquiredExecution.assembleTerminal(manifest, candidate, payload, options);
            return candidate.resolve("events.jsonl");
        }
        RunDescriptor run = new RunDescriptor(UUID.randomUUID().toString(), 0, null, options.clock().instant(),
                new RunDescriptor.Cli("client-terminal-evidence", "1", "Agent Journal"), null, null);
        try (OcsfRunWriter writer = OcsfRunWriter.openTerminal(candidate, run, SchemaPin.bundled(), options, payload)) {
            writer.complete();
            if (writer.state() != OcsfRunWriter.State.COMPLETED) throw new IOException("terminal writer did not complete");
        }
        return candidate.resolve("events.jsonl");
    }

    private Jv.Arr copySources(Path candidate, Jv association, Path sourceRoot) throws IOException {
        Jv.Arr artifacts = new Jv.Arr();
        java.util.Set<Path> copied = new java.util.HashSet<>();
        for (Jv source : TerminalValues.array(association, "sources")) {
            Path relative = TerminalValues.relative(TerminalValues.text(source, "relativePath"));
            Path input = safe(sourceRoot.toAbsolutePath().normalize(), relative);
            Path output = candidate.resolve("sources").resolve(relative);
            Files.createDirectories(output.getParent());
            if (!Files.exists(output)) {
                Files.copy(input, output);
                force(output);
            }
            if (Files.size(output) != TerminalValues.number(source, "size") || !fileHash(output).equals(TerminalValues.text(source, "sha256"))) throw new TerminalIntegrityException("source hash/extent mismatch");
            if (copied.add(output)) artifacts.add(artifact(output));
        }
        return artifacts;
    }

    private Jv.Obj artifact(Path file) throws IOException {
        return new Jv.Obj().put("relativePath", root.relativize(file).toString()).put("sha256", fileHash(file)).put("size", Files.size(file));
    }

    private Jv readMarker(Path marker) throws IOException {
        safe(root, root.relativize(marker));
        Jv value = TerminalJson.read(Files.readAllBytes(marker));
        TerminalValues.fields(value, "payload ack");
        Jv ack = value.member("ack");
        Jv payload = value.member("payload");
        Jv association = validateAcknowledgment(value);
        TerminalInventory.check(ack, association);
        boolean foundEvents = false;
        TerminalValues.equal(ack, "receiverContract", CONTRACT); TerminalValues.equal(ack, "producerNamespace", namespace);
        TerminalValues.equal(ack, "payloadSha256", payloadHash(value.member("payload")));
        for (Jv artifact : TerminalValues.array(ack, "artifacts")) {
            TerminalValues.fields(artifact, "relativePath sha256 size");
            Path file = safe(root, TerminalValues.relative(TerminalValues.text(artifact, "relativePath")));
            if (Files.size(file) != TerminalValues.number(artifact, "size") || !fileHash(file).equals(TerminalValues.text(artifact, "sha256"))) throw new TerminalIntegrityException("published artifact integrity failure");
            if (TerminalValues.text(artifact, "relativePath").matches("prepared/candidate-[^/]+/events[.]jsonl")) { validateEvents(file, payload); foundEvents = true; }
        }
        if (!foundEvents) throw new TerminalIntegrityException("marker has no canonical event file");
        return value;
    }

    private Jv validateAcknowledgment(Jv value) {
        Jv ack = value.member("ack");
        TerminalValues.fields(ack, "receiverContract bindingId producerNamespace executionId decisionId revisionId payloadSha256 preparedAt artifacts");
        TerminalValues.text(ack, "bindingId"); TerminalValues.instant(ack, "preparedAt");
        Jv payload = value.member("payload");
        TerminalValues.fields(payload, "terminalEnvelope association originalBindingId");
        TerminalContract.Input input = TerminalContract.envelope(TerminalValues.string(payload, "terminalEnvelope").getBytes(StandardCharsets.UTF_8), namespace);
        Jv association = TerminalJson.read(TerminalValues.string(payload, "association").getBytes(StandardCharsets.UTF_8));
        TerminalAssociation.check(association, input.decision());
        TerminalValues.equal(ack, "executionId", TerminalValues.text(input.decision(), "executionId"));
        TerminalValues.equal(ack, "decisionId", TerminalValues.text(input.decision(), "decisionId"));
        TerminalValues.equal(ack, "revisionId", TerminalValues.text(association, "revisionId"));
        return association;
    }

    private static Path safe(Path base, Path relative) throws IOException {
        Path current = base;
        if (Files.isSymbolicLink(current) || !current.toRealPath().equals(current)) throw new IOException("symlink root");
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("symlink in protected path");
        }
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) throw new TerminalIntegrityException("missing regular artifact");
        return current;
    }

    private static void directory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("symlink store directory");
        Files.createDirectories(path);
        if (!path.toRealPath().equals(path)) throw new IOException("store contains symlink ancestor");
    }

    private static String payloadHash(Jv payload) {
        String exact = payload.member("originalBindingId").isNull() ? TerminalValues.string(payload, "terminalEnvelope") : TerminalJson.write(payload);
        return hash(exact.getBytes(StandardCharsets.UTF_8));
    }

    private static void validateEvents(Path events, Jv payload) throws IOException {
        boolean found = false, complete = false;
        for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
            Jv record = Jv.parse(line);
            if (record.path("run", "terminal_binding") != null) found = TerminalJson.write(record.path("run", "terminal_binding")).equals(TerminalJson.write(payload));
            if (record.path("recording", "state") != null) complete = "COMPLETED".equals(record.path("recording", "state").text());
        }
        if (!found || !complete) throw new TerminalIntegrityException("incomplete/mismatched terminal canonical file");
    }

    private static String fileHash(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void writeForced(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); force(file);
    }
    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    static String hash(byte[] bytes) { return SchemaPin.sha256(bytes); }
    static String key(String... parts) {
        byte[][] strings = Arrays.stream(parts).map(s -> s.getBytes(StandardCharsets.UTF_8)).toArray(byte[][]::new);
        int size = Arrays.stream(strings).mapToInt(s -> s.length + Integer.BYTES).sum();
        ByteBuffer framed = ByteBuffer.allocate(size);
        for (byte[] bytes : strings) framed.putInt(bytes.length).put(bytes);
        return hash(framed.array());
    }
}
