package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.github.markpollack.journal.tck.ocsf.TerminalReading.*;

/** Independent check of a fresh-transcript-start retained generation (text turns, Claude Code 2.1.316): the frozen profile
 * {@value #PROFILE} bounds it to exactly two turns, the ordinary profile {@value #ORDINARY_PROFILE} admits any sealed turn count.
 * The first turn's before-prefix is the tagged ABSENT observation, never an invented empty-file witness.
 * Ordered caller observations (absence probes, candidate scan, dispatch, readable prefixes, final singleton)
 * are trusted evidence checked for consistency; nothing here claims OS containment between probes.
 * {@link Verdict#REFUSED} is a contradiction; {@link Verdict#INCOMPLETE} is evidence that was not established.
 */
public final class FreshRetainedGeneration {
    public static final String KIND = "journal-retained-fresh-generation/1";
    /** Frozen bounded profile: exactly two sealed turns; its rules do not change. */
    public static final String PROFILE = "client-terminal-claude-retained-fresh-2.1.316/1";
    /** Ordinary fresh-start profile: any sealed turn count under the same per-turn rules. */
    public static final String ORDINARY_PROFILE = "client-terminal-claude-retained-fresh-2.1.316/2";
    public static final Set<String> PROFILES = Set.of(PROFILE, ORDINARY_PROFILE);
    private static final Set<String> HISTORY_FLAGS = Set.of("--resume", "-r", "--continue", "-c", "--fork-session");
    private static final Set<String> IDENTITY_RECORDS = Set.of("system", "assistant", "result");
    private FreshRetainedGeneration() { }

    /** Separates contradiction from unestablished evidence; both refuse qualification. */
    public enum Verdict { REFUSED, INCOMPLETE }

    /** The reason a fresh-start generation does not qualify. */
    public static final class Rejection extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Verdict verdict;
        Rejection(Verdict verdict, String reason, Throwable cause) { super(verdict + ": " + reason, cause); this.verdict = verdict; }
        public Verdict verdict() { return verdict; }
    }

    /** Validates header, originals, turn membership, fresh launch, emitted identity and the observation journal. */
    public static void validate(Json manifest, Path base, String profile) throws IOException {
        require(PROFILES.contains(profile), Verdict.REFUSED, "unsupported fresh retained profile");
        preliminary(manifest);
        try { RetainedGeneration.validate(manifest, base, true, PROFILE.equals(profile)); }
        catch (Rejection rejection) { throw rejection; }
        catch (IllegalArgumentException contradiction) { throw new Rejection(Verdict.REFUSED, contradiction.getMessage(), contradiction); }
        Map<String, RetainedSource> sources = RetainedGeneration.sources(manifest, base);
        Json fresh = freshStart(manifest, sources);
        Observations observations = new Observations(manifest, base, sources, fresh);
        identity(manifest, sources, observations.dispatchStdout);
    }

    private static void preliminary(Json m) {
        require(m instanceof Json.JObject, Verdict.REFUSED, "manifest is not an object");
        for (String key : List.of("sealed", "observedResourcesTerminated", "stdoutNaturalEof", "stderrNaturalEof"))
            require(m.member(key) instanceof Json.JBool b && b.value(), Verdict.INCOMPLETE, "generation not sealed/EOF: " + key);
        require(m.member("overflow") instanceof Json.JBool b && !b.value(), Verdict.INCOMPLETE, "generation overflow");
        Set<String> names = new HashSet<>();
        if (m.member("sources") instanceof Json.JArray a) for (Json source : a.elements()) if (source.member("source") instanceof Json.JString s) names.add(s.value());
        require(names.containsAll(Set.of("STDIN", "STDOUT", "STDERR", "TRANSCRIPT")), Verdict.INCOMPLETE, "native/wire originals missing");
        require(!m.member("observedNativeIdentity").isNull(), Verdict.INCOMPLETE, "no CLI-emitted identity record");
        require(!m.member("observations").isNull(), Verdict.INCOMPLETE, "fresh start not observed");
    }

    private static Json freshStart(Json m, Map<String, RetainedSource> sources) {
        Json f = m.member("freshStart");
        fields(f, "requestedSessionId expectedNativePath launchArgv processIdentity cwd");
        String session = string(m, "nativeSessionId");
        require(string(f, "requestedSessionId").equals(session), Verdict.REFUSED, "requested identity differs from native session");
        require(string(f, "processIdentity").equals(string(m, "connectionId")), Verdict.REFUSED, "process identity differs from generation");
        require(string(f, "expectedNativePath").endsWith("/" + session + ".jsonl"), Verdict.REFUSED, "expected native path does not name the requested session");
        List<String> argv = array(f, "launchArgv").stream().map(Json::text).toList();
        require(argv.contains(session), Verdict.REFUSED, "launch argv does not request the session");
        require(argv.stream().noneMatch(HISTORY_FLAGS::contains), Verdict.REFUSED, "fresh-launch/membership contradiction: history reuse option");
        String cwd = string(f, "cwd");
        for (Json turn : array(m, "turns")) for (Json locator : array(turn, "nativeRecords"))
            require(cwd.equals(string(sources.get("TRANSCRIPT").record(locator), "cwd")), Verdict.REFUSED, "native cwd differs from fresh launch");
        return f;
    }

    private static void identity(Json m, Map<String, RetainedSource> sources, long dispatchStdout) {
        Json i = m.member("observedNativeIdentity");
        fields(i, "source start end sha256 reportedSessionId recordType");
        require("STDOUT".equals(string(i, "source")), Verdict.REFUSED, "identity record is not an original stdout record");
        Json row = sources.get("STDOUT").record(new Json.JObject(Map.of("source", i.member("source"), "start", i.member("start"), "end", i.member("end"), "sha256", i.member("sha256"))));
        String type = string(row, "type");
        require(IDENTITY_RECORDS.contains(type) && type.equals(string(i, "recordType")), Verdict.INCOMPLETE, "record is not a CLI-emitted identity-bearing message");
        String session = string(m, "nativeSessionId");
        require(session.equals(string(i, "reportedSessionId")) && session.equals(string(row, "session_id")), Verdict.REFUSED, "emitted identity contradicts requested/native session");
        require(number(i, "start") >= dispatchStdout, Verdict.REFUSED, "identity record precedes first dispatch");
    }

    /** Ordered trusted observations; sequence and monotonic time must advance and every stage must be present. */
    private static final class Observations {
        private final String expected;
        private final RetainedSource transcript;
        private final List<Json> prefixes = new ArrayList<>();
        private long seq = -1, nanos = -1, dispatchStdout = -1;
        private int probes, scans, finals;
        private String fileKey;
        private boolean dispatched;

        Observations(Json m, Path base, Map<String, RetainedSource> sources, Json fresh) throws IOException {
            expected = string(fresh, "expectedNativePath"); transcript = sources.get("TRANSCRIPT");
            Json declaration = m.member("observations"); fields(declaration, "path sha256 size");
            Path path = base.resolve(string(declaration, "path"));
            require(Files.isRegularFile(path), Verdict.INCOMPLETE, "observation journal missing");
            byte[] bytes = Files.readAllBytes(path);
            require(number(declaration, "size") == bytes.length && string(declaration, "sha256").equals(NativeArtifact.sha256(bytes)), Verdict.REFUSED, "observation journal changed");
            for (String line : new String(bytes, StandardCharsets.UTF_8).lines().toList()) read(NeutralJson.parse(line), m);
            require(probes >= 2 && scans >= 1, Verdict.INCOMPLETE, "fresh start not observed: two ENOENT probes and a complete scan are required before dispatch");
            require(dispatched && !prefixes.isEmpty(), Verdict.INCOMPLETE, "no readable original prefix after first dispatch");
            require(finals == 1, Verdict.INCOMPLETE, "final candidate enumeration missing");
            boundaries(m);
        }

        private void read(Json o, Json m) {
            long s = number(o, "seq"), n = number(o, "monotonicNanos");
            require(s > seq && n > nanos, Verdict.REFUSED, "observation order not monotonic"); seq = s; nanos = n;
            require(finals == 0, Verdict.REFUSED, "observation after final enumeration");
            switch (string(o, "kind")) {
                case "probe" -> probe(o);
                case "scan" -> scan(o, m);
                case "dispatch" -> dispatch(o, m);
                case "prefix" -> prefix(o);
                case "final" -> last(o);
                default -> throw new Rejection(Verdict.REFUSED, "unknown observation kind", null);
            }
        }

        private void probe(Json o) {
            fields(o, "seq monotonicNanos kind path result parentPath parentFileKey");
            require(!dispatched, Verdict.REFUSED, "absence probe after dispatch is not a fresh-start witness");
            require(expected.equals(string(o, "path")), Verdict.REFUSED, "probe path differs from expected native path");
            String result = string(o, "result");
            require(!"EXISTS".equals(result), Verdict.REFUSED, "fresh profile cannot use existing path/history");
            require("ENOENT".equals(result), Verdict.INCOMPLETE, "absence probe result is UNKNOWN: " + result);
            string(o, "parentPath"); string(o, "parentFileKey"); probes++;
        }

        private void scan(Json o, Json m) {
            fields(o, "seq monotonicNanos kind root sessionId matches skipped unreadable");
            require(!dispatched, Verdict.REFUSED, "candidate scan after dispatch is not a fresh-start witness");
            string(o, "root"); require(string(m, "nativeSessionId").equals(string(o, "sessionId")), Verdict.REFUSED, "scan identity differs");
            require(array(o, "matches").isEmpty(), Verdict.REFUSED, "fresh profile cannot use existing path/history: candidate matched");
            require(number(o, "skipped") == 0 && number(o, "unreadable") == 0, Verdict.INCOMPLETE, "unique exhaustive identity not established: skipped/unreadable candidates");
            scans++;
        }

        private void dispatch(Json o, Json m) {
            fields(o, "seq monotonicNanos kind executionId stdinOffset stdoutOffset");
            Json turn = RetainedGeneration.turn(m, string(o, "executionId"));
            require(number(turn.member("prompt"), "start") == number(o, "stdinOffset"), Verdict.REFUSED, "dispatch offset differs from the admitted envelope");
            require(number(o, "stdoutOffset") <= number(turn.member("startOffsets"), "STDOUT"), Verdict.REFUSED, "dispatch stdout offset outside the turn window");
            if (!dispatched) { require(probes >= 2 && scans >= 1, Verdict.INCOMPLETE, "fresh start not observed before first dispatch"); dispatchStdout = number(o, "stdoutOffset"); }
            dispatched = true;
        }

        private void prefix(Json o) {
            fields(o, "seq monotonicNanos kind path fileKey bytes sha256");
            require(dispatched, Verdict.INCOMPLETE, "creation continuity unknown: readable prefix before dispatch");
            require(expected.equals(string(o, "path")), Verdict.REFUSED, "prefix path differs from expected native path");
            String key = rawString(o, "fileKey"); require(!key.isEmpty(), Verdict.INCOMPLETE, "stable file identity missing");
            if (fileKey == null) fileKey = key;
            require(fileKey.equals(key), Verdict.REFUSED, "original/file continuity broken: file identity changed");
            long count = number(o, "bytes"), previous = prefixes.isEmpty() ? 1 : number(prefixes.get(prefixes.size() - 1), "bytes");
            require(count >= previous, Verdict.REFUSED, "original/file continuity broken: prefix shrank or is empty");
            try { transcript.prefix(new Json.JObject(Map.of("bytes", o.member("bytes"), "sha256", o.member("sha256")))); } catch (IllegalArgumentException e) { throw new Rejection(Verdict.REFUSED, "original/file continuity broken: " + e.getMessage(), e); }
            prefixes.add(o);
        }

        private void last(Json o) {
            fields(o, "seq monotonicNanos kind path fileKey bytes sha256 candidates skipped unreadable");
            require(dispatched && !prefixes.isEmpty(), Verdict.INCOMPLETE, "final enumeration precedes readable prefix");
            require(expected.equals(string(o, "path")) && fileKey.equals(string(o, "fileKey")), Verdict.REFUSED, "final file identity differs");
            require(number(o, "bytes") == transcript.size() && string(o, "sha256").equals(transcript.sha256()), Verdict.REFUSED, "forged complete declaration: final observation differs from original");
            List<Json> candidates = array(o, "candidates");
            require(number(o, "skipped") == 0 && number(o, "unreadable") == 0 && candidates.size() == 1, Verdict.INCOMPLETE, "unique exhaustive identity not established");
            require(expected.equals(candidates.get(0).text()), Verdict.REFUSED, "final candidate differs from expected native path");
            finals++;
        }

        private void boundaries(Json m) {
            List<Json> turns = array(m, "turns");
            for (int i = 0; i < turns.size(); i++) {
                observed(turns.get(i).member("nativePrefixAfter"));
                if (i > 0) observed(turns.get(i).member("nativePrefixBefore"));
            }
        }

        private void observed(Json prefix) {
            boolean seen = prefixes.stream().anyMatch(o -> number(o, "bytes") == number(prefix, "bytes") && string(o, "sha256").equals(string(prefix, "sha256")));
            require(seen, Verdict.INCOMPLETE, "declared native prefix was not an ordered readable observation");
        }
    }

    private static void require(boolean pass, Verdict verdict, String reason) { if (!pass) throw new Rejection(verdict, reason, null); }
}
