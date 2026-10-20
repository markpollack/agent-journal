package io.github.markpollack.journal.tck.verify;

import io.github.markpollack.journal.tck.verify.CandidatePackage.Event;
import io.github.markpollack.journal.tck.verify.CandidatePackage.Run;
import io.github.markpollack.journal.tck.verify.Check.Category;
import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle.Block;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle.Line;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle.Owner;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle.PermissionDecision;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle.ToolResult;
import io.github.markpollack.journal.tck.verify.ClaudeStreamOracle.ToolUse;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compares a persisted Journal run package (the candidate, read by {@link CandidatePackage}) with the Claude Code
 * stream it was recorded from (the oracle, read by {@link ClaudeStreamOracle}) and reports four separate results:
 * capture completeness, preservation, normalization correctness and explanation of generated values. Every
 * property is judged per owner (the main thread and each sub-agent), by occurrence and multiplicity before any
 * lookup by id, with exact numbers, and a value is never matched because an equal value exists elsewhere.
 *
 * <p>The checking path is this class, {@link ClaudeStreamOracle}, {@link CandidatePackage}, {@link Json} and
 * {@link NeutralJson}: Jackson's tokenizer as neutral infrastructure, nothing from a capture module, the SDK or
 * {@code journal-core}. The pinned data it interprets with is {@link ClaudeStreamContract}.
 */
public final class ClaudeStreamVerifier {

    private final List<ClaudeStreamOracle> streams;
    private final CandidatePackage candidate;
    private final VerificationReport report = new VerificationReport();

    private ClaudeStreamVerifier(List<ClaudeStreamOracle> streams, CandidatePackage candidate) {
        this.streams = streams;
        this.candidate = candidate;
    }

    /**
     * @param streams the native stream of each recorded invocation, in invocation order (index 0 first)
     * @param candidate the persisted package
     * @throws IllegalStateException if the candidate's events schema version is not one this verifier implements
     */
    public static VerificationReport verify(List<ClaudeStreamOracle> streams, CandidatePackage candidate) {
        return new ClaudeStreamVerifier(streams, candidate).run();
    }

    public static VerificationReport verify(ClaudeStreamOracle stream, CandidatePackage candidate) {
        return verify(List.of(stream), candidate);
    }

    private VerificationReport run() {
        describe();
        schemaVersion();
        Run root = candidate.root();
        Map<Owner, Run> runsByOwner = linkOwners();
        capture();
        for (Map.Entry<Owner, Run> e : runsByOwner.entrySet()) {
            Owner owner = e.getKey();
            Run run = e.getValue();
            if (run == null) {
                continue; // reported by children.linked
            }
            String label = owner.label();
            sourceRecords(owner, run, label);
            if (run == root) {
                vendorEvents(root);
                droppedLines();
            }
            toolCalls(owner, run, label);
            assistantBlocks(owner, run, label);
            permissionDecisions(owner, run, label);
        }
        return report;
    }

    // ---------------------------------------------------------------- identity of what was judged

    private void describe() {
        List<Map<String, Object>> oracles = new ArrayList<>();
        for (int i = 0; i < streams.size(); i++) {
            ClaudeStreamOracle s = streams.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("invocation", i);
            m.put("path", s.file().toString());
            m.put("sha256", s.sha256());
            m.put("lines", s.lines().size());
            m.put("delivered", s.deliveredCount());
            m.put("dropped", new TreeMap<>(s.droppedByType()));
            m.put("resultLine", s.hasResultLine());
            List<String> owners = new ArrayList<>();
            for (Owner o : s.owners()) {
                owners.add(o.label());
            }
            m.put("owners", owners);
            oracles.add(m);
        }
        report.oracle("streams", oracles);
        Run root = candidate.root();
        report.candidate("rootRunDir", root.dir.toString());
        report.candidate("runId", root.id());
        report.candidate("header", root.header == null ? null : plain(root.header));
        Json capture = root.runJson.path("summary", "capture");
        Map<String, Object> declared = new LinkedHashMap<>();
        if (capture != null) {
            for (String key : List.of("cli", "cliVersion", "parserInput", "finalization", "acquisition",
                    "preservation", "level1Omissions", "invocations")) {
                Json v = capture.path(key);
                if (v != null) {
                    declared.put(key, plain(v));
                }
            }
            List<Object> sources = new ArrayList<>();
            Json declaredSources = capture.path("sources");
            for (Json source : declaredSources == null ? List.<Json>of() : declaredSources.elements()) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String key : List.of("kind", "acquisition", "preservation", "status", "level1", "knownGaps",
                        "sessions")) {
                    Json v = source.path(key);
                    if (v != null) {
                        m.put(key, plain(v));
                    }
                }
                sources.add(m);
            }
            declared.put("sources", sources);
        }
        report.candidate("declared", declared);
        report.candidate("runStatus", ClaudeStreamOracle.textOf(root.runJson, "status"));
        List<Map<String, Object>> children = new ArrayList<>();
        for (Run child : candidate.children()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", child.id());
            m.put("spawnToolUseId", child.spawnToolUseId());
            m.put("parentRunId", child.parentRunId());
            children.add(m);
        }
        report.candidate("childRuns", children);
        report.candidate("filesRead", candidate.filesRead());
        for (ClaudeStreamOracle s : streams) {
            for (Owner o : s.owners()) {
                Map<String, Object> counts = new LinkedHashMap<>();
                counts.put("assistantLines", o.assistantLines.size());
                counts.put("userLines", o.userLines.size());
                counts.put("textBlocks", o.blocks.stream().filter(b -> "text".equals(b.kind())).count());
                counts.put("thinkingBlocks", o.blocks.stream().filter(b -> "thinking".equals(b.kind())).count());
                counts.put("toolUses", o.toolUses.size());
                counts.put("toolResults", o.toolResults.size());
                counts.put("permissionDecisions", o.permissionDecisions.size());
                report.denominator(o.label(), counts);
            }
            Map<String, Object> other = new LinkedHashMap<>();
            Map<String, Integer> byType = new TreeMap<>();
            for (Line line : s.otherDeliveredLines()) {
                byType.merge(line.type() + (line.subtype() == null ? "" : "/" + line.subtype()), 1, Integer::sum);
            }
            other.put("deliveredLines", byType);
            other.put("droppedLines", new TreeMap<>(s.droppedByType()));
            report.denominator("otherLines", other);
        }
    }

    /** A {@link Json} value as plain maps, lists and scalars, for the report (numbers by their spelling as text). */
    static Object plain(Json json) {
        if (json instanceof Json.JObject o) {
            Map<String, Object> m = new LinkedHashMap<>();
            o.members().forEach((k, v) -> m.put(k, plain(v)));
            return m;
        }
        if (json instanceof Json.JArray a) {
            List<Object> l = new ArrayList<>();
            a.elements().forEach(v -> l.add(plain(v)));
            return l;
        }
        if (json instanceof Json.JNumber n) {
            return n.value();
        }
        if (json instanceof Json.JBool b) {
            return b.value();
        }
        return json == null || json.isNull() ? null : json.text();
    }

    private void schemaVersion() {
        Run root = candidate.root();
        Json version = root.header == null ? null : root.header.path("schemaVersion");
        if (!(version instanceof Json.JNumber n) || n.value().intValueExact() != ClaudeStreamContract.SUPPORTED_SCHEMA_VERSION) {
            report.add(Category.COMPLETENESS, "header.schema_version", "main", Outcome.UNSUPPORTED,
                    "events.jsonl header schemaVersion " + (version == null ? "absent" : version.scalarText())
                            + "; this verifier implements " + ClaudeStreamContract.SUPPORTED_SCHEMA_VERSION);
            throw new IllegalStateException("refusing a candidate whose events schema version is not "
                    + ClaudeStreamContract.SUPPORTED_SCHEMA_VERSION + ": " + (version == null ? "absent" : version.scalarText()));
        }
        for (Run child : candidate.children()) {
            Json v = child.header == null ? null : child.header.path("schemaVersion");
            if (!(v instanceof Json.JNumber m) || m.value().intValueExact() != ClaudeStreamContract.SUPPORTED_SCHEMA_VERSION) {
                throw new IllegalStateException("refusing child run " + child.id() + ": events schema version "
                        + (v == null ? "absent" : v.scalarText()));
            }
        }
        report.add(Category.COMPLETENESS, "header.schema_version", "main", Outcome.VERIFIED,
                "schemaVersion " + ClaudeStreamContract.SUPPORTED_SCHEMA_VERSION + " on the root and "
                        + candidate.children().size() + " child run(s)");
    }

    // ---------------------------------------------------------------- owners and children

    /** Maps every oracle owner to its candidate run; reports the linkage. A missing run maps to {@code null}. */
    private Map<Owner, Run> linkOwners() {
        Map<Owner, Run> runs = new LinkedHashMap<>();
        Run root = candidate.root();
        List<String> problems = new ArrayList<>();
        Set<Run> used = new LinkedHashSet<>();
        int expectedChildren = 0;
        for (ClaudeStreamOracle s : streams) {
            for (Owner owner : s.owners()) {
                if (owner.spawnToolUseId == null) {
                    runs.putIfAbsent(owner, root);
                    continue;
                }
                expectedChildren++;
                List<Run> matching = new ArrayList<>();
                for (Run child : candidate.children()) {
                    if (owner.spawnToolUseId.equals(child.spawnToolUseId())) {
                        matching.add(child);
                    }
                }
                if (matching.size() != 1) {
                    problems.add(owner.label() + ": " + matching.size() + " child runs");
                    runs.put(owner, null);
                    continue;
                }
                Run child = matching.get(0);
                used.add(child);
                // the expected parent is the run of the owner whose line issued the spawning tool use
                Run expectedParent = root;
                for (Owner spawner : s.owners()) {
                    if (s.toolUseIdsOf(spawner).contains(owner.spawnToolUseId) && spawner.spawnToolUseId != null) {
                        for (Run r : candidate.children()) {
                            if (spawner.spawnToolUseId.equals(r.spawnToolUseId())) {
                                expectedParent = r;
                            }
                        }
                    }
                }
                if (!Objects.equals(child.parentRunId(), expectedParent.id())) {
                    problems.add(owner.label() + ": parentRunId " + child.parentRunId() + ", expected " + expectedParent.id());
                }
                runs.put(owner, child);
            }
        }
        for (Run child : candidate.children()) {
            if (!used.contains(child)) {
                problems.add("child run " + child.id() + " (spawn " + child.spawnToolUseId() + ") has no owner in the oracle");
            }
        }
        if (expectedChildren == 0 && candidate.children().isEmpty()) {
            report.add(Category.COMPLETENESS, "children.linked", "main", Outcome.NOT_APPLICABLE, "no sub-agent in the oracle");
        }
        else if (problems.isEmpty()) {
            report.add(Category.COMPLETENESS, "children.linked", "main", Outcome.VERIFIED,
                    expectedChildren + " sub-agent owner(s), each with exactly one child run linked to its parent");
        }
        else {
            report.add(Category.COMPLETENESS, "children.linked", "main", Outcome.DEFECTIVE, String.join("; ", problems));
        }
        // the parent's LLM call lists each child run
        if (expectedChildren > 0) {
            List<String> missing = new ArrayList<>();
            List<Event> calls = root.ofType("llm_call");
            Json subagents = calls.isEmpty() ? null : calls.get(0).json().path("metadata", "subagents");
            for (Map.Entry<Owner, Run> e : runs.entrySet()) {
                if (e.getKey().spawnToolUseId == null || e.getValue() == null) {
                    continue;
                }
                boolean listed = false;
                for (Json entry : subagents == null ? List.<Json>of() : subagents.elements()) {
                    if (e.getKey().spawnToolUseId.equals(ClaudeStreamOracle.textOf(entry, "spawnToolUseId"))
                            && e.getValue().id().equals(ClaudeStreamOracle.textOf(entry, "runId"))) {
                        listed = true;
                    }
                }
                if (!listed) {
                    missing.add(e.getKey().label());
                }
            }
            report.add(Category.COMPLETENESS, "children.listed_on_parent", "main",
                    missing.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                    missing.isEmpty() ? "llm_call.metadata.subagents names every child run" : "not listed: " + missing);
        }
        return runs;
    }

    // ---------------------------------------------------------------- completeness: the declared capture

    private void capture() {
        Run root = candidate.root();
        Json capture = root.runJson.path("summary", "capture");
        if (capture == null) {
            report.add(Category.COMPLETENESS, "capture.invocations", "main", Outcome.DEFECTIVE, "run.json has no summary.capture");
            report.add(Category.COMPLETENESS, "capture.statuses", "main", Outcome.DEFECTIVE, "run.json has no summary.capture");
            report.add(Category.COMPLETENESS, "capture.known_gaps", "main", Outcome.DEFECTIVE, "run.json has no summary.capture");
            return;
        }
        // invocations: one entry per stream, with the stream's own counts
        List<String> problems = new ArrayList<>();
        Json invocations = capture.path("invocations");
        List<Json> entries = invocations == null ? List.of() : invocations.elements();
        if (entries.size() != streams.size()) {
            problems.add("invocations " + entries.size() + ", streams " + streams.size());
        }
        boolean allComplete = true;
        for (int i = 0; i < Math.min(entries.size(), streams.size()); i++) {
            ClaudeStreamOracle s = streams.get(i);
            Json entry = entries.get(i);
            long sourceLines = s.lines().stream().filter(l -> l.delivered()
                    && ClaudeStreamContract.SOURCE_RECORD_TYPES.contains(l.type())).count();
            expectNumber(entry, "index", i, problems, i);
            expectNumber(entry, "sourceRecords", sourceLines, problems, i);
            expectNumber(entry, "vendorEvents", s.otherDeliveredLines().size(), problems, i);
            Json complete = entry.path("streamComplete");
            if (!(complete instanceof Json.JBool b) || b.value() != s.hasResultLine()) {
                problems.add("invocation " + i + " streamComplete " + (complete == null ? "absent" : complete.scalarText())
                        + ", stream has result line: " + s.hasResultLine());
            }
            allComplete &= s.hasResultLine();
            String sessionId = null;
            String cliVersion = null;
            for (Line line : s.lines()) {
                if (sessionId == null) {
                    sessionId = ClaudeStreamOracle.textOf(line.json(), "session_id");
                }
                if (cliVersion == null && "init".equals(line.subtype())) {
                    cliVersion = ClaudeStreamOracle.textOf(line.json(), "claude_code_version");
                }
            }
            if (sessionId != null && !sessionId.equals(ClaudeStreamOracle.textOf(entry, "sessionId"))) {
                problems.add("invocation " + i + " sessionId differs from the stream's session_id");
            }
            if (cliVersion != null && !cliVersion.equals(ClaudeStreamOracle.textOf(entry, "cliVersion"))) {
                problems.add("invocation " + i + " cliVersion differs from the init line's claude_code_version");
            }
        }
        report.add(Category.COMPLETENESS, "capture.invocations", "main",
                problems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                problems.isEmpty() ? entries.size() + " invocation(s) inventoried with the streams' own counts" : String.join("; ", problems));

        // statuses: allowed values, and consistent with the run and the streams
        List<String> status = new ArrayList<>();
        String finalization = ClaudeStreamOracle.textOf(capture, "finalization");
        String acquisition = ClaudeStreamOracle.textOf(capture, "acquisition");
        String preservation = ClaudeStreamOracle.textOf(capture, "preservation");
        if (finalization == null || !ClaudeStreamContract.FINALIZATION_VALUES.contains(finalization)) {
            status.add("finalization " + finalization);
        }
        else {
            String runStatus = ClaudeStreamOracle.textOf(root.runJson, "status");
            if ("FINISHED".equals(runStatus) && !"finished".equals(finalization)
                    || "FAILED".equals(runStatus) && !"failed".equals(finalization)) {
                status.add("finalization " + finalization + " with run status " + runStatus);
            }
        }
        if (acquisition == null || !ClaudeStreamContract.ACQUISITION_VALUES.contains(acquisition)) {
            status.add("acquisition " + acquisition);
        }
        else if (entries.size() == streams.size() && !acquisition.equals(allComplete ? "complete" : "incomplete")) {
            status.add("acquisition " + acquisition + " but every stream complete: " + allComplete);
        }
        Json omissions = capture.path("level1Omissions");
        if (preservation == null || !ClaudeStreamContract.PRESERVATION_VALUES.contains(preservation)) {
            status.add("preservation " + preservation);
        }
        else if ("partial".equals(preservation) && (omissions == null || omissions.elements().isEmpty())) {
            status.add("preservation partial without level1Omissions");
        }
        else if ("complete".equals(preservation) && omissions != null && !omissions.elements().isEmpty()) {
            status.add("preservation complete with level1Omissions");
        }
        report.add(Category.COMPLETENESS, "capture.statuses", "main",
                status.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                status.isEmpty() ? "finalization " + finalization + ", acquisition " + acquisition + ", preservation "
                        + preservation + " (" + (omissions == null ? 0 : omissions.elements().size()) + " declared omission(s))"
                        : String.join("; ", status));

        // known gaps: every dropped line type observed in a stream is declared on the live-stream source
        Map<String, Integer> dropped = new TreeMap<>();
        for (ClaudeStreamOracle s : streams) {
            s.droppedByType().forEach((t, n) -> dropped.merge(t, n, Integer::sum));
        }
        if (dropped.isEmpty()) {
            report.add(Category.COMPLETENESS, "capture.known_gaps", "main", Outcome.NOT_APPLICABLE, "no dropped line in the streams");
            return;
        }
        List<String> undeclared = new ArrayList<>();
        for (String type : dropped.keySet()) {
            if (!declaresGap(capture, type)) {
                undeclared.add(type);
            }
        }
        report.add(Category.COMPLETENESS, "capture.known_gaps", "main",
                undeclared.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                undeclared.isEmpty() ? "declared: " + dropped : "dropped but not declared as a known gap: " + undeclared);
    }

    private static boolean declaresGap(Json capture, String type) {
        Json sources = capture.path("sources");
        for (Json source : sources == null ? List.<Json>of() : sources.elements()) {
            if (!"live-stream".equals(ClaudeStreamOracle.textOf(source, "kind"))) {
                continue;
            }
            Json gaps = source.path("knownGaps");
            for (Json gap : gaps == null ? List.<Json>of() : gaps.elements()) {
                if (gap.text() != null && gap.text().contains(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void expectNumber(Json entry, String key, long expected, List<String> problems, int invocation) {
        Json v = entry.path(key);
        if (!(v instanceof Json.JNumber n) || n.value().compareTo(java.math.BigDecimal.valueOf(expected)) != 0) {
            problems.add("invocation " + invocation + " " + key + " " + (v == null ? "absent" : v.scalarText()) + ", expected " + expected);
        }
    }

    // ---------------------------------------------------------------- preservation: records kept whole

    /** A candidate record resolved to its oracle line through the reference it carries. */
    private record Resolved(Event event, ClaudeStreamOracle stream, Line line, boolean invocationValid, String key) {
    }

    private Resolved resolve(Event event) {
        Integer invocation = event.attributeInt("invocation");
        Integer messageNumber = event.attributeInt("messageNumber");
        boolean valid = invocation != null && invocation >= 0 && invocation < streams.size();
        ClaudeStreamOracle stream = valid ? streams.get(invocation) : streams.size() == 1 ? streams.get(0) : null;
        Line line = stream == null || messageNumber == null ? null : stream.line(messageNumber);
        String key = (valid ? invocation : (streams.size() == 1 ? 0 : -1)) + ":" + messageNumber;
        return new Resolved(event, stream, line, valid, key);
    }

    private void sourceRecords(Owner owner, Run run, String label) {
        List<Event> records = run.custom(ClaudeStreamContract.EV_SOURCE_RECORD);
        Map<String, Integer> expected = new TreeMap<>();
        for (int i = 0; i < streams.size(); i++) {
            for (Owner o : streams.get(i).owners()) {
                if (Objects.equals(o.spawnToolUseId, owner.spawnToolUseId)) {
                    for (Line line : o.lines) {
                        expected.merge(i + ":" + line.messageNumber(), 1, Integer::sum);
                    }
                }
            }
        }
        Map<String, Integer> actual = new TreeMap<>();
        List<Resolved> resolved = new ArrayList<>();
        int invalidInvocation = 0;
        for (Event e : records) {
            Resolved r = resolve(e);
            resolved.add(r);
            actual.merge(r.key(), 1, Integer::sum);
            if (!r.invocationValid()) {
                invalidInvocation++;
            }
        }
        if (expected.isEmpty() && records.isEmpty()) {
            report.add(Category.PRESERVATION, "source_records.multiplicity", label, Outcome.NOT_APPLICABLE, "no line of this owner");
            return;
        }
        multiplicity(Category.PRESERVATION, "source_records.multiplicity", label, expected, actual, "occurrence (invocation:messageNumber)");
        report.add(Category.PRESERVATION, "source_records.invocation_reference", label,
                invalidInvocation == 0 ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                invalidInvocation == 0 ? records.size() + " record(s) reference a recorded invocation"
                        : invalidInvocation + " of " + records.size() + " record(s) reference an invocation outside 0.." + (streams.size() - 1)
                                + (streams.size() == 1 ? " (resolved by messageNumber alone for the payload checks)" : ""));
        payloads(resolved, owner, label, "source_records", true);
    }

    private void vendorEvents(Run root) {
        List<Event> events = root.custom(ClaudeStreamContract.EV_VENDOR_EVENT);
        Map<String, Integer> expected = new TreeMap<>();
        for (int i = 0; i < streams.size(); i++) {
            for (Line line : streams.get(i).otherDeliveredLines()) {
                expected.merge(i + ":" + line.messageNumber(), 1, Integer::sum);
            }
        }
        Map<String, Integer> actual = new TreeMap<>();
        List<Resolved> resolved = new ArrayList<>();
        int invalidInvocation = 0;
        for (Event e : events) {
            Resolved r = resolve(e);
            resolved.add(r);
            actual.merge(r.key(), 1, Integer::sum);
            if (!r.invocationValid()) {
                invalidInvocation++;
            }
        }
        if (expected.isEmpty() && events.isEmpty()) {
            report.add(Category.PRESERVATION, "vendor_events.multiplicity", "main", Outcome.NOT_APPLICABLE, "no other delivered line");
            return;
        }
        multiplicity(Category.PRESERVATION, "vendor_events.multiplicity", "main", expected, actual, "occurrence (invocation:messageNumber)");
        report.add(Category.PRESERVATION, "vendor_events.invocation_reference", "main",
                invalidInvocation == 0 ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                invalidInvocation == 0 ? events.size() + " record(s) reference a recorded invocation"
                        : invalidInvocation + " of " + events.size() + " record(s) reference an invocation outside 0.." + (streams.size() - 1));
        payloads(resolved, null, "main", "vendor_events", false);
    }

    /**
     * Ownership, type, payload value and payload text of resolved whole records. The owner check is skipped for
     * vendor events (system and result lines have no owner).
     */
    private void payloads(List<Resolved> resolved, Owner owner, String label, String family, boolean checkOwner) {
        int unresolved = 0;
        List<String> ownership = new ArrayList<>();
        List<String> type = new ArrayList<>();
        List<String> valueDefects = new ArrayList<>();
        int unparsable = 0;
        int textEqual = 0;
        int spellingOnly = 0;
        int textDiffers = 0;
        for (Resolved r : resolved) {
            Event e = r.event();
            if (r.line() == null) {
                unresolved++;
                continue;
            }
            if (checkOwner) {
                String parent = e.attributeText("parentToolUseId");
                if (!Objects.equals(r.line().owner(), owner.spawnToolUseId) || !Objects.equals(parent, owner.spawnToolUseId)) {
                    ownership.add("events.jsonl:" + e.lineNumber() + " -> stream line " + r.line().lineNumber()
                            + " owned by " + (r.line().owner() == null ? "main" : "child:" + r.line().owner()));
                }
            }
            if (!Objects.equals(e.attributeText("type"), r.line().type())
                    || (!checkOwner && !Objects.equals(e.attributeText("subtype"), r.line().subtype()))) {
                type.add("events.jsonl:" + e.lineNumber() + " type " + e.attributeText("type") + ", stream line "
                        + r.line().lineNumber() + " is " + r.line().type());
            }
            String raw = e.attributeText("rawJson");
            if (raw == null) {
                valueDefects.add("events.jsonl:" + e.lineNumber() + " has no rawJson");
                continue;
            }
            Json payload;
            try {
                payload = NeutralJson.parse(raw);
            }
            catch (IllegalArgumentException notJson) {
                unparsable++;
                continue;
            }
            List<Difference> differences = Json.differences(r.line().json(), payload);
            List<Difference> valueDifferences = differences.stream().filter(d -> d.kind() != Difference.Kind.REPRESENTATION).toList();
            if (!valueDifferences.isEmpty()) {
                valueDefects.add("events.jsonl:" + e.lineNumber() + " vs stream line " + r.line().lineNumber() + ": "
                        + valueDifferences.get(0) + (valueDifferences.size() > 1 ? " (+" + (valueDifferences.size() - 1) + ")" : ""));
            }
            if (raw.equals(r.line().text())) {
                textEqual++;
            }
            else if (valueDifferences.isEmpty()) {
                spellingOnly++;
            }
            else {
                textDiffers++;
            }
        }
        int n = resolved.size();
        if (checkOwner) {
            report.add(Category.PRESERVATION, family + ".ownership", label,
                    ownership.isEmpty() ? (unresolved == n ? Outcome.UNRESOLVED : Outcome.VERIFIED) : Outcome.DEFECTIVE,
                    ownership.isEmpty() ? (n - unresolved) + " record(s) resolve to lines of this owner" : String.join("; ", ownership));
        }
        report.add(Category.PRESERVATION, family + ".type", label,
                type.isEmpty() ? (unresolved == n ? Outcome.UNRESOLVED : Outcome.VERIFIED) : Outcome.DEFECTIVE,
                type.isEmpty() ? "record types match the resolved lines" : String.join("; ", type));
        Outcome value;
        String valueDetail;
        if (!valueDefects.isEmpty()) {
            value = Outcome.DEFECTIVE;
            valueDetail = valueDefects.size() + " payload(s) differ in value: " + String.join("; ", valueDefects);
        }
        else if (unresolved > 0 || unparsable > 0) {
            value = Outcome.UNRESOLVED;
            valueDetail = unresolved + " record(s) resolve to no delivered line, " + unparsable + " rawJson not parseable";
        }
        else {
            value = Outcome.VERIFIED;
            valueDetail = n + " payload(s) equal in value to the stream line at their occurrence";
        }
        report.add(Category.PRESERVATION, family + ".payload_value", label, value, valueDetail);
        Outcome text;
        if (textDiffers > 0 || !valueDefects.isEmpty()) {
            text = Outcome.DEFECTIVE;
        }
        else if (unresolved > 0 || unparsable > 0) {
            text = Outcome.UNRESOLVED;
        }
        else if (spellingOnly > 0) {
            text = Outcome.REPRESENTATION_DIFFERS;
        }
        else {
            text = Outcome.VERIFIED;
        }
        report.add(Category.PRESERVATION, family + ".payload_text", label, text,
                textEqual + " byte-identical, " + spellingOnly + " equal in value with different spelling, " + textDiffers + " different");
    }

    private void droppedLines() {
        Json capture = candidate.root().runJson.path("summary", "capture");
        Map<String, Integer> dropped = new TreeMap<>();
        for (ClaudeStreamOracle s : streams) {
            s.droppedByType().forEach((t, n) -> dropped.merge(t, n, Integer::sum));
        }
        for (Map.Entry<String, Integer> e : dropped.entrySet()) {
            boolean declared = capture != null && declaresGap(capture, e.getKey());
            report.add(Category.PRESERVATION, "dropped_lines." + e.getKey(), "main",
                    declared ? Outcome.UNSUPPORTED : Outcome.DEFECTIVE,
                    e.getValue() + " line(s) of type " + e.getKey() + (declared
                            ? " are not in level 1; declared as a known gap of the live stream (not verified, not lost silently)"
                            : " are not in level 1 and not declared"));
        }
    }

    /** Multiset comparison by key; a duplicate on either side is visible in the detail. */
    private void multiplicity(Category category, String property, String label, Map<String, Integer> expected,
            Map<String, Integer> actual, String what) {
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        List<String> duplicated = new ArrayList<>();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            int have = actual.getOrDefault(e.getKey(), 0);
            if (have == 0) {
                missing.add(e.getKey());
            }
            else if (have > e.getValue()) {
                duplicated.add(e.getKey() + " x" + have);
            }
            else if (have < e.getValue()) {
                missing.add(e.getKey() + " (" + have + " of " + e.getValue() + ")");
            }
        }
        for (String key : actual.keySet()) {
            if (!expected.containsKey(key)) {
                extra.add(key);
            }
        }
        boolean ok = missing.isEmpty() && extra.isEmpty() && duplicated.isEmpty();
        report.add(category, property, label, ok ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                ok ? expected.values().stream().mapToInt(Integer::intValue).sum() + " " + what + "(s), each exactly once"
                        : "missing " + missing + ", extra " + extra + ", duplicated " + duplicated);
    }

    // ---------------------------------------------------------------- normalization: tool calls

    private void toolCalls(Owner owner, Run run, String label) {
        List<Event> calls = run.ofType("tool_call");
        List<ToolUse> uses = owner.toolUses;
        if (uses.isEmpty() && calls.isEmpty()) {
            report.add(Category.NORMALIZATION, "tool_calls.multiplicity", label, Outcome.NOT_APPLICABLE, "no tool use of this owner");
            return;
        }
        // identities: an id that repeats in the oracle is unresolvable here
        Map<String, Integer> expected = new TreeMap<>();
        for (ToolUse use : uses) {
            expected.merge(String.valueOf(use.id()), 1, Integer::sum);
        }
        Map<String, Integer> actual = new TreeMap<>();
        for (Event call : calls) {
            actual.merge(String.valueOf(ClaudeStreamOracle.textOf(call.json(), "id")), 1, Integer::sum);
        }
        List<String> repeated = expected.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
        if (!repeated.isEmpty()) {
            report.add(Category.NORMALIZATION, "tool_calls.multiplicity", label, Outcome.UNRESOLVED,
                    "the oracle repeats tool-use id(s) " + repeated + "; identity is unresolvable without the contract's occurrence scoping");
            return;
        }
        multiplicity(Category.NORMALIZATION, "tool_calls.multiplicity", label, expected, actual, "tool call");
        boolean multiplicityOk = report.outcome("tool_calls.multiplicity", label) == Outcome.VERIFIED;

        // ownership: a call on this run whose id belongs to another owner in the oracle
        List<String> foreign = new ArrayList<>();
        for (Event call : calls) {
            String id = ClaudeStreamOracle.textOf(call.json(), "id");
            for (ClaudeStreamOracle s : streams) {
                for (Owner other : s.owners()) {
                    if (!Objects.equals(other.spawnToolUseId, owner.spawnToolUseId) && s.toolUseIdsOf(other).contains(id)) {
                        foreign.add(id + " belongs to " + other.label());
                    }
                }
            }
        }
        report.add(Category.NORMALIZATION, "tool_calls.ownership", label,
                foreign.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                foreign.isEmpty() ? calls.size() + " call(s) on this run, none owned elsewhere" : String.join("; ", foreign));

        // order, only once multiplicity holds
        if (multiplicityOk) {
            List<String> expectedOrder = uses.stream().map(ToolUse::id).toList();
            List<String> actualOrder = calls.stream().map(c -> ClaudeStreamOracle.textOf(c.json(), "id")).toList();
            report.add(Category.NORMALIZATION, "tool_calls.order", label,
                    expectedOrder.equals(actualOrder) ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                    expectedOrder.equals(actualOrder) ? "same sequence as the tool uses" : "sequence differs from the tool uses");
        }
        else {
            report.add(Category.NORMALIZATION, "tool_calls.order", label, Outcome.NOT_APPLICABLE, "multiplicity not verified");
        }

        // per matched id (exactly once on both sides)
        Map<String, Event> callById = new LinkedHashMap<>();
        for (Event call : calls) {
            String id = ClaudeStreamOracle.textOf(call.json(), "id");
            if (actual.get(id) == 1) {
                callById.put(id, call);
            }
        }
        List<String> names = new ArrayList<>();
        List<String> inputValues = new ArrayList<>();
        List<String> inputSpelling = new ArrayList<>();
        boolean anyNumberInput = false;
        List<String> stringResults = new ArrayList<>();
        int stringResultCount = 0;
        List<String> structured = new ArrayList<>();
        int structuredCount = 0;
        List<String> success = new ArrayList<>();
        int withResult = 0;
        List<String> absent = new ArrayList<>();
        int absentCount = 0;
        List<String> turns = new ArrayList<>();
        List<String> unresolvedResults = new ArrayList<>();
        Map<String, ToolResult> resultById = new LinkedHashMap<>();
        Map<String, Integer> resultCount = new LinkedHashMap<>();
        for (ToolResult r : owner.toolResults) {
            resultById.putIfAbsent(r.toolUseId(), r);
            resultCount.merge(r.toolUseId(), 1, Integer::sum);
        }
        for (ToolUse use : uses) {
            Event call = callById.get(use.id());
            if (call == null) {
                continue; // counted by multiplicity
            }
            if (!Objects.equals(use.name(), ClaudeStreamOracle.textOf(call.json(), "toolName"))) {
                names.add(use.id());
            }
            Json input = call.json().path("input");
            List<Difference> differences = Json.differences(use.input(), input);
            List<Difference> value = differences.stream().filter(d -> d.kind() != Difference.Kind.REPRESENTATION).toList();
            if (!value.isEmpty()) {
                inputValues.add(use.id() + ": " + value.get(0) + (value.size() > 1 ? " (+" + (value.size() - 1) + ")" : ""));
            }
            if (use.input() != null && use.input().containsNumber()) {
                anyNumberInput = true;
            }
            for (Difference d : differences) {
                if (d.kind() == Difference.Kind.REPRESENTATION) {
                    inputSpelling.add(use.id() + " " + d.path() + ": " + d.detail());
                }
            }
            if (!Objects.equals(use.messageId(), ClaudeStreamOracle.textOf(call.json(), "turnId"))) {
                turns.add(use.id());
            }
            Integer results = resultCount.get(use.id());
            if (results != null && results > 1) {
                unresolvedResults.add(use.id() + " has " + results + " results");
                continue;
            }
            ToolResult result = resultById.get(use.id());
            Json output = call.json().path("output");
            Json error = call.json().path("errorMessage");
            if (result == null) {
                absentCount++;
                if (!(output == null || output.isNull()) || !(error == null || error.isNull())) {
                    absent.add(use.id());
                }
                continue;
            }
            withResult++;
            Json successFlag = call.json().path("success");
            if (!(successFlag instanceof Json.JBool b) || b.value() == result.isError()) {
                success.add(use.id());
            }
            Json content = result.content();
            if (content == null || content.isNull() || content instanceof Json.JString) {
                stringResultCount++;
                String expectedText = content == null || content.isNull() ? null : content.text();
                String outputText = output == null || output.isNull() ? null : output.text();
                String errorText = error == null || error.isNull() ? null : error.text();
                boolean ok = Objects.equals(expectedText, outputText)
                        && Objects.equals(result.isError() ? expectedText : null, errorText);
                if (!ok) {
                    stringResults.add(use.id());
                }
            }
            else {
                structuredCount++;
                if (output == null || output.isNull()) {
                    structured.add(use.id() + ": output absent");
                }
                else if (output instanceof Json.JString) {
                    structured.add(use.id() + ": " + Json.kindOf(content) + " content stored as a string (R13-04)");
                }
                else {
                    List<Difference> d = Json.differences(content, output).stream()
                            .filter(x -> x.kind() != Difference.Kind.REPRESENTATION).toList();
                    if (!d.isEmpty()) {
                        structured.add(use.id() + ": " + d.get(0));
                    }
                }
            }
        }
        int matched = callById.size();
        report.add(Category.NORMALIZATION, "tool_calls.name", label,
                names.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                names.isEmpty() ? matched + " name(s) equal" : "differ: " + names);
        report.add(Category.NORMALIZATION, "tool_calls.input_value", label,
                inputValues.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                inputValues.isEmpty() ? matched + " input(s) equal in exact value, kind and structure" : String.join("; ", inputValues));
        report.add(Category.NORMALIZATION, "tool_calls.input_representation", label,
                !anyNumberInput ? Outcome.NOT_APPLICABLE : inputSpelling.isEmpty() ? Outcome.VERIFIED : Outcome.REPRESENTATION_DIFFERS,
                !anyNumberInput ? "no number in any input" : inputSpelling.isEmpty() ? "every number spelled as in the source"
                        : inputSpelling.size() + " number(s) equal in value, spelled differently (R13-09): " + String.join("; ", inputSpelling));
        report.add(Category.NORMALIZATION, "tool_calls.result_text", label,
                stringResultCount == 0 ? Outcome.NOT_APPLICABLE : stringResults.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                stringResultCount == 0 ? "no string result" : stringResults.isEmpty()
                        ? stringResultCount + " string result(s) equal, with errorMessage per is_error" : "differ: " + stringResults);
        report.add(Category.NORMALIZATION, "tool_calls.result_structured", label,
                structuredCount == 0 ? Outcome.NOT_APPLICABLE : structured.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                structuredCount == 0 ? "no structured result" : structured.isEmpty()
                        ? structuredCount + " structured result(s) equal in value and structure" : String.join("; ", structured));
        report.add(Category.NORMALIZATION, "tool_calls.success_flag", label,
                withResult == 0 ? Outcome.NOT_APPLICABLE : success.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                withResult == 0 ? "no result" : success.isEmpty() ? withResult + " success flag(s) agree with is_error" : "differ: " + success);
        report.add(Category.NORMALIZATION, "tool_calls.result_absent", label,
                absentCount == 0 ? Outcome.NOT_APPLICABLE : absent.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                absentCount == 0 ? "every tool use has a result" : absent.isEmpty()
                        ? absentCount + " tool use(s) without a result have no output and no error" : "output present without a result: " + absent);
        if (!unresolvedResults.isEmpty()) {
            report.add(Category.NORMALIZATION, "tool_calls.result_identity", label, Outcome.UNRESOLVED, String.join("; ", unresolvedResults));
        }
        report.add(Category.EXPLANATION, "tool_calls.turn_reference", label,
                turns.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                turns.isEmpty() ? matched + " turnId(s) are the id of the message that carried the tool use" : "differ: " + turns);
        toolTiming(owner, run, label, callById, resultById, resultCount);
    }

    private void toolTiming(Owner owner, Run run, String label, Map<String, Event> callById,
            Map<String, ToolResult> resultById, Map<String, Integer> resultCount) {
        Map<String, ToolUse> useById = new LinkedHashMap<>();
        for (ToolUse use : owner.toolUses) {
            useById.putIfAbsent(use.id(), use);
        }
        List<Event> timings = run.custom(ClaudeStreamContract.EV_TOOL_TIMING);
        Map<String, Event> timingById = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (Event t : timings) {
            String id = t.attributeText("toolUseId");
            if (timingById.put(id, t) != null) {
                problems.add(id + ": more than one tool_timing");
            }
            if (!useById.containsKey(id)) {
                problems.add(id + ": tool_timing for an id the oracle does not have");
            }
        }
        int declared = 0;
        int undeclared = 0;
        List<String> valueProblems = new ArrayList<>();
        for (Map.Entry<String, Event> e : callById.entrySet()) {
            String id = e.getKey();
            ToolUse use = useById.get(id);
            if (resultCount.getOrDefault(id, 0) != 1) {
                continue; // no result, or unresolved identity: no duration to explain
            }
            ToolResult result = resultById.get(id);
            long expectedDuration = -1;
            if (use.timestamp() != null && result.timestamp() != null) {
                try {
                    expectedDuration = Duration.between(Instant.parse(use.timestamp()), Instant.parse(result.timestamp())).toMillis();
                }
                catch (DateTimeParseException notInstant) {
                    expectedDuration = -1;
                }
            }
            Json callDuration = e.getValue().json().path("durationMs");
            if (!(callDuration instanceof Json.JNumber n) || n.value().longValueExact() != expectedDuration) {
                valueProblems.add(id + ": tool_call.durationMs " + (callDuration == null ? "absent" : callDuration.scalarText())
                        + ", vendor timestamps give " + expectedDuration);
            }
            Event timing = timingById.get(id);
            if (timing == null) {
                undeclared++;
                continue;
            }
            declared++;
            String source = timing.attributeText("durationSource");
            Json duration = timing.attribute("durationMs");
            long declaredDuration = duration instanceof Json.JNumber d ? d.value().longValueExact() : Long.MIN_VALUE;
            if (ClaudeStreamContract.DURATION_FROM_VENDOR_TIMESTAMPS.equals(source)) {
                if (expectedDuration == -1) {
                    problems.add(id + ": declared vendor-timestamps but a timestamp is missing on the stream");
                }
                else if (!Objects.equals(timing.attributeText("issuedAt"), use.timestamp())
                        || !Objects.equals(timing.attributeText("resultAt"), result.timestamp())
                        || declaredDuration != expectedDuration) {
                    problems.add(id + ": issuedAt/resultAt/durationMs are not the stream's timestamps and their difference");
                }
            }
            else if (ClaudeStreamContract.DURATION_NOT_REPORTED.equals(source)) {
                if (declaredDuration != -1) {
                    problems.add(id + ": not-reported with durationMs " + declaredDuration);
                }
                else if (expectedDuration != -1) {
                    problems.add(id + ": not-reported although both lines carry timestamps");
                }
            }
            else {
                problems.add(id + ": unknown durationSource " + source);
            }
        }
        if (declared + undeclared == 0) {
            report.add(Category.EXPLANATION, "tool_calls.duration_value", label, Outcome.NOT_APPLICABLE, "no tool call with a result");
            report.add(Category.EXPLANATION, "tool_timing.declared", label, Outcome.NOT_APPLICABLE, "no tool call with a result");
            return;
        }
        report.add(Category.EXPLANATION, "tool_calls.duration_value", label,
                valueProblems.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                valueProblems.isEmpty() ? (declared + undeclared) + " duration(s) equal the stream's timestamp difference, or -1 without timestamps"
                        : String.join("; ", valueProblems));
        Outcome declaredOutcome = !problems.isEmpty() || undeclared > 0 ? Outcome.DEFECTIVE : Outcome.VERIFIED;
        report.add(Category.EXPLANATION, "tool_timing.declared", label, declaredOutcome,
                (problems.isEmpty() ? declared + " duration(s) declared with their source and the stream's own timestamps"
                        : String.join("; ", problems))
                        + (undeclared > 0 ? "; " + undeclared + " tool call(s) with a result have no tool_timing declaration (durationSource unstated)" : ""));
    }

    // ---------------------------------------------------------------- normalization: assistant blocks

    private void assistantBlocks(Owner owner, Run run, String label) {
        // text at position
        Map<String, Integer> expectedText = new TreeMap<>();
        Map<String, Integer> expectedThinking = new TreeMap<>();
        Map<Integer, List<String>> expectedKinds = new TreeMap<>();
        Map<String, String> expectedToolUseAt = new TreeMap<>();
        for (Line line : owner.assistantLines) {
            expectedKinds.put(line.messageNumber(), new ArrayList<>());
        }
        for (Block b : owner.blocks) {
            String position = b.messageNumber() + ":" + b.blockIndex();
            expectedKinds.get(b.messageNumber()).add(b.kind());
            if ("text".equals(b.kind())) {
                expectedText.merge(position + "=" + b.text(), 1, Integer::sum);
            }
            else if ("thinking".equals(b.kind())) {
                expectedThinking.merge(position + "=" + b.thinking() + "\u0000" + b.signature(), 1, Integer::sum);
            }
            else if ("tool_use".equals(b.kind())) {
                expectedToolUseAt.put(position, b.toolUseId());
            }
        }
        Map<String, Integer> actualText = new TreeMap<>();
        for (Event e : run.custom(ClaudeStreamContract.EV_ASSISTANT_TEXT)) {
            actualText.merge(e.attributeInt("messageNumber") + ":" + e.attributeInt("blockIndex") + "=" + e.attributeText("text"), 1, Integer::sum);
        }
        Map<String, Integer> actualThinking = new TreeMap<>();
        for (Event e : run.custom(ClaudeStreamContract.EV_THINKING_BLOCK)) {
            actualThinking.merge(e.attributeInt("messageNumber") + ":" + e.attributeInt("blockIndex") + "="
                    + e.attributeText("content") + "\u0000" + e.attributeText("signature"), 1, Integer::sum);
        }
        if (expectedText.isEmpty() && actualText.isEmpty()) {
            report.add(Category.NORMALIZATION, "assistant_text.value_at_position", label, Outcome.NOT_APPLICABLE, "no text block");
        }
        else {
            positional(Category.NORMALIZATION, "assistant_text.value_at_position", label, expectedText, actualText, "text block");
        }
        if (expectedThinking.isEmpty() && actualThinking.isEmpty()) {
            report.add(Category.NORMALIZATION, "thinking.value_at_position", label, Outcome.NOT_APPLICABLE, "no thinking block");
        }
        else {
            positional(Category.NORMALIZATION, "thinking.value_at_position", label, expectedThinking, actualThinking, "thinking block");
        }

        // kind sequence per line, and the tool-use reference of each tool_use block entry
        Map<Integer, List<String>> actualKinds = new TreeMap<>();
        Map<String, String> actualToolUseAt = new TreeMap<>();
        List<String> problems = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (Event e : run.custom(ClaudeStreamContract.EV_MESSAGE_BLOCKS)) {
            Integer mn = e.attributeInt("messageNumber");
            if (actualKinds.containsKey(mn)) {
                problems.add("message_blocks repeated for messageNumber " + mn);
            }
            List<String> kinds = new ArrayList<>();
            Json blocks = e.attribute("blocks");
            int i = 0;
            for (Json block : blocks == null ? List.<Json>of() : blocks.elements()) {
                Json index = block.path("index");
                if (!(index instanceof Json.JNumber n) || n.value().intValueExact() != i) {
                    problems.add("messageNumber " + mn + " block " + i + " carries index " + (index == null ? "absent" : index.scalarText()));
                }
                kinds.add(ClaudeStreamOracle.textOf(block, "kind"));
                String toolUseId = ClaudeStreamOracle.textOf(block, "toolUseId");
                if (toolUseId != null) {
                    actualToolUseAt.put(mn + ":" + i, toolUseId);
                }
                i++;
            }
            actualKinds.put(mn, kinds);
        }
        if (expectedKinds.isEmpty() && actualKinds.isEmpty()) {
            report.add(Category.NORMALIZATION, "message_blocks.kind_sequence_per_line", label, Outcome.NOT_APPLICABLE, "no assistant line");
        }
        else {
            for (Map.Entry<Integer, List<String>> e : expectedKinds.entrySet()) {
                List<String> have = actualKinds.get(e.getKey());
                if (have == null) {
                    problems.add("messageNumber " + e.getKey() + " has no message_blocks");
                }
                else if (!have.equals(e.getValue())) {
                    if (e.getValue().stream().allMatch(ClaudeStreamContract.KNOWN_BLOCK_KINDS::contains)) {
                        problems.add("messageNumber " + e.getKey() + " kinds " + have + ", expected " + e.getValue());
                    }
                    else {
                        unresolved.add("messageNumber " + e.getKey() + " has block kind(s) without a pinned name: " + e.getValue());
                    }
                }
            }
            for (Integer mn : actualKinds.keySet()) {
                if (!expectedKinds.containsKey(mn)) {
                    problems.add("message_blocks for messageNumber " + mn + ", which is not an assistant line of this owner");
                }
            }
            report.add(Category.NORMALIZATION, "message_blocks.kind_sequence_per_line", label,
                    !problems.isEmpty() ? Outcome.DEFECTIVE : !unresolved.isEmpty() ? Outcome.UNRESOLVED : Outcome.VERIFIED,
                    !problems.isEmpty() ? String.join("; ", problems) : !unresolved.isEmpty() ? String.join("; ", unresolved)
                            : expectedKinds.size() + " line(s) with the same block kinds in the same order");
        }
        if (expectedToolUseAt.isEmpty()) {
            report.add(Category.NORMALIZATION, "message_blocks.tool_use_reference", label, Outcome.NOT_APPLICABLE, "no tool_use block");
        }
        else {
            List<String> wrong = new ArrayList<>();
            for (Map.Entry<String, String> e : expectedToolUseAt.entrySet()) {
                if (!Objects.equals(actualToolUseAt.get(e.getKey()), e.getValue())) {
                    wrong.add("position " + e.getKey() + " expected " + e.getValue() + ", found " + actualToolUseAt.get(e.getKey()));
                }
            }
            for (Map.Entry<String, String> e : actualToolUseAt.entrySet()) {
                if (!expectedToolUseAt.containsKey(e.getKey())) {
                    wrong.add("position " + e.getKey() + " carries " + e.getValue() + " where the oracle has no tool_use");
                }
            }
            report.add(Category.NORMALIZATION, "message_blocks.tool_use_reference", label,
                    wrong.isEmpty() ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                    wrong.isEmpty() ? expectedToolUseAt.size() + " tool_use block(s) reference their id at their position" : String.join("; ", wrong));
        }
    }

    /** Multiset comparison of position-tagged values; the detail names positions, never content. */
    private void positional(Category category, String property, String label, Map<String, Integer> expected,
            Map<String, Integer> actual, String what) {
        List<String> missing = new ArrayList<>();
        List<String> extra = new ArrayList<>();
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            int have = actual.getOrDefault(e.getKey(), 0);
            if (have != e.getValue()) {
                missing.add(positionOf(e.getKey()) + (have == 0 ? "" : " x" + have + " of " + e.getValue()));
            }
        }
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            if (!expected.containsKey(e.getKey())) {
                extra.add(positionOf(e.getKey()));
            }
        }
        boolean ok = missing.isEmpty() && extra.isEmpty();
        report.add(category, property, label, ok ? Outcome.VERIFIED : Outcome.DEFECTIVE,
                ok ? expected.values().stream().mapToInt(Integer::intValue).sum() + " " + what + "(s) equal at their position"
                        : "value missing or different at position(s) " + missing + ", unexpected at " + extra);
    }

    private static String positionOf(String key) {
        int eq = key.indexOf('=');
        return eq < 0 ? key : key.substring(0, eq);
    }

    // ---------------------------------------------------------------- normalization: permission decisions

    private void permissionDecisions(Owner owner, Run run, String label) {
        Map<String, Integer> expected = new TreeMap<>();
        for (PermissionDecision d : owner.permissionDecisions) {
            expected.merge(d.messageNumber() + ":" + d.toolUseId() + ":" + d.decision() + ":" + d.source() + ":" + d.reasonType(), 1, Integer::sum);
        }
        Map<String, Integer> actual = new TreeMap<>();
        for (Event e : run.custom(ClaudeStreamContract.EV_PERMISSION_DECISION)) {
            actual.merge(e.attributeInt("messageNumber") + ":" + e.attributeText("toolUseId") + ":" + e.attributeText("decision")
                    + ":" + e.attributeText("source") + ":" + e.attributeText("reasonType"), 1, Integer::sum);
        }
        if (expected.isEmpty() && actual.isEmpty()) {
            report.add(Category.NORMALIZATION, "permission_decisions.multiset", label, Outcome.NOT_APPLICABLE, "no permission decision");
            return;
        }
        multiplicity(Category.NORMALIZATION, "permission_decisions.multiset", label, expected, actual,
                "decision (messageNumber:toolUseId:decision:source:reasonType)");
    }
}
