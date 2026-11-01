package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.journal.tck.ocsf.Diagnostic;
import io.github.markpollack.journal.tck.ocsf.NativeArtifact;
import io.github.markpollack.journal.tck.ocsf.OcsfPackage;
import io.github.markpollack.journal.tck.ocsf.PackageReader;
import io.github.markpollack.journal.tck.verify.Json;
import io.github.markpollack.journal.tck.verify.NeutralJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Existing-writer concurrency proof with synthetic Client receipts, not Client arbitration or a new evidence schema. */
class LateFinalizationReaderTest {
    @TempDir Path work;
    private static final String EXECUTION = "client-execution-fixture";

    private record Evidence(Path receipt, String receiptHash, Path termination, String terminationHash, Path observation, Path events) { }
    private record Read(Json originalDecision, Json originalTermination, String providerOutcome, String currentCapture) { }

    @Test
    void finalizerUnblocksAfterFrozenTimeoutWithoutChangingTheOriginalReceipt() throws Exception {
        Evidence evidence = lateEvidence();
        Read read = independentlyRead(evidence, evidence.observation());
        assertThat(read.originalDecision().path("decision", "future_state").text()).isEqualTo("CANCELLED");
        assertThat(read.originalDecision().path("decision", "reason").text()).isEqualTo("TIMEOUT");
        assertThat(read.originalTermination().member("cleanup_confirmed").scalarText()).isEqualTo("false");
        assertThat(read.originalDecision().path("capture_at_terminal", "status").text()).isEqualTo("PENDING");
        assertThat(read.originalDecision().member("termination")).isNull();
        assertThat(NativeArtifact.sha256(Files.readAllBytes(evidence.termination()))).isEqualTo(evidence.terminationHash());
        assertThat(read.providerOutcome()).isEqualTo("SUCCESS");
        assertThat(read.currentCapture()).isEqualTo("complete");
        assertThat(NativeArtifact.sha256(Files.readAllBytes(evidence.receipt()))).isEqualTo(evidence.receiptHash());
        assertThatThrownBy(() -> Files.writeString(evidence.observation(), "overwrite", StandardOpenOption.CREATE_NEW))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test
    void independentReadingRejectsPromotionWrongExecutionAndMissingReceiptBinding() throws Exception {
        Evidence evidence = lateEvidence();
        String original = Files.readString(evidence.observation());
        List<String> changed = List.of(
                original.replace("\"capture_completion\":", "\"execution_outcome\":\"SUCCESS\",\"capture_completion\":"),
                original.replace(EXECUTION, "another-execution"),
                original.replace("client-connection-fixture", "another-connection"),
                original.replace("original_terminal_receipt", "unbound_receipt"),
                original.replace("termination_receipt", "unbound_termination"),
                original.replace("decision-fixture", "another-decision"),
                original.replace("\"future_state\":\"CANCELLED\"", "\"future_state\":\"SUCCESS\""),
                original.replace(evidence.terminationHash(), "wrong-termination-hash"),
                original.replace(evidence.receiptHash(), "wrong-receipt-hash"));
        for (int i = 0; i < changed.size(); i++) {
            Path control = Files.writeString(work.resolve("rejection-" + i + ".json"), changed.get(i));
            assertThatThrownBy(() -> independentlyRead(evidence, control)).isInstanceOf(IllegalArgumentException.class);
        }
        // Rehashing a rewritten receipt cannot replace the independently pinned original decision.
        String promoted = Files.readString(evidence.receipt()).replace("CANCELLED", "SUCCESS");
        Path replacement = Files.writeString(work.resolve("rewritten-receipt.json"), promoted);
        String forged = original.replace("terminal-receipt.json", "rewritten-receipt.json")
                .replace(evidence.receiptHash(), NativeArtifact.sha256(Files.readAllBytes(replacement)));
        Path control = Files.writeString(work.resolve("forged-receipt-link.json"), forged);
        assertThatThrownBy(() -> independentlyRead(evidence, control)).isInstanceOf(IllegalArgumentException.class);
        String changedTermination = Files.readString(evidence.termination()).replace("false", "true");
        Path changedT = Files.writeString(work.resolve("rewritten-termination.json"), changedTermination);
        String forgedT = original.replace("termination-receipt.json", "rewritten-termination.json")
                .replace(evidence.terminationHash(), NativeArtifact.sha256(Files.readAllBytes(changedT)));
        Path tControl = Files.writeString(work.resolve("forged-termination-link.json"), forgedT);
        assertThatThrownBy(() -> independentlyRead(evidence, tControl)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLateReferenceCannotHideChangedCanonicalBytes() throws Exception {
        Evidence evidence = lateEvidence();
        Files.writeString(evidence.events(), Files.readString(evidence.events()).replace("actual provider result", "corrupted provider result"));
        assertThatThrownBy(() -> independentlyRead(evidence, evidence.observation())).hasMessageContaining("evidence hash");
    }

    @Test
    void identicalDecisionReplayIsIdempotentAndAddsNoCanonicalExecution() throws Exception {
        Evidence evidence = lateEvidence();
        Path replay = Files.copy(evidence.observation(), work.resolve("identical-replay.json"));
        Read first = independentlyRead(evidence, evidence.observation());
        Read again = independentlyRead(evidence, replay);
        assertThat(NeutralJson.write(again.originalDecision())).isEqualTo(NeutralJson.write(first.originalDecision()));
        assertThat(NeutralJson.write(again.originalTermination())).isEqualTo(NeutralJson.write(first.originalTermination()));
        assertThat(PackageReader.open(evidence.events()).events("prompt_turn", 2)).hasSize(1);
    }

    @Test
    void aSuppliedPersistenceFailureReceiptCannotBeUpgradedByLaterEvidence() throws Exception {
        Evidence evidence = lateEvidence("FAILED", "PERSISTENCE_FAILED", 4500);
        Read read = independentlyRead(evidence, evidence.observation());
        assertThat(read.originalDecision().path("decision", "future_state").text()).isEqualTo("FAILED");
        assertThat(read.originalDecision().path("decision", "reason").text()).isEqualTo("PERSISTENCE_FAILED");
        assertThat(read.originalDecision().path("decision", "cause").text()).isEqualTo("supplied synthetic disk-full cause");
        assertThat(read.originalDecision().member("decided_elapsed_ms").scalarText()).isEqualTo("4500");
        assertThat(read.originalTermination().member("frozen_elapsed_ms").scalarText()).isEqualTo("14500");
        assertThat(read.providerOutcome()).isEqualTo("SUCCESS");
        assertThat(read.currentCapture()).isEqualTo("complete");
    }

    private Evidence lateEvidence() throws Exception {
        return lateEvidence("CANCELLED", "TIMEOUT", 5000);
    }

    private Evidence lateEvidence(String outcome, String reason, int decidedAt) throws Exception {
        Path pkg = work.resolve("late-capture");
        CountDownLatch forceEntered = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        WriterOptions options = WriterOptions.defaults()
                .withClock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
                .withFaults((operation, detail) -> {
                    if ("force".equals(operation)) {
                        forceEntered.countDown();
                        try {
                            // A harness guard, not an execution or cleanup policy timeout.
                            if (!releaseForce.await(10, TimeUnit.SECONDS)) { throw new IOException("test finalizer was not released"); }
                        }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    }
                });
        var lane = Executors.newSingleThreadExecutor();
        try {
            var pending = lane.submit(() -> writeEvidence(pkg, options));
            assertThat(forceEntered.await(10, TimeUnit.SECONDS)).as("actual writer reached final force, conceptual t=4s").isTrue();
            assertThat(pending.isDone()).as("bytes may exist but final write acknowledgment is withheld").isFalse();
            // Client supplies this frozen decision. No Client timer/arbitration is implemented by this fixture.
            Jv.Obj decision = new Jv.Obj().put("id", "decision-fixture").put("future_state", outcome).put("reason", reason);
            if ("FAILED".equals(outcome)) { decision.put("cause", "supplied synthetic disk-full cause"); }
            String terminal = Jv.write(new Jv.Obj().put("evidence_class", "synthetic supplied Client decision, not runtime arbitration")
                    .put("client_execution_uid", EXECUTION).put("client_session_uid", "client-session-fixture")
                    .put("client_connection_uid", "client-connection-fixture").put("decision", decision)
                    .put("provider_observation", "SUCCESS").put("decided_elapsed_ms", decidedAt)
                    .put("capture_at_terminal", new Jv.Obj().put("status", "PENDING"))
                    .put("caller_wait", new Jv.Obj().put("elapsed_ms", 1000).put("execution_cancelled_by_wait", false)));
            Path receipt = Files.writeString(work.resolve("terminal-receipt.json"), terminal, StandardOpenOption.CREATE_NEW);
            String receiptHash = NativeArtifact.sha256(Files.readAllBytes(receipt));
            String terminationBytes = Jv.write(new Jv.Obj().put("client_execution_uid", EXECUTION)
                    .put("terminal_decision_uid", "decision-fixture").put("original_receipt_sha256", receiptHash)
                    .put("cleanup_confirmed", false).put("frozen_elapsed_ms", decidedAt + 10000)
                    .put("cleanup_started_elapsed_ms", decidedAt).put("cleanup_allowance_ms", 10000)
                    .put("outstanding", Jv.arr("required-finalizer")));
            Path termination = Files.writeString(work.resolve("termination-receipt.json"), terminationBytes, StandardOpenOption.CREATE_NEW);
            String terminationHash = NativeArtifact.sha256(Files.readAllBytes(termination));
            OcsfPackage visible = PackageReader.open(pkg);
            assertThat(visible.refused()).isFalse();
            assertThat(visible.events("prompt_turn", 2)).singleElement().satisfies(event ->
                    assertThat(event.get("status_id").scalarText()).isEqualTo("1"));
            assertThat(NeutralJson.readDocument(receipt).path("decision", "future_state").text()).isEqualTo(outcome);
            assertThat(pending.isDone()).as("deadline/termination recording does not force-close the blocked sink").isFalse();
            releaseForce.countDown(); // conceptual t=16s: after accepted timeout and frozen termination(false)
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(OcsfRunWriter.State.COMPLETED);
            Path detached = Files.createDirectory(work.resolve("detached"));
            Path events = Files.copy(pkg.resolve("events.jsonl"), detached.resolve("events.jsonl"));
            String eventsHash = NativeArtifact.sha256(Files.readAllBytes(events));
            Jv.Obj association = new Jv.Obj().put("evidence_class", "synthetic late-evidence association; proposed representation")
                    .put("client_execution_uid", EXECUTION).put("client_session_uid", "client-session-fixture")
                    .put("client_connection_uid", "client-connection-fixture")
                    .put("terminal_decision_uid", "decision-fixture").put("observed_elapsed_ms", 16000)
                    .put("accepted_execution_decision", decision)
                    .put("original_terminal_receipt", new Jv.Obj().put("path", "terminal-receipt.json").put("sha256", receiptHash))
                    .put("termination_receipt", new Jv.Obj().put("path", "termination-receipt.json").put("sha256", terminationHash))
                    .put("evidence", new Jv.Obj().put("path", "detached/events.jsonl").put("sha256", eventsHash))
                    .put("capture_completion", "complete");
            Path observation = Files.writeString(work.resolve("late-observation.json"), Jv.write(association), StandardOpenOption.CREATE_NEW);
            return new Evidence(receipt, receiptHash, termination, terminationHash, observation, events);
        }
        finally {
            releaseForce.countDown();
            lane.shutdown();
            assertThat(lane.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private OcsfRunWriter.State writeEvidence(Path pkg, WriterOptions options) {
        RunDescriptor run = new RunDescriptor("journal-storage-fixture", 0, "native-session-fixture", options.clock().instant(),
                new RunDescriptor.Cli("Claude Code", "2.1.304", "Anthropic"),
                new Jv.Obj().put("client_execution_uid", EXECUTION).put("client_session_uid", "client-session-fixture")
                        .put("client_connection_uid", "client-connection-fixture"),
                new Jv.Obj().put("label", "synthetic late finalization, not a Client runtime or retained-session qualification"));
        byte[] bytes = """
                {"type":"system","subtype":"init","session_id":"native-session-fixture","claude_code_version":"2.1.304"}
                {"type":"result","subtype":"success","is_error":false,"session_id":"native-session-fixture","uuid":"result-fixture","result":"actual provider result","usage":{"input_tokens":1,"output_tokens":2}}
                """.getBytes(StandardCharsets.UTF_8);
        try (OcsfRunWriter writer = OcsfRunWriter.open(pkg, run, SchemaPin.bundled(), options)) {
            writer.processOutcome(new Jv.Obj().put("status", "unknown").put("exit_code", Jv.Null.INSTANCE));
            SourceInput source = writer.source(new SourceDescriptor("stdout.bin", 1, "claude_stream", "stdout", "synthetic original bytes", null));
            source.bytes(bytes, options.clock().instant());
            source.end(SourceInput.End.EOF, options.clock().instant());
            return writer.complete();
        }
    }

    /** Test-owned authority/link checks over the real independent canonical reader, not a shipped new schema validator. */
    private Read independentlyRead(Evidence trusted, Path observation) throws IOException {
        Json late = NeutralJson.readDocument(observation);
        require(EXECUTION.equals(text(late, "client_execution_uid")), "wrong execution");
        require(late.member("execution_outcome") == null, "late execution promotion");
        Json binding = late.member("original_terminal_receipt");
        require(binding != null, "missing original terminal receipt");
        Path receipt = work.resolve(text(binding, "path"));
        String receiptHash = NativeArtifact.sha256(Files.readAllBytes(receipt));
        require(trusted.receiptHash().equals(receiptHash) && receiptHash.equals(text(binding, "sha256")), "original terminal receipt hash");
        Json decision = NeutralJson.readDocument(receipt);
        require(EXECUTION.equals(text(decision, "client_execution_uid")), "receipt execution");
        require(text(decision, "client_session_uid").equals(text(late, "client_session_uid"))
                && text(decision, "client_connection_uid").equals(text(late, "client_connection_uid")), "session/connection association");
        Json tBinding = late.member("termination_receipt");
        require(tBinding != null, "missing termination receipt");
        Path tPath = work.resolve(text(tBinding, "path"));
        String tHash = NativeArtifact.sha256(Files.readAllBytes(tPath));
        require(trusted.terminationHash().equals(tHash) && tHash.equals(text(tBinding, "sha256")), "termination receipt hash");
        Json termination = NeutralJson.readDocument(tPath);
        require(EXECUTION.equals(text(termination, "client_execution_uid")), "termination execution");
        String decisionId = text(decision.member("decision"), "id");
        require(decisionId.equals(text(late, "terminal_decision_uid"))
                && decisionId.equals(text(termination, "terminal_decision_uid")), "decision identity");
        require(trusted.receiptHash().equals(text(termination, "original_receipt_sha256")), "termination binding to R");
        require(Json.sameValue(decision.member("decision"), late.member("accepted_execution_decision")), "changed terminal decision");
        Path events = work.resolve(text(late.member("evidence"), "path"));
        require(NativeArtifact.sha256(Files.readAllBytes(events)).equals(text(late.member("evidence"), "sha256")), "evidence hash");
        OcsfPackage canonical = PackageReader.open(events);
        require(!canonical.refused() && canonical.diagnostics(Diagnostic.Severity.DEFECT).isEmpty(), "canonical defects");
        require(EXECUTION.equals(canonical.manifest.text("run", "launch_settings", "client_execution_uid")), "canonical execution");
        require(text(late, "client_connection_uid").equals(canonical.manifest.text("run", "launch_settings", "client_connection_uid")), "canonical connection");
        require(!EXECUTION.equals(canonical.manifest.runUid()), "storage identity conflated with Client execution");
        var terminal = canonical.events("prompt_turn", 2);
        require(terminal.size() == 1 && "1".equals(terminal.get(0).get("status_id").scalarText()), "provider outcome");
        require(canonical.manifest.json().path("recording", "process", "exit_code").isNull(), "invented physical exit");
        require("complete".equals(canonical.manifest.text("recording", "acquisition", "status")), "late capture extent");
        require(canonical.manifest.text("recording", "acquisition", "status").equals(text(late, "capture_completion")), "capture claim differs");
        return new Read(decision, termination, "SUCCESS", text(late, "capture_completion"));
    }

    private static String text(Json object, String key) {
        require(object != null && object.member(key) != null, "missing " + key);
        return object.member(key).text();
    }
    private static void require(boolean condition, String detail) {
        if (!condition) { throw new IllegalArgumentException(detail); }
    }
}
