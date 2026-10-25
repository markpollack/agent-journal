package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.Event;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.OracleReport;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.ocsf.inventory.AssemblyOracle;
import io.github.markpollack.journal.tck.verify.Check;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.FIXTURES;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.LIVE_CLAUDE_PAIR;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.nativeOf;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.options;
import static io.github.markpollack.journal.ocsf.writer.WriterTestSupport.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final Level 1 assembly of Claude Code 2.1.304's stream and native session file (the sanitized live pair of
 * claude-mcp-1) under the experimental profile, judged by the independent {@link AssemblyOracle} from the native
 * bytes and a hand-written expectation, never by the writer's assembly. Controls: another execution's session file,
 * a missing required source, the same import replayed, identical bytes from another origin, an identity repeated within
 * one source, and files altered after assembly that the oracle must reject; the final file read alone; the inputs and the
 * original package unchanged.
 */
class FinalAssemblyClaudeTest {

    static final String PROFILE = "claude-code-2.1.304-stdout-session-file";
    static final String RUN_SESSION = "00000000-0000-4000-8000-0000000000a4";
    static final String OTHER_SESSION = "11111111-1111-4111-8111-111111111111";
    static final String STREAM_ORIGIN = "CliCapture stdout copy of the launch (sanitized)";
    static final String TRANSCRIPT_ORIGIN = "native session file archived after the launch ended (sanitized)";

    /** One input with the acquisition its supplier asserts (the fixture's own launch unless a test says otherwise) and its declared copy lineage. */
    record In(String artifact, String role, Path file, String origin, String acquisition, String copyOf) {

        In(String artifact, String role, Path file, String origin) {
            this(artifact, role, file, origin, WriterTestSupport.launch(LIVE_CLAUDE_PAIR), null);
        }
    }

    @Test
    void thePairAssemblesIntoOneCanonicalExecutionTheOracleVerifies(@TempDir Path work) throws IOException {
        List<In> inputs = pair();
        Path out = assemble(work.resolve("final"), inputs);
        OracleReport report = oracle(out, inputs);
        assertThat(report.defects()).as(report.toString()).isEmpty();
        for (String property : List.of("assembly.declaration", "assembly.gate", "assembly.required", "assembly.entities", "assembly.references", "assembly.consolidation",
                "assembly.accounting.distinct", "assembly.accounting.response_final_output_tokens", "assembly.accounting.run_output_tokens")) {
            assertThat(report.outcome(property, "assembly")).as(property + ": " + report).isEqualTo(Check.Outcome.VERIFIED);
        }
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        // four tool calls (ToolSearch, echo_record twice, always_fails), each observed by both sources: one canonical request and result each
        assertThat(pkg.events("tool_call", 1)).hasSize(4).allSatisfy(e -> assertThat(e.assemblyRole()).isEqualTo(3));
        assertThat(pkg.events("tool_call", 2)).hasSize(4);
        assertThat(pkg.events.stream().filter(e -> e.is("tool_call", 1)).count()).as("each request's two supporting observations stay as evidence").isEqualTo(12);
        // the six assistant records consolidate at their most advanced stage (Completed) with final usage only
        assertThat(pkg.events("model_response")).hasSize(6).allSatisfy(e -> {
            assertThat(e.activityId).isEqualTo(2);
            assertThat(e.elements("usage_observations")).allSatisfy(u -> assertThat(u.member("usage_status_id").scalarText()).isEqualTo("2"));
        });
        // MCP structured content: the stream's tool_use_result object is carried by the declared authority, the conflict stays listed
        Event echo = pkg.events("tool_call", 2).stream().filter(e -> e.json.path("tool_result", "structured_result", "structuredContent") != null).findFirst().orElseThrow();
        assertThat(echo.elements("assembly", "assembly_conflicts")).singleElement()
                .satisfies(c -> assertThat(c.member("resolution").text()).startsWith("selected by the profile's declared authority: the stream source's value"));
        assertThat(echo.get("mcp")).as("the stream's MCP projection is carried (single source)").isNotNull();
        Json declared = pkg.declarations.assembly();
        assertThat(declared.path("result", "status").text()).isEqualTo("complete with conflicts");
        assertThat(declared.path("result", "canonical_events").scalarText()).isEqualTo("61");
    }

    @Test
    void theFinalFileReadsAloneWithoutNativeFilesManifestOrSchemaCaches(@TempDir Path work) throws IOException {
        Path out = assemble(work.resolve("final"), pair());
        Path alone = Files.createDirectories(work.resolve("elsewhere/copied"));
        Files.copy(out.resolve("events.jsonl"), alone.resolve("events.jsonl"));
        OcsfPackage pkg = PackageReader.open(alone.resolve("events.jsonl"));
        assertThat(pkg.diagnostics(Diagnostic.Severity.REFUSED)).isEmpty();
        assertThat(pkg.diagnostics(Diagnostic.Severity.DEFECT)).as(pkg.diagnostics.toString()).isEmpty();
        assertThat(pkg.declarations.assembly()).as("the assembly is declared in the file itself").isNotNull();
        assertThat(pkg.declarations.assembly().path("profile", "joins").elements()).isNotEmpty();
        assertThat(pkg.canonical()).as("the canonical projection: single observations and consolidated events").hasSize(61);
    }

    @Test
    void anotherExecutionsSessionFileIsRefusedAndWrittenNowhere(@TempDir Path work) throws IOException {
        Path foreign = rewrite(work, "transcript.jsonl", t -> t.replace(RUN_SESSION, OTHER_SESSION));
        List<In> inputs = List.of(stream(), new In("transcript.jsonl", "transcript", foreign, TRANSCRIPT_ORIGIN));
        Path out = assemble(work.resolve("final"), inputs);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        Json transcript = source(pkg, "transcript.jsonl");
        assertThat(transcript.member("accepted").scalarText()).isEqualTo("false");
        assertThat(transcript.member("verdict").text()).contains(OTHER_SESSION).contains("another execution");
        assertThat(pkg.events.stream().filter(e -> "transcript.jsonl".equals(e.artifact()))).isEmpty();
        OracleReport report = oracle(out, inputs);
        assertThat(report.outcome("assembly.gate", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        // a file that claims the foreign source belongs to the execution is rejected by the oracle
        Path forged = mutate(work, out, line -> line.contains("\"kind\":\"assembly\"") ? line.replace("\"accepted\":false", "\"accepted\":true") : line);
        assertThat(oracle(forged, inputs).outcome("assembly.gate", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
    }

    @Test
    void aMissingRequiredSourceMakesTheAssemblyIncomplete(@TempDir Path work) throws IOException {
        List<In> inputs = List.of(transcript());
        Path out = assemble(work.resolve("final"), inputs);
        Json declared = PackageReader.open(out.resolve("events.jsonl")).declarations.assembly();
        assertThat(declared.path("result", "status").text()).isEqualTo("incomplete");
        assertThat(declared.path("result", "missing_required_roles").elements()).extracting(Json::text).containsExactly("stream");
        OracleReport report = oracle(out, inputs);
        assertThat(report.outcome("assembly.required", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
        Path forged = mutate(work, out, line -> line.contains("\"kind\":\"assembly\"") ? line.replace("\"status\":\"incomplete\"", "\"status\":\"complete\"") : line);
        assertThat(oracle(forged, inputs).outcome("assembly.required", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
    }

    @Test
    void replayingTheSameImportAddsNothingWhileIdenticalBytesFromAnotherOriginStayDistinct(@TempDir Path work) throws IOException {
        List<In> replay = new ArrayList<>(pair());
        replay.add(new In("transcript-again.jsonl", "transcript", nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), TRANSCRIPT_ORIGIN));
        Path out = assemble(work.resolve("replay"), replay);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        assertThat(source(pkg, "transcript-again.jsonl").member("verdict").text()).contains("the same import as transcript.jsonl");
        assertThat(pkg.events.stream().filter(e -> "transcript-again.jsonl".equals(e.artifact()))).isEmpty();
        assertThat(pkg.declarations.assembly().path("result", "canonical_events").scalarText()).isEqualTo("61");
        assertThat(oracle(out, replay).defects()).as(oracle(out, replay).toString()).isEmpty();

        // CDX-R1-05: the same bytes from another origin, with no declared copy lineage: equal content does not say they
        // are one native occurrence, so they are neither consolidated by position nor counted again; they are refused
        List<In> undeclared = new ArrayList<>(pair());
        undeclared.add(new In("transcript-copy.jsonl", "transcript", nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), "a second copy taken from the session directory"));
        Path out1 = assemble(work.resolve("undeclared"), undeclared);
        OcsfPackage pkg1 = PackageReader.open(out1.resolve("events.jsonl"));
        assertThat(source(pkg1, "transcript-copy.jsonl").member("accepted").scalarText()).isEqualTo("false");
        assertThat(source(pkg1, "transcript-copy.jsonl").member("verdict").text()).contains("identical bytes to transcript.jsonl from another origin").contains("no declared copy lineage");
        assertThat(pkg1.events.stream().filter(e -> "transcript-copy.jsonl".equals(e.artifact()))).isEmpty();
        assertThat(pkg1.declarations.assembly().path("result", "canonical_events").scalarText()).isEqualTo("61");
        assertThat(oracle(out1, undeclared).defects()).as(oracle(out1, undeclared).toString()).isEmpty();

        // the same input declared a copy of transcript.jsonl: the declared lineage relates the records, the equal bytes verify it
        List<In> copy = new ArrayList<>(pair());
        copy.add(new In("transcript-copy.jsonl", "transcript", nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), "a second copy taken from the session directory",
                WriterTestSupport.launch(LIVE_CLAUDE_PAIR), "transcript.jsonl"));
        Path out2 = assemble(work.resolve("copy"), copy);
        OcsfPackage pkg2 = PackageReader.open(out2.resolve("events.jsonl"));
        assertThat(source(pkg2, "transcript-copy.jsonl").member("accepted").scalarText()).isEqualTo("true");
        assertThat(source(pkg2, "transcript-copy.jsonl").member("copy_of").text()).isEqualTo("transcript.jsonl");
        assertThat(pkg2.events.stream().filter(e -> "transcript-copy.jsonl".equals(e.artifact()))).as("its provenance is kept").isNotEmpty();
        assertThat(pkg2.declarations.assembly().path("result", "canonical_events").scalarText()).as("a copy never becomes extra executions").isEqualTo("61");
        assertThat(pkg2.events("tool_call", 1)).hasSize(4);
        OracleReport report = oracle(out2, copy);
        assertThat(report.defects()).as(report.toString()).isEmpty();

        // a declared copy whose bytes differ is not a copy
        List<In> notACopy = new ArrayList<>(pair());
        notACopy.add(new In("transcript-copy.jsonl", "transcript", nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), "a file claimed to copy the session file",
                WriterTestSupport.launch(LIVE_CLAUDE_PAIR), "transcript.jsonl"));
        OcsfPackage pkg3 = PackageReader.open(assemble(work.resolve("differs"), notACopy).resolve("events.jsonl"));
        assertThat(source(pkg3, "transcript-copy.jsonl").member("verdict").text()).contains("its bytes differ");
        assertThat(pkg3.events.stream().filter(e -> "transcript-copy.jsonl".equals(e.artifact()))).isEmpty();
    }

    @Test
    void anOptionalSourceWithoutAnIdentityBelongsOnlyThroughTheAnchorsAcquisition(@TempDir Path work) throws IOException {
        // CDX-R1-06: a session file whose records report no session; optionality is not membership evidence
        String text = Files.readString(nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), StandardCharsets.UTF_8)
                .replace(",\"sessionId\":\"" + RUN_SESSION + "\"", "").replace("\"sessionId\":\"" + RUN_SESSION + "\",", "");
        assertThat(text).doesNotContain(RUN_SESSION);
        Path anonymous = Files.writeString(work.resolve("transcript.jsonl"), text, StandardCharsets.UTF_8);
        List<In> elsewhere = List.of(stream(), new In("transcript.jsonl", "transcript", anonymous, TRANSCRIPT_ORIGIN, "another acquisition", null));
        OcsfPackage refused = PackageReader.open(assemble(work.resolve("elsewhere"), elsewhere).resolve("events.jsonl"));
        assertThat(source(refused, "transcript.jsonl").member("accepted").scalarText()).isEqualTo("false");
        assertThat(source(refused, "transcript.jsonl").member("verdict").text()).contains("membership not established");
        assertThat(refused.events.stream().filter(e -> "transcript.jsonl".equals(e.artifact()))).isEmpty();
        assertThat(oracle(work.resolve("elsewhere"), elsewhere).outcome("assembly.gate", "assembly")).isEqualTo(Check.Outcome.VERIFIED);
        List<In> unasserted = List.of(stream(), new In("transcript.jsonl", "transcript", anonymous, TRANSCRIPT_ORIGIN, null, null));
        OcsfPackage none = PackageReader.open(assemble(work.resolve("unasserted"), unasserted).resolve("events.jsonl"));
        assertThat(source(none, "transcript.jsonl").member("accepted").scalarText()).isEqualTo("false");
        // asserted as the stream's own acquisition, it is a member, and the file says on what basis
        List<In> same = List.of(stream(), new In("transcript.jsonl", "transcript", anonymous, TRANSCRIPT_ORIGIN, WriterTestSupport.launch(LIVE_CLAUDE_PAIR), null));
        OcsfPackage member = PackageReader.open(assemble(work.resolve("same"), same).resolve("events.jsonl"));
        assertThat(source(member, "transcript.jsonl").member("accepted").scalarText()).isEqualTo("true");
        assertThat(source(member, "transcript.jsonl").member("verdict").text()).contains("a member by the acquisition");
        assertThat(oracle(work.resolve("same"), same).outcome("assembly.gate", "assembly")).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void anIdentityRepeatedWithinOneSourceStaysUnpairedAndFlagged(@TempDir Path work) throws IOException {
        // the session file's last tool_use assistant record appears twice (the same uuid and tool_use id in two records)
        String text = Files.readString(nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), StandardCharsets.UTF_8);
        String repeated = text.lines().filter(l -> l.contains("\"type\":\"assistant\"") && l.contains("\"tool_use\"")).reduce((a, b) -> b).orElseThrow();
        Path dup = Files.writeString(work.resolve("transcript.jsonl"), text + repeated + "\n", StandardCharsets.UTF_8);
        List<In> inputs = List.of(stream(), new In("transcript.jsonl", "transcript", dup, TRANSCRIPT_ORIGIN));
        Path out = assemble(work.resolve("final"), inputs);
        OcsfPackage pkg = PackageReader.open(out.resolve("events.jsonl"));
        List<Event> flagged = pkg.events.stream().filter(e -> "6".equals(e.get("assembly", "relation_class_id") == null ? null : e.get("assembly", "relation_class_id").scalarText())).toList();
        assertThat(flagged).as("the stream's and both session-file observations of that record stay canonical and flagged").hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(e -> assertThat(e.assemblyRole()).isEqualTo(1));
        OracleReport report = oracle(out, inputs);
        assertThat(report.outcome("assembly.entities", "assembly")).as(report.toString()).isEqualTo(Check.Outcome.VERIFIED);
    }

    @Test
    void theOracleRejectsAlteredConsolidation(@TempDir Path work) throws IOException {
        List<In> inputs = pair();
        Path out = assemble(work.resolve("final"), inputs);
        // a supporting observation presented as canonical: an extra execution
        Path demoted = mutate(work, out, firstMatching(l -> l.contains("\"assembly_role_id\":2") && l.contains("\"class_uid\":90111003"),
                l -> l.replace("\"assembly_role_id\":2,\"assembly_role\":\"Supporting observation\"", "\"assembly_role_id\":1,\"assembly_role\":\"Canonical single observation\"")));
        OracleReport d = oracle(demoted, inputs);
        assertThat(d.outcome("assembly.entities", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        // a conflict silently dropped
        Path silent = mutate(work, out, firstMatching(l -> l.contains("\"assembly_role_id\":3") && l.contains("\"assembly_conflicts\":["), FinalAssemblyClaudeTest::withoutConflicts));
        assertThat(oracle(silent, inputs).outcome("assembly.consolidation", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        // a value no source reported
        Path invented = mutate(work, out, firstMatching(l -> l.contains("\"assembly_role_id\":3") && l.contains("\"stop_reason\""),
                l -> l.replaceFirst("\"stop_reason\":\"[a-z_]+\"", "\"stop_reason\":\"invented\"")));
        assertThat(oracle(invented, inputs).outcome("assembly.consolidation", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
        // one quantity carried twice on a canonical event
        Path doubled = mutate(work, out, firstMatching(l -> l.contains("\"assembly_role_id\":3") && l.contains("\"usage_observations\":[{"), FinalAssemblyClaudeTest::doubledUsage));
        OracleReport u = oracle(doubled, inputs);
        assertThat(u.outcome("assembly.accounting.distinct", "assembly")).isEqualTo(Check.Outcome.DEFECTIVE);
    }

    @Test
    void assemblyLeavesItsInputsAndTheOriginalPackageUnchanged(@TempDir Path work) throws IOException {
        Path stream = Files.copy(nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), work.resolve("stream.jsonl"));
        Path transcript = Files.copy(nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), work.resolve("transcript.jsonl"));
        Path original = FIXTURES.resolve(LIVE_CLAUDE_PAIR).resolve("../" + WriterTestSupport.LIVE_MCP + "/package").normalize();
        String before = NativeArtifact.read(stream).sha256() + NativeArtifact.read(transcript).sha256() + NativeArtifact.read(original.resolve("events.jsonl")).sha256();
        Jv lineage = new Jv.Arr().add(FinalAssembly.lineage(original, "the stream-only package of the same launch"));
        FinalAssembly.assemble(work.resolve("final"), run(LIVE_CLAUDE_PAIR), AssemblyProfile.bundled(PROFILE),
                List.of(new FinalAssembly.Input("stream.jsonl", "stream", stream, STREAM_ORIGIN, WriterTestSupport.launch(LIVE_CLAUDE_PAIR)),
                        new FinalAssembly.Input("transcript.jsonl", "transcript", transcript, TRANSCRIPT_ORIGIN, WriterTestSupport.launch(LIVE_CLAUDE_PAIR))),
                lineage, options());
        String after = NativeArtifact.read(stream).sha256() + NativeArtifact.read(transcript).sha256() + NativeArtifact.read(original.resolve("events.jsonl")).sha256();
        assertThat(after).isEqualTo(before);
        Json declared = PackageReader.open(work.resolve("final/events.jsonl")).declarations.assembly();
        assertThat(declared.member("lineage").elements()).singleElement()
                .satisfies(l -> assertThat(l.member("events_jsonl_sha256").text()).isEqualTo(NativeArtifact.read(original.resolve("events.jsonl")).sha256()));
    }

    // ---- support -----------------------------------------------------------------------------------------------------

    static In stream() {
        return new In("stream.jsonl", "stream", nativeOf(LIVE_CLAUDE_PAIR, "stream.jsonl"), STREAM_ORIGIN);
    }

    static In transcript() {
        return new In("transcript.jsonl", "transcript", nativeOf(LIVE_CLAUDE_PAIR, "transcript.jsonl"), TRANSCRIPT_ORIGIN);
    }

    static List<In> pair() {
        return List.of(stream(), transcript());
    }

    static Path assemble(Path out, List<In> inputs) {
        List<FinalAssembly.Input> in = inputs.stream().map(i -> new FinalAssembly.Input(i.artifact(), i.role(), i.file(), i.origin(), i.acquisition(), i.copyOf())).toList();
        FinalAssembly.assemble(out, run(LIVE_CLAUDE_PAIR), AssemblyProfile.bundled(PROFILE), in, new Jv.Arr(), options());
        return out;
    }

    static OracleReport oracle(Path out, List<In> inputs) throws IOException {
        Json spec = NeutralJson.parse(Files.readString(FIXTURES.resolve(LIVE_CLAUDE_PAIR).resolve("native/assembly-expectation.json")));
        List<AssemblyOracle.Supplied> supplied = inputs.stream().map(i -> new AssemblyOracle.Supplied(i.artifact(), i.role(), i.origin(), NativeArtifact.read(i.file()), i.acquisition(), i.copyOf())).toList();
        return AssemblyOracle.verify(PackageReader.open(out.resolve("events.jsonl")), spec, supplied);
    }

    static Json source(OcsfPackage pkg, String artifact) {
        return pkg.declarations.assembly().member("sources").elements().stream().filter(s -> artifact.equals(s.member("artifact").text())).findFirst().orElseThrow();
    }

    static Path rewrite(Path work, String artifact, UnaryOperator<String> edit) throws IOException {
        String text = Files.readString(nativeOf(LIVE_CLAUDE_PAIR, artifact), StandardCharsets.UTF_8);
        return Files.writeString(Files.createDirectories(work.resolve("edited")).resolve(artifact), edit.apply(text), StandardCharsets.UTF_8);
    }

    /** A copy of the final file with each line edited; the declarations stay as written. */
    static Path mutate(Path work, Path out, UnaryOperator<String> edit) throws IOException {
        Path dir = Files.createTempDirectory(work, "mutated");
        List<String> lines = Files.readAllLines(out.resolve("events.jsonl"), StandardCharsets.UTF_8).stream().map(edit).toList();
        Files.write(dir.resolve("events.jsonl"), lines, StandardCharsets.UTF_8);
        return dir;
    }

    static UnaryOperator<String> firstMatching(java.util.function.Predicate<String> match, UnaryOperator<String> edit) {
        boolean[] done = {false};
        return l -> {
            if (!done[0] && match.test(l)) {
                done[0] = true;
                return edit.apply(l);
            }
            return l;
        };
    }

    static String withoutConflicts(String line) {
        Jv.Obj e = (Jv.Obj) Jv.parse(line);
        ((Jv.Obj) e.get("assembly")).members().remove("assembly_conflicts");
        return Jv.write(e);
    }

    static String doubledUsage(String line) {
        Jv.Obj e = (Jv.Obj) Jv.parse(line);
        Jv.Arr usage = (Jv.Arr) e.get("usage_observations");
        usage.add(Jv.copy(usage.elements().get(0)));
        return Jv.write(e);
    }
}
