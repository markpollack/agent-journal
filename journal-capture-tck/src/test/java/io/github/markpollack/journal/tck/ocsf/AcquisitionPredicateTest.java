package io.github.markpollack.journal.tck.ocsf;

import io.github.markpollack.journal.tck.verify.Check.Outcome;
import io.github.markpollack.journal.tck.verify.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.copy;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editEvents;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editExpected;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.editManifest;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.natives;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.num;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.obj;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.pkg;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.str;
import static io.github.markpollack.journal.tck.ocsf.OcsfFixtures.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each predicate of the frozen-denominator, extent, acquisition and source-state checks, one branch at a time, with
 * the exact detail it reports: the frozen-artifact comparison field by field, optional and partial manifests, extent
 * claims (complete with a different artifact, incomplete yet larger, within a partial capture), acquisition
 * derivation without a declaration or while still recording, the acquired-prefix judging rules, each source-state
 * inconsistency, child scope fields and the report's own description of its denominator.
 */
class AcquisitionPredicateTest {

    static final String PAIR = OracleFullSourceTest.PAIR;
    static final String STREAM = "main/stream.jsonl";
    static final String TRANSCRIPT = "main/transcript.jsonl";

    @Test
    void theSuppliedArtifactIsComparedWithTheFrozenOneFieldByField(@TempDir Path work) {
        Path bytes = copy(PAIR, work.resolve("bytes"));
        editExpected(bytes, m -> {
            Json.JObject s = obj(m.member("sources").elements().get(1));
            s.members().remove("sha256");
            s.members().put("bytes", num("1"));
        });
        OracleReport r = verify(bytes);
        assertThat(r.check("source.available", TRANSCRIPT).detail()).isEqualTo("the supplied native artifact is not the frozen expected source: bytes 7569 != frozen 1");
        Path records = copy(PAIR, work.resolve("records"));
        editExpected(records, m -> {
            Json.JObject s = obj(m.member("sources").elements().get(1));
            s.members().remove("sha256");
            s.members().remove("bytes");
            s.members().put("records", num("2"));
        });
        assertThat(verify(records).check("source.available", TRANSCRIPT).detail()).isEqualTo("the supplied native artifact is not the frozen expected source: records 17 != frozen 2");
        Path sha = copy(PAIR, work.resolve("sha"));
        editExpected(sha, m -> obj(m.member("sources").elements().get(1)).members().put("sha256", str("0".repeat(64))));
        assertThat(verify(sha).check("source.available", TRANSCRIPT).detail()).startsWith("the supplied native artifact is not the frozen expected source: sha256 ").contains(" != frozen 0000");
        Path none = copy(PAIR, work.resolve("none"));
        editExpected(none, m -> {
            Json.JObject s = obj(m.member("sources").elements().get(1));
            s.members().remove("sha256");
            s.members().remove("bytes");
            s.members().remove("records");
        });
        OracleReport nr = verify(none);
        assertThat(nr.outcome("source.available", TRANSCRIPT)).as("a manifest without an extent cannot disagree").isEqualTo(Outcome.VERIFIED);
        assertThat(nr.check("source.extent", TRANSCRIPT).detail()).startsWith("declared complete with the frozen extent");
    }

    @Test
    void anOptionalSourceAPartialCaptureAndARunWithoutAUidAreEachStated(@TempDir Path work) {
        Path optional = copy(PAIR, work.resolve("optional"));
        editExpected(optional, m -> obj(m.member("sources").elements().get(1)).members().put("required", new Json.JBool(false)));
        FullOneReviewRegressionTest.omitTranscriptSource(optional);
        OracleReport r = verify(optional);
        assertThat(r.outcome("source.expected", TRANSCRIPT)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(r.check("source.expected", TRANSCRIPT).detail()).startsWith("optional source not declared: the frozen manifest expects transcript.jsonl");
        assertThat(r.outcome("whole_record.coverage", TRANSCRIPT)).as("optional does not waive the obligation once the artifact exists").isEqualTo(Outcome.DEFECTIVE);

        Path partial = copy(PAIR, work.resolve("partial"));
        editExpected(partial, m -> {
            m.members().put("complete", new Json.JBool(false));
            m.members().remove("run_uid");
        });
        editManifest(pkg(partial), run -> {
            Json.JObject stream = obj(run.member("sources").elements().get(0));
            stream.members().put("state", str("INTERRUPTED"));
            obj(stream.member("ended")).members().put("how", str("INTERRUPTED"));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        OracleReport p = verify(partial);
        assertThat(p.check("source.denominator", "main").detail()).isEqualTo("frozen expected-source manifest: 2 artifact(s) of a partial capture");
        assertThat(p.outcome("source.extent", STREAM)).as("an honest prefix within a declared partial capture is what was expected").isEqualTo(Outcome.VERIFIED);
        assertThat(p.check("source.extent", STREAM).detail()).contains("within the declared partial capture");
        assertThat(p.outcome("acquisition.complete", "main")).as("acquisition completeness is still judged on its own").isEqualTo(Outcome.UNSUPPORTED);
        assertThat(p.withOutcome(Outcome.DEFECTIVE)).isEmpty();
    }

    @Test
    void extentClaimsAreJudgedAgainstTheFrozenExtent(@TempDir Path work) {
        Path different = copy(PAIR, work.resolve("different"));
        editManifest(pkg(different), run -> obj(run.member("sources").elements().get(1)).members().put("sha256", str("1".repeat(64))));
        OracleReport d = verify(different);
        assertThat(d.outcome("source.extent", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(d.check("source.extent", TRANSCRIPT).detail()).startsWith("declared complete but with a different extent: 17 record(s), 7569 bytes, sha256 1111");
        assertThat(d.outcome("source.inventory", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);

        Path larger = copy(PAIR, work.resolve("larger"));
        editManifest(pkg(larger), run -> {
            Json.JObject s = obj(run.member("sources").elements().get(1));
            s.members().put("state", str("INTERRUPTED"));
            obj(s.member("ended")).members().put("how", str("INTERRUPTED"));
            s.members().put("records", num("99"));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        OracleReport l = verify(larger);
        assertThat(l.outcome("source.extent", TRANSCRIPT)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(l.check("source.extent", TRANSCRIPT).detail()).startsWith("declared incomplete yet larger than the frozen extent: 99/7569 vs 17/7569");

        Path bytesOnly = copy(PAIR, work.resolve("bytes-only"));
        editExpected(bytesOnly, m -> obj(m.member("sources").elements().get(1)).members().remove("records"));
        editManifest(pkg(bytesOnly), run -> obj(run.member("sources").elements().get(1)).members().put("records", num("16")));
        OracleReport b = verify(bytesOnly);
        assertThat(b.outcome("source.extent", TRANSCRIPT)).as("the frozen manifest states no record count, so bytes and hash decide").isEqualTo(Outcome.VERIFIED);
        assertThat(b.outcome("source.inventory", TRANSCRIPT)).as("the artifact itself still has 17").isEqualTo(Outcome.DEFECTIVE);
    }

    @Test
    void acquisitionIsDerivedFromTheSourcesWhenNothingIsDeclaredAndWhileRecording(@TempDir Path work) {
        Path undeclared = copy(PAIR, work.resolve("undeclared"));
        editManifest(pkg(undeclared), run -> run.members().remove("recording"));
        OracleReport u = verify(undeclared);
        assertThat(u.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(u.check("acquisition.complete", "main").detail()).contains("no acquisition declaration (");

        Path statusless = copy(PAIR, work.resolve("statusless"));
        editManifest(pkg(statusless), run -> obj(run.path("recording", "acquisition")).members().remove("status"));
        OracleReport s = verify(statusless);
        assertThat(s.outcome("acquisition.complete", "main")).as("a missing status cannot disagree with the derived one").isEqualTo(Outcome.VERIFIED);

        Path recording = copy(PAIR, work.resolve("recording"));
        editManifest(pkg(recording), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            Json.JObject stream = obj(run.member("sources").elements().get(0));
            stream.members().put("final", new Json.JBool(false));
            stream.members().put("state", str("RECORDING"));
            stream.members().remove("sha256");
            stream.members().remove("ended");
            obj(run.path("recording", "acquisition")).members().put("status", str("pending"));
        });
        OracleReport r = verify(recording);
        assertThat(r.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNRESOLVED);
        assertThat(r.check("acquisition.complete", "main").detail()).isEqualTo("still recording: stream.jsonl: still recording");
        assertThat(r.outcome("source.state", STREAM)).isEqualTo(Outcome.VERIFIED);
        assertThat(r.check("source.state", STREAM).detail()).isEqualTo("open (state RECORDING), no hash or end marker");
        assertThat(r.outcome("source.extent", STREAM)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(r.check("source.extent", STREAM).detail()).startsWith("declared incomplete (open): 27 of 27 record(s)");

        Path interrupted = copy(PAIR, work.resolve("interrupted"));
        editManifest(pkg(interrupted), run -> obj(run.member("package")).members().put("state", str("INTERRUPTED")));
        OracleReport i = verify(interrupted);
        assertThat(i.check("acquisition.complete", "main").detail()).startsWith("acquisition incomplete (storage INTERRUPTED): ");
    }

    @Test
    void anHonestPrefixIsJudgedOnItsDeclaredExtentOnlyWhenItIsAVerifiedPrefix(@TempDir Path work) throws Exception {
        // the candidate acquired the first 10 stream records: the events beyond them are removed and the source declares that prefix
        byte[] whole = Files.readAllBytes(natives(copy(PAIR, work.resolve("probe"))).resolve("stream.jsonl"));
        NativeArtifact art = NativeArtifact.ofBytes(Path.of("stream.jsonl"), whole);
        int cut = 0;
        for (int k = 0; k < 10; k++) {
            cut += art.records().get(k).raw().length + 1;
        }
        final int prefixBytes = cut;
        String prefixSha = NativeArtifact.sha256(java.util.Arrays.copyOf(whole, cut));
        Path honest = copy(PAIR, work.resolve("honest"));
        editEvents(pkg(honest), events -> events.removeIf(e -> "stream.jsonl".equals(SchemaCatalog.text(e.member("source_ref"), "artifact")) && OcsfFixtures.recordIndex(e) > 10));
        editManifest(pkg(honest), run -> {
            Json.JObject s = obj(run.member("sources").elements().get(0));
            s.members().put("state", str("INTERRUPTED"));
            obj(s.member("ended")).members().put("how", str("INTERRUPTED"));
            s.members().put("records", num("10"));
            s.members().put("bytes", num(Integer.toString(prefixBytes)));
            s.members().put("sha256", str(prefixSha));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        OracleReport h = verify(honest);
        assertThat(h.outcome("source.inventory", STREAM)).as("judged on the verified prefix").isEqualTo(Outcome.VERIFIED);
        assertThat(h.check("whole_record.coverage", STREAM).detail()).startsWith("10 owned record(s) each retained exactly once");
        assertThat(h.outcome("source.extent", STREAM)).isEqualTo(Outcome.UNSUPPORTED);
        assertThat(h.check("source.extent", STREAM).detail()).contains("10 of 27 record(s)");
        assertThat(((java.util.Map<?, ?>) h.toMap().get("oracle")).get(STREAM + " (acquired prefix)")).as("the report says which prefix it judged").isNotNull();
        assertThat(OcsfReaderTool.acceptance(h).exit()).isEqualTo(OcsfReaderTool.EXIT_INCOMPLETE);

        // the same declaration with a hash that is not the prefix's: judged against the whole artifact, which reports the mismatch
        Path dishonest = copy(PAIR, work.resolve("dishonest"));
        editManifest(pkg(dishonest), run -> {
            Json.JObject s = obj(run.member("sources").elements().get(0));
            s.members().put("state", str("INTERRUPTED"));
            obj(s.member("ended")).members().put("how", str("INTERRUPTED"));
            s.members().put("records", num("10"));
            s.members().put("bytes", num(Integer.toString(prefixBytes)));
            s.members().put("sha256", str("2".repeat(64)));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        OracleReport d = verify(dishonest);
        assertThat(d.outcome("source.inventory", STREAM)).isEqualTo(Outcome.DEFECTIVE);
        assertThat(d.check("source.inventory", STREAM).detail()).contains("records 10 declared, artifact has 27");
        assertThat(((java.util.Map<?, ?>) d.toMap().get("oracle")).get(STREAM + " (acquired prefix)")).isNull();

        // a declared extent at least the artifact's length is never a prefix
        Path beyond = copy(PAIR, work.resolve("beyond"));
        editManifest(pkg(beyond), run -> {
            Json.JObject s = obj(run.member("sources").elements().get(0));
            s.members().put("state", str("INTERRUPTED"));
            obj(s.member("ended")).members().put("how", str("INTERRUPTED"));
            s.members().put("bytes", num(Integer.toString(whole.length + 5)));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        OracleReport b = verify(beyond);
        assertThat(b.check("source.inventory", STREAM).detail()).contains("bytes " + (whole.length + 5) + ", artifact has " + whole.length);
        assertThat(((java.util.Map<?, ?>) b.toMap().get("oracle")).get(STREAM + " (acquired prefix)")).isNull();
    }

    @Test
    void everySourceStateInconsistencyIsNamed(@TempDir Path work) throws Exception {
        Path noEnded = copy(PAIR, work.resolve("no-ended"));
        editManifest(pkg(noEnded), run -> obj(run.member("sources").elements().get(0)).members().remove("ended"));
        assertThat(verify(noEnded).check("source.state", STREAM).detail()).isEqualTo("final but no ended marker");

        Path openWithSha = copy(PAIR, work.resolve("open-sha"));
        editManifest(pkg(openWithSha), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            Json.JObject s = obj(run.member("sources").elements().get(0));
            s.members().put("final", new Json.JBool(false));
            s.members().put("state", str("RECORDING"));
            s.members().remove("ended");
            obj(run.path("recording", "acquisition")).members().put("status", str("pending"));
        });
        assertThat(verify(openWithSha).check("source.state", STREAM).detail()).isEqualTo("open yet carries a sha256");
        Path openWithEnded = copy(PAIR, work.resolve("open-ended"));
        editManifest(pkg(openWithEnded), run -> {
            obj(run.member("package")).members().put("state", str("RECORDING"));
            obj(run.member("package")).members().put("events_jsonl_sha256", Json.JNull.INSTANCE);
            Json.JObject s = obj(run.member("sources").elements().get(0));
            s.members().put("final", new Json.JBool(false));
            s.members().put("state", str("RECORDING"));
            s.members().remove("sha256");
            obj(run.path("recording", "acquisition")).members().put("status", str("pending"));
        });
        assertThat(verify(openWithEnded).check("source.state", STREAM).detail()).isEqualTo("open yet carries an ended marker");

        Path tailNotLast = copy(PAIR, work.resolve("tail-not-last"));
        editManifest(pkg(tailNotLast), run -> {
            obj(run.member("sources").elements().get(0)).members().put("incomplete_tail_record", num("3"));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        String detail = verify(tailNotLast).check("source.state", STREAM).detail();
        assertThat(detail).contains("incomplete_tail_record 3 is not the artifact's last record (27)").contains("is not retained as a parse_status 5");

        // the native is cut inside its last record and the candidate declares the tail, but retained it as an ordinary event
        Path tailNotRetained = copy(PAIR, work.resolve("tail-not-retained"));
        Path stream = natives(tailNotRetained).resolve("stream.jsonl");
        byte[] all = Files.readAllBytes(stream);
        Files.write(stream, java.util.Arrays.copyOf(all, all.length - 20));
        OcsfFixtures.refreeze(tailNotRetained);
        editManifest(pkg(tailNotRetained), run -> {
            obj(run.member("sources").elements().get(0)).members().put("incomplete_tail_record", num("27"));
            obj(run.path("recording", "acquisition")).members().put("status", str("incomplete"));
        });
        OracleReport t = verify(tailNotRetained);
        assertThat(t.check("source.state", STREAM).detail()).isEqualTo("incomplete_tail_record 27 is not retained as a parse_status 5 (Incomplete at source end) record");
        assertThat(t.outcome("acquisition.complete", "main")).isEqualTo(Outcome.UNSUPPORTED);
    }

    @Test
    void childScopeFieldsAreEachCompared(@TempDir Path work) {
        Path path = copy(PAIR, work.resolve("path"));
        editManifest(OcsfFixtures.child(path), run -> obj(run.path("run", "source_scope")).members().put("owner_path", str("other_path")));
        assertThat(verify(path).check("package.children", "main").detail()).contains("declares scope").contains("expected stream.jsonl.parent_tool_use_id = " + OcsfFixtures.SPAWN);
        Path artifact = copy(PAIR, work.resolve("artifact"));
        editManifest(OcsfFixtures.child(artifact), run -> obj(run.path("run", "source_scope")).members().put("artifact", str("transcript.jsonl")));
        assertThat(verify(artifact).outcome("package.children", "main")).isEqualTo(Outcome.DEFECTIVE);
        Path noScope = copy(PAIR, work.resolve("no-scope"));
        editManifest(OcsfFixtures.child(noScope), run -> obj(run.member("run")).members().remove("source_scope"));
        assertThat(verify(noScope).check("package.children", "main").detail()).contains("declares scope none");
        Path control = copy(PAIR, work.resolve("control"));
        assertThat(verify(control).check("package.children", "main").detail()).isEqualTo("1 expected split core(s), each linked with its declared owner scope");
        Path codex = copy(OcsfFixtures.CODEX, work.resolve("codex"));
        assertThat(verify(codex).outcome("package.children", "main")).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void theReportDescribesItsDenominatorAndRequiredChecks(@TempDir Path work) throws Exception {
        Path fixture = copy(PAIR, work);
        OracleReport r = verify(fixture);
        java.util.Map<?, ?> oracle = (java.util.Map<?, ?>) r.toMap().get("oracle");
        assertThat(oracle.get("expectedSources").toString()).isEqualTo("stream.jsonl (claude_stream), transcript.jsonl (claude_transcript) [complete run]");
        assertThat(r.required()).contains("source.available [" + STREAM + "]", "source.state [" + TRANSCRIPT + "]", "package.readable [child:" + OcsfFixtures.SPAWN + "]");
        assertThat(r.toMap().get("required")).isEqualTo(r.required());
        assertThat(r.toMap().get("missingRequired")).isEqualTo(List.of());
        editExpected(fixture, m -> m.members().put("complete", new Json.JBool(false)));
        assertThat(((java.util.Map<?, ?>) verify(fixture).toMap().get("oracle")).get("expectedSources").toString()).endsWith("[not a complete run]");
        Files.delete(natives(fixture).resolve(ExpectedSources.FILE_NAME));
        assertThat(((java.util.Map<?, ?>) verify(fixture).toMap().get("oracle")).get("expectedSources").toString()).startsWith("none: candidate-declared inventory");
    }
}
