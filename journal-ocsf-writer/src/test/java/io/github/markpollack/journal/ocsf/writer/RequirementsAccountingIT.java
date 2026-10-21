package io.github.markpollack.journal.ocsf.writer;

import io.github.markpollack.judge.ai.requirements.EarsRequirement;
import io.github.markpollack.judge.ai.requirements.Rfc2119Requirement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The verify-phase accounting of every rule and criterion (RULE-36, work order ACCEPT-1 §1): for each criterion, the
 * executable checks that establish it in <em>this build</em>, their evidence class and scope, or a named gap; for
 * each rule, the criteria and direct checks whose conjunction establishes its full obligation. Nothing is counted
 * from a name alone: an oracle item reads the status the requirements reporter persisted in this build; a JUnit item
 * reads the surefire result of the named method from this build's reports (not found or not run is an ERROR); a
 * validator item reads a committed upstream-validation summary and requires its recorded hash to equal the current
 * event file; live items are informational pointers to private steward evidence and establish nothing here; deferred
 * and unexercised items are gaps with their reason and are never PASS.
 *
 * <p>Reduction: ERROR (a missing instrument) over FAIL over ABSTAIN (a gap, a gated skip, or a partially established
 * rule) over PASS, scoped to what the items actually covered. The build fails on any ERROR or FAIL; the complete
 * accounting is written to {@code target/requirements-accounting/ACCOUNTING.md} and {@code accounting.json} for the
 * acceptance review, together with the corruption-control roster of §4 (each control a test that ran and passed).
 * Evidence classes are kept apart: ORACLE (independent oracle on committed fixtures), JUNIT (deterministic Java
 * checks, including stand-in processes), VALIDATOR (pinned upstream tool, committed result), LIVE (steward evidence,
 * informational), DEFERRED and UNEXERCISED (named gaps for Owner disposition).
 */
class RequirementsAccountingIT {

    static final Path WRITER = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
    static final Path TCK = WRITER.resolve("..").resolve("journal-capture-tck").normalize();
    static final Path REQUIREMENTS = TCK.resolve("src/test/resources/requirements/agent-journal");
    static final Path FIXTURES = TCK.resolve("src/test/resources/ocsf/fixtures");
    static final Path VALIDATION = TCK.resolve("src/test/resources/ocsf/validation");

    enum Kind { ORACLE, JUNIT, VALIDATOR, LIVE, DEFERRED, UNEXERCISED, SEPARATE, NOTE }

    enum Status { PASS, ABSTAIN, FAIL, ERROR, INFO }

    /** One piece of evidence: what kind, its reference, and the CLI/version/route or scenario scope it covers. */
    record Item(Kind kind, String ref, String scope) {
    }

    record Outcome(Item item, Status status, String detail) {
    }

    /** The common scopes, stated once. */
    static final String CLAUDE_ALL = "Claude Code 2.1.299 sdk (sanitized real fixture), 2.1.304 stdout (sanitized live fixtures)";
    static final String CODEX_ALL = "Codex CLI 0.160.1 stdout (labelled synthetic and sanitized live fixtures)";
    static final String BOTH = CLAUDE_ALL + "; " + CODEX_ALL;
    static final String STAND_IN = "stand-in CLI processes (shell scripts replaying committed streams; labelled test-only)";
    static final String LIVE_RUNS = "steward evidence 2026-10-21-accept-1-live: claude-features-1, claude-denial-1, claude-cancel-1 (Claude Code 2.1.304), codex-features-1, codex-cancel-1 (Codex CLI 0.160.1)";

    static Item oracle(String criterion, String scope) {
        return new Item(Kind.ORACLE, criterion, scope);
    }

    static Item junit(String ref, String scope) {
        return new Item(Kind.JUNIT, ref, scope);
    }

    static Item validator(String scope) {
        return new Item(Kind.VALIDATOR, "ocsf/validation/*.json", scope);
    }

    static Item live(String what) {
        return new Item(Kind.LIVE, what, LIVE_RUNS);
    }

    static Item deferred(String reason) {
        return new Item(Kind.DEFERRED, reason, "proposed deferral for Owner disposition");
    }

    static Item unexercised(String reason) {
        return new Item(Kind.UNEXERCISED, reason, "named gap");
    }

    /** A measurement taken by a separate, documented step outside this build (reported apart, never a pass here). */
    static Item separate(String what) {
        return new Item(Kind.SEPARATE, what, "separate measurement");
    }

    /** A scope statement that establishes nothing and withholds nothing: what was not observed and is therefore not claimed. */
    static Item note(String text) {
        return new Item(Kind.NOTE, text, "scope note");
    }

    /** Criterion to the evidence that establishes it in this build. Every criterion of the roster must appear here. */
    static final Map<String, List<Item>> CRITERIA = new LinkedHashMap<>();
    static {
        CRITERIA.put("UC1-AC1", List.of(junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN),
                junit("LiveCaptureSeamTest#aStreamPipedToTheCaptureSeamIsRecordedWithItsReportedVersionAndJudgedAgainstTheBytes", "recorded Claude 2.1.299 stream through the stdin seam"),
                junit("LiveFixtureFeaturesTest#theClaudeFeatureRecordingExhibitsToolsChildrenFailureUsageAndRetainedFamilies", CLAUDE_ALL),
                junit("LiveFixtureFeaturesTest#theCodexRecordingExhibitsOperationsFailureRepetitionAndUsage", CODEX_ALL), live("both CLIs launched by CliCapture, streams normalized live")));
        CRITERIA.put("UC1-AC2", List.of(deferred("no session-only CLI is in the two-CLI scope: Claude Code and Codex both offer a stream and are captured from it; the import mechanism exists (PrefixReplayTest on a rollout) but the selection rule for a session-only CLI is unexercised and remains an open obligation, not NOT_APPLICABLE")));
        CRITERIA.put("UC1-AC3", List.of(junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN + ": byte-equal archive, sha256, parsed=false, not a declared source"),
                junit("CliCaptureTest#codexVersionIsDiscoveredOnceByTheDocumentedCommandAndTheThreadIdBecomesTheSession", STAND_IN + ": rollout archived by thread id"),
                live("native session files archived by hash on all five runs")));
        CRITERIA.put("UC1-AC4", List.of(junit("CliCaptureTest#theArchiveContentsCannotChangeTheNormalizedStreamOutput", STAND_IN + ": identical output whatever the archive holds, including a non-JSON archive"),
                junit("PinsTest#mappingContractsDeclareEveryRuleTheMappersExecute", "the stream contract states its unavailable_at_source facts (final per-response usage, the main thread's prompt)")));
        CRITERIA.put("UC1-AC5", List.of(oracle("UC1-AC5", "labelled synthetic 2.1.999 Claude stream (syn-claude-untested-version)")));
        CRITERIA.put("UC1-AC6", List.of(oracle("UC1-AC6", BOTH)));
        CRITERIA.put("UC1-AC7", List.of(junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN + ": the frozen denominator is the stream alone while the archive exists"),
                junit("FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource", "frozen manifest defines the denominator")));
        CRITERIA.put("UC2-AC1", List.of(junit("LiveCaptureSeamTest#aStreamPipedToTheCaptureSeamIsRecordedWithItsReportedVersionAndJudgedAgainstTheBytes", "Claude 2.1.299 (seam)"),
                junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN + ": no version at launch, reported by the stream, no version command"),
                junit("OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect", "settings values exact"), live("Claude 2.1.304 reported by the stream on three runs")));
        CRITERIA.put("UC2-AC2", List.of(junit("CliCaptureTest#codexVersionIsDiscoveredOnceByTheDocumentedCommandAndTheThreadIdBecomesTheSession", STAND_IN + ": codex --version counted once; basis declared"),
                live("Codex 0.160.1 discovered once on two runs")));
        CRITERIA.put("UC2-AC3", List.of(junit("LiveCaptureSeamTest#aStreamPipedToTheCaptureSeamIsRecordedWithItsReportedVersionAndJudgedAgainstTheBytes", "records before the version are persisted"),
                junit("UntestedCompatibilityTest#aVersionNoEntryCoversIsRetainedFlaggedAndNotNormalized", "records_before_selection recorded"),
                junit("WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof", "prefix persistence")));
        CRITERIA.put("UC2-AC4", List.of(oracle("UC2-AC4", BOTH), junit("CompatibilityTest#exactTestedVersionsAndInclusiveBoundariesSelectTheOneEntry", "registry selection"),
                junit("UntestedCompatibilityTest#aTestedVersionOnItsRouteIsSelectedAndVerified", "declaration in parent and child files")));
        CRITERIA.put("UC2-AC5", List.of(oracle("UC2-AC5", "labelled synthetic 2.1.999 Claude stream"), junit("UntestedCompatibilityTest#aVersionNoEntryCoversIsRetainedFlaggedAndNotNormalized", "retained, flagged, reader warns"),
                junit("CompatibilityTest#outsideEveryRangeOrOffEveryKeyIsNoMatchAndTheNearestEntryIsNotSelected", "no nearest-entry selection")));
        CRITERIA.put("UC2-AC6", List.of(junit("CompatibilityTest#overlappingEntriesAreRejectedWhenTheRegistryLoads", "overlap, inverted range, duplicate id rejected at load")));
        CRITERIA.put("UC3-AC1", List.of(oracle("UC3-AC1", BOTH)));
        CRITERIA.put("UC3-AC2", List.of(oracle("UC3-AC2", BOTH)));
        CRITERIA.put("UC3-AC3", List.of(oracle("UC3-AC3", BOTH)));
        CRITERIA.put("UC3-AC4", List.of(oracle("UC3-AC4", BOTH)));
        CRITERIA.put("UC3-AC5", List.of(oracle("UC3-AC5", "Claude only: Codex's exec stream reports no cost (NOT_APPLICABLE there, not a pass)")));
        CRITERIA.put("UC3-AC6", List.of(oracle("UC3-AC6", BOTH)));
        CRITERIA.put("UC3-AC7", List.of(oracle("UC3-AC7", "Claude: accepted-by-rule decisions, an observed rejection at tool-call scope and the run-scope denial naming it (2.1.304 live denial fixture); Codex reports no decisions"),
                junit("LiveFixtureFeaturesTest#theDenialRecordingExhibitsAnObservedRejectionAtBothScopes", "observed rejection values")));
        CRITERIA.put("UC3-AC8", List.of(oracle("UC3-AC8", "Codex operations unattributed (the stream names no call id); Claude has no separately reported operations")));
        CRITERIA.put("UC3-AC9", List.of(junit("OcsfOracleValueCheckTest#responseContentPresentWhereTheSourceHasNoneIsADefect", "content absent stays absent"),
                junit("OcsfOracleMutationTest#aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero", "absence is not zero"),
                junit("OcsfOracleValueCheckTest#modelProviderIsReportedNotInferred", "provider never inferred")));
        CRITERIA.put("UC3-AC10", List.of(oracle("UC3-AC10", BOTH)));
        CRITERIA.put("UC3-AC11", List.of(oracle("UC3-AC11", BOTH)));
        CRITERIA.put("UC3-AC12", List.of(deferred("compact residual extraction is not implemented: whole records are retained beside portable fields (RULE-15); the roadmap places compaction after ACCEPT-1 (OCSF-3); size of whole-record retention is measured in the return")));
        CRITERIA.put("UC3-AC13", List.of(oracle("UC3-AC13", "Claude 2.1.304 child scope only: the sub-agent's prompt the stream reports; the main thread's -p prompt is not echoed on any tested route (unavailable at source, stated in the contract)"),
                junit("LiveFixtureFeaturesTest#theSubAgentPromptIsTheChildScopesVendorReportedPrompt", "exact text, no invented turn id")));
        CRITERIA.put("UC3-AC14", List.of(junit("FieldDispositionTest#everyObservedStreamFieldHasExactlyOneBackedDisposition", BOTH + ": every observed path has one backed disposition"),
                junit("FieldDispositionTest#aMissingDispositionABrokenReferenceAndAnUnexercisedClaimEachFail", "negative controls")));
        CRITERIA.put("UC3-AC15", List.of(oracle("UC3-AC15", BOTH)));
        CRITERIA.put("UC4-AC1", List.of(junit("WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof", "prefix readable while open"),
                junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN + ": read while the process runs"),
                junit("StandalonePrefixTest#aFlushedPrefixReadAloneIsAnOpenInterpretablePrefixAndTheCompletedFileCarriesItsFinalization", "file alone"), live("packages read 9 to 32 times before exit on each run")));
        CRITERIA.put("UC4-AC2", List.of(junit("WriterLifecycleTest#completeRequiresEveryDeclaredSourceToHaveEndedAndInputsCannotBeMixed", "completion rule"),
                junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN + ": EOF, exit 0, COMPLETED")));
        CRITERIA.put("UC4-AC3", List.of(oracle("UC4-AC3", "replay-interrupted 2.1.299 prefix; live cancelled Claude 2.1.304 and Codex 0.160.1 fixtures"),
                junit("CliCaptureTest#aDeliberateCancellationTerminatesTheProcessAndInterruptsTheRecordingWithTheReason", STAND_IN + ": SIGTERM via the handle, exit 143, reason recorded"),
                junit("CliCaptureLifecycleTest#aSilentCliIsCancelledAtTheDeadline", STAND_IN + ": the deadline fires without any output; 0 records, honest interruption"),
                junit("CliCaptureLifecycleTest#outputThenAStallIsCancelledAtTheDeadlineWithThePrefixKept", STAND_IN + ": the deadline fires during a stall; the prefix is kept and judged incomplete"),
                junit("CliCaptureLifecycleTest#aCliThatIgnoresTerminationAndKeepsStdoutOpenIsKilledAfterTheGrace", STAND_IN + ": SIGKILL after the grace, exit 137, prefix kept"),
                junit("CliCaptureLifecycleTest#aDescendantKeepingStdoutOpenAfterTheCliEndedIsKilledAndTheCaptureEnds", STAND_IN + ": orphaned descendant killed, capture ends"),
                live("deliberate cancellations of both CLIs")));
        CRITERIA.put("UC4-AC4", List.of(junit("WriterLifecycleTest#injectedWriteFailureLeavesAnHonestFailedPackage", "injected write failure"),
                junit("CliCaptureTest#aPersistenceFailureStopsTheProcessLeavesAFailedPackageAndKeepsTheIndependentCopyWhole", STAND_IN + ": writer failure stops the CLI")));
        CRITERIA.put("UC4-AC5", List.of(oracle("UC4-AC5", "interrupted and cancelled fixtures"),
                junit("CliCaptureTest#aNonZeroExitAfterACompleteStreamIsACompletelyAcquiredFailedRun", STAND_IN + ": complete acquisition, failed CLI outcome"),
                junit("CliCaptureTest#anAbnormalExitInsideARecordLeavesAnHonestIncompleteTailAndAFailedProcess", STAND_IN + ": finalized, acquisition incomplete")));
        CRITERIA.put("UC4-AC6", List.of(oracle("UC4-AC6", "Claude 2.1.299 fixture child and 2.1.304 live Agent child"),
                junit("WriterLifecycleTest#childRecordsAreRoutedToTheirOwnCoreAsTheyArrive", "routing as records arrive"),
                junit("StandaloneEventFileTest#theChildEventFileAloneCarriesItsOwnDeclarationsAndIsJudgedOnItsOwn", "child file alone")));
        CRITERIA.put("UC4-AC7", List.of(junit("OcsfOracleValueCheckTest#resultStructuredDetailAndMissingResultAreEachChecked", "missing result stays open"),
                junit("CodexExecStreamTest#aDroppedStartOrASwappedIdentityIsAPopulationDefect", "a start never completed stays an operation in progress"),
                note("scope: the open-request case is established on the labelled synthetic Codex stream; no live run left a request without a result (the cancelled Claude run ended on a whole result record), so live occurrence is not claimed")));
        CRITERIA.put("UC4-AC8", List.of(junit("CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy", STAND_IN + ": read before exit, judged against the independent copy frozen after acquisition"),
                live("five live runs through the launching capture, each judged by the oracle against its frozen independent copy; three ACCEPTED, two INCOMPLETE as cancelled")));
        CRITERIA.put("UC5-AC1", List.of(junit("StandaloneEventFileTest#theParentEventFileAloneIsInterpretedFromItsDeclarations", "parent alone"),
                junit("StandaloneEventFileTest#theWholeRunAloneIsAcceptedByTheOracleAndTheToolWithoutAnyManifest", "whole run alone"),
                junit("StandalonePrefixTest#aChildFileWrittenDuringTheRunIsInterpretableAloneBeforeTheRunEnds", "child file alone during the run"),
                junit("IsolatedBootstrapTest#aCopiedCoreWithItsChildBootstrapsWithoutAnythingElse", "copied core, no network, no cache")));
        CRITERIA.put("UC5-AC2", List.of(junit("StandaloneEventFileTest#removingOrAlteringAMandatoryDeclarationFailsInterpretationExplicitly", "nine removal/alteration controls"),
                junit("PackageReaderTest#missingDefinitionsRefuseInterpretationButKeepTheEvents", "refusal keeps events"), junit("PackageReaderTest#missingDefinitionReferenceIsADefect", "reference")));
        CRITERIA.put("UC5-AC3", List.of(oracle("UC5-AC3", BOTH), junit("SchemaCatalogTest#savedValuesAreCheckedAgainstDeclaredTypesEnumsAndRequirements", "types, enums, requirements"),
                junit("PackageReaderTest#typeMismatchAndMissingRequiredAreDefects", "diagnostics")));
        CRITERIA.put("UC5-AC4", List.of(oracle("UC5-AC4", BOTH), junit("FullOneReviewRegressionTest#aRenamedSourceIsBothAnOmissionAndAnUnexpectedSource", "renamed source")));
        CRITERIA.put("UC5-AC5", List.of(junit("FullOneReviewRegressionTest#aRequiredCheckMissingFromTheReportBlocksAcceptance", "registered required checks")));
        CRITERIA.put("UC5-AC6", List.of(junit("LiveFixtureFeaturesTest#everyFixtureStatesItsEvidenceClassAndLiveOnesCarryProvenance", "every committed fixture")));
        CRITERIA.put("UC5-AC7", List.of(junit("LiveFixtureFeaturesTest#theClaudeFeatureRecordingExhibitsToolsChildrenFailureUsageAndRetainedFamilies", CLAUDE_ALL),
                junit("LiveFixtureFeaturesTest#theDenialRecordingExhibitsAnObservedRejectionAtBothScopes", "Claude 2.1.304 denial"),
                junit("LiveFixtureFeaturesTest#theCancelledRecordingsAreHonestInterruptedPrefixes", "both CLIs cancelled"),
                junit("LiveFixtureFeaturesTest#theCodexRecordingExhibitsOperationsFailureRepetitionAndUsage", CODEX_ALL),
                junit("CodexExecStreamTest#controlVerifiesEveryFamilyWithStatedPopulationsAndIsAccepted", "labelled synthetic Codex stream"),
                junit("OcsfOracleFixtureTest#sanitizedClaudeStreamWithChildCoreIsJudgedPerCoreWithoutDefect", "sanitized real 2.1.299 stream"),
                note("not claimed (not observed on any tested route): media/multimodal blocks, redacted or encrypted reasoning, an interactively answered permission prompt (the bidirectional stream-json route), Codex sub-agents, Codex permission decisions, a live open request at interruption")));
        CRITERIA.put("UC5-AC8", List.of(junit("RequirementsRosterTest#everyRuleHeadingIsParsedExactlyOnceStrictlyAndMatchesThePinnedRoster", "37 rules, strict"),
                junit("RequirementsRosterTest#everyCriterionHeadingIsParsedExactlyOnceStrictlyAndMatchesThePinnedCounts", "49 criteria, strict"),
                junit("NativeRequirementTest#everyNativeRfcSpecificationCarriesTheCompleteStatementAndThePromptRendersIt", "complete statements (Agent Judge 0.19.0)")));
        CRITERIA.put("UC5-AC9", List.of(oracle("UC5-AC9", "reporter controls computed on the real reports")));
        CRITERIA.put("UC5-AC10", List.of(separate("mutation sensitivity is measured by the pit profiles of both modules (threshold 70%, mutators and targets unchanged) in detached worktrees of the delivered revision, not in this build; killed, survived and uncovered counts are reported apart in the return")));
        CRITERIA.put("UC5-AC11", List.of(validator("every committed fixture core, execution records selected by the record rule; ocsf-toolkit v0.9.0, compiled schema ec1b99bc")));
        CRITERIA.put("UC5-AC12", List.of(oracle("UC5-AC12", "reporter controls"), junit("RequirementsReporterReproductionTest#rr02RemovingOneRequiredPropertyOrOneRequiredOwnerIsAnErrorNotAPass", "missing property or owner is ERROR")));
        CRITERIA.put("UC5-AC13", List.of(junit("MappingCatalogTest#theCommonConceptMatrixIsDerivedFromTheEntriesAndThisRunsOracleResultsWithStatesKeptSeparate", BOTH),
                junit("MappingCatalogTest#everyCatalogReferenceResolvesInJava", "reference validity")));
    }

    /** Rule to the criteria and direct checks whose conjunction establishes its full obligation. */
    record RuleEvidence(List<String> criteria, List<Item> direct, String note) {
    }

    static final Map<String, RuleEvidence> RULES = new LinkedHashMap<>();
    static {
        RULES.put("RULE-1", new RuleEvidence(List.of("UC1-AC1"), List.of(), "stream normalized for both CLIs"));
        RULES.put("RULE-2", new RuleEvidence(List.of("UC1-AC2"), List.of(), "session-only import: deferred, no such CLI in scope"));
        RULES.put("RULE-3", new RuleEvidence(List.of("UC1-AC5"), List.of(), ""));
        RULES.put("RULE-4", new RuleEvidence(List.of("UC1-AC3", "UC1-AC7"), List.of(), ""));
        RULES.put("RULE-5", new RuleEvidence(List.of("UC1-AC4"), List.of(junit("CliCaptureTest#theArchiveContentsCannotChangeTheNormalizedStreamOutput", "archive never opened; version from the stream")), ""));
        RULES.put("RULE-6", new RuleEvidence(List.of("UC1-AC4"), List.of(), "stated limitations in the contract's unavailable_at_source"));
        RULES.put("RULE-7", new RuleEvidence(List.of("UC2-AC1"), List.of(), ""));
        RULES.put("RULE-8", new RuleEvidence(List.of("UC2-AC2"), List.of(), ""));
        RULES.put("RULE-9", new RuleEvidence(List.of("UC2-AC3"), List.of(), ""));
        RULES.put("RULE-10", new RuleEvidence(List.of("UC2-AC4", "UC2-AC6"), List.of(junit("CompatibilityTest#theBundledRegistryLoadsAndNamesContractsMappersAndSpecificationsThatExist", "every entry names an existing specification, schema, contract and mapper")), ""));
        RULES.put("RULE-11", new RuleEvidence(List.of("UC5-AC3", "UC5-AC11"), List.of(junit("PinsTest#bundledExtensionSourceListsEveryFileOfTheExtensionDirectory", "extension source complete")), ""));
        RULES.put("RULE-12", new RuleEvidence(List.of("UC3-AC4", "UC3-AC5", "UC3-AC7", "UC3-AC9", "UC3-AC15", "UC4-AC7"), List.of(), ""));
        RULES.put("RULE-13", new RuleEvidence(List.of("UC3-AC1", "UC3-AC2", "UC3-AC3", "UC3-AC6", "UC3-AC10", "UC3-AC13"), List.of(), ""));
        RULES.put("RULE-14", new RuleEvidence(List.of("UC3-AC1"), List.of(junit("OcsfOracleMutationTest#identicalResultsSwappedBetweenOwnersAreADefect", "no value-search ownership"),
                junit("OcsfOracleMutationTest#resultOwnedByAnIdQuotedInsideItsOwnTextIsADefect", "no id-in-text ownership"), junit("OcsfOracleMutationTest#largeIntegerAndHighPrecisionDecimalAreExact", "no thresholds")), ""));
        RULES.put("RULE-15", new RuleEvidence(List.of("UC3-AC6"), List.of(junit("OcsfOracleMutationTest#normalizedRecordRetainedOnlyAsFallbackIsAMissingNormalization", "fallback is retention, not normalization")), "MAY: whole-record retention in force"));
        RULES.put("RULE-16", new RuleEvidence(List.of(), List.of(junit("FieldDispositionTest#everyObservedStreamFieldHasExactlyOneBackedDisposition", "no field is dispositioned omitted: no omission rule exists, none is applied"),
                junit("AnalysisViewsTest#identicalResultContentUnderTwoOwnersIsKeptSeparate", "identical values under distinct owners stay distinct")), "no residual omission exists yet; the obligation binds any future compaction (UC3-AC12 deferred)"));
        RULES.put("RULE-17", new RuleEvidence(List.of(), List.of(junit("OracleFullSourceTest#changingARetainedNumberIsAStructureDefectAndRespellingItIsARepresentationDifference", "value vs representation"),
                junit("OcsfOracleMutationTest#costSpellingIsRepresentationValueIsValue", "spelling")), "MAY"));
        RULES.put("RULE-18", new RuleEvidence(List.of("UC4-AC8"), List.of(junit("WriterLifecycleTest#captureFacingRecordInputProducesTheSameBytesAsChunkedReplay", "one writer, same bytes")), ""));
        RULES.put("RULE-19", new RuleEvidence(List.of("UC4-AC1"), List.of(), ""));
        RULES.put("RULE-20", new RuleEvidence(List.of("UC4-AC2", "UC4-AC3", "UC4-AC4", "UC4-AC5"), List.of(), ""));
        RULES.put("RULE-21", new RuleEvidence(List.of("UC1-AC6", "UC3-AC7", "UC3-AC8", "UC3-AC11", "UC4-AC6"), List.of(junit("OcsfOracleValueCheckTest#sessionOrNativeIdentityCopiedWronglyIsADefect", "native ids in their scope")), ""));
        RULES.put("RULE-22", new RuleEvidence(List.of(), List.of(junit("CodexExecStreamTest#viewsMatchTheIndependentlyAuthoredAnswersAndStateTheirCountUnits", "views are test utilities with stated count units"),
                junit("AnalysisViewsTest#usageSelectsOneApplicableFinalPerGroupAndNeverSums", "no summed totals")), "the writer computes no counts; enforced by review of the mapper code"));
        RULES.put("RULE-23", new RuleEvidence(List.of(), List.of(new Item(Kind.JUNIT, "RequirementsEvidenceTest#everyAssessedCriterionIsEstablishedOnAllItsObligationsAndEveryOtherRequirementIsReportedNotAssessed", "writer pom: no evaluation or model dependency outside test scope")), "also checked directly by this accounting (writerPomIsFreeOfRuntimeEvaluationDependencies)"));
        RULES.put("RULE-24", new RuleEvidence(List.of("UC5-AC1", "UC4-AC6"), List.of(), ""));
        RULES.put("RULE-25", new RuleEvidence(List.of("UC5-AC1", "UC5-AC2"), List.of(), ""));
        RULES.put("RULE-26", new RuleEvidence(List.of("UC5-AC1"), List.of(junit("OcsfOracleMutationTest#removedWholeRecordIsAGapInCoverageAndReconstruction", "judged without optional files"),
                junit("DeclaredFileDiagnosticsTest#anUnreadableAncillaryManifestIsADefectAndTheFileIsReadAlone", "run.json not needed")), ""));
        RULES.put("RULE-27", new RuleEvidence(List.of(), List.of(unexercised("no external feedback producer is in the two-CLI scope; feedback.jsonl durability was not exercised and is not claimed")), ""));
        RULES.put("RULE-28", new RuleEvidence(List.of("UC5-AC7"), List.of(), ""));
        RULES.put("RULE-29", new RuleEvidence(List.of("UC5-AC4", "UC5-AC5"), List.of(junit("OcsfOracleMutationTest#refusedPackageIsUnsupportedNotVerified", "refusal is not verification")), "the TCK never calls the writer: by construction of the module dependency (writer -> TCK, test scope only)"));
        RULES.put("RULE-30", new RuleEvidence(List.of("UC5-AC6"), List.of(), ""));
        RULES.put("RULE-31", new RuleEvidence(List.of("UC5-AC6"), List.of(), "provenance manifests with hashes and the recorded sanitization steps on every live-derived fixture; the private captures stay in the steward"));
        RULES.put("RULE-32", new RuleEvidence(List.of("UC5-AC8", "UC5-AC9"), List.of(), ""));
        RULES.put("RULE-33", new RuleEvidence(List.of("UC2-AC5"), List.of(junit("UntestedCompatibilityTest#normalizingAfterANoMatchSelectionIsADefect", "normalization after no match is a defect")), ""));
        RULES.put("RULE-34", new RuleEvidence(List.of("UC3-AC14"), List.of(), ""));
        RULES.put("RULE-35", new RuleEvidence(List.of("UC5-AC13"), List.of(), ""));
        RULES.put("RULE-36", new RuleEvidence(List.of("UC5-AC12"), List.of(), ""));
        RULES.put("RULE-37", new RuleEvidence(List.of(), List.of(junit("StandaloneEventFileTest#theParentEventFileAloneIsInterpretedFromItsDeclarations", "definitions once per file, not per event")), "no mapping language, generated reader or model call exists in the build; enforced by review beyond the executable check"));
    }

    /** The §4 corruption-control roster: each control must have run and passed in this build (positive controls live beside them in the same tests). */
    static final Map<String, List<String>> CONTROLS = new LinkedHashMap<>();
    static {
        CONTROLS.put("one required field, check or owner missing", List.of("RequirementsReporterReproductionTest#rr02RemovingOneRequiredPropertyOrOneRequiredOwnerIsAnErrorNotAPass", "FullOneReviewRegressionTest#aRequiredCheckMissingFromTheReportBlocksAcceptance"));
        CONTROLS.put("wrong portable value with unchanged retained source", List.of("OcsfOracleMutationTest#jo02WrongNormalizedCostAmountWithUnchangedRetainedSourceIsDefective", "FullOneReviewRegressionTest#changedRenderedTextIsAContextValueDefectWhileTheRawRecordIsIntact", "OcsfOracleMutationTest#requestArgumentsAndToolKindAreExact", "OcsfOracleValueCheckTest#sessionOrNativeIdentityCopiedWronglyIsADefect"));
        CONTROLS.put("equal results swapped between tools", List.of("OcsfOracleMutationTest#identicalResultsSwappedBetweenOwnersAreADefect", "OracleFullSourceTest#swappingResultOwnersBetweenRecordsIsAnOwnershipDefectOnBothSources"));
        CONTROLS.put("duplicate or wrongly scoped usage", List.of("OcsfOracleMutationTest#provisionalUsageMarkedFinalIsADefect", "OracleFullSourceTest#alteringNormalizedSessionUsageWhileTheRawRecordIsIntactIsAValueDefect", "OcsfOracleMutationTest#missingDuplicatedAndReorderedRowsAreDefects", "AnalysisViewsTest#usageSelectsOneApplicableFinalPerGroupAndNeverSums"));
        CONTROLS.put("dropped child", List.of("OcsfOracleMutationTest#aChildLinkedToTheWrongSpawnOrDroppedIsADefect", "PackageReaderTest#missingOrMislinkedChildIsADefect"));
        CONTROLS.put("incorrect order", List.of("OcsfOracleMutationTest#missingDuplicatedAndReorderedRowsAreDefects", "OcsfOracleValueCheckTest#recordIndexGoingBackwardsWithinAnArtifactIsAnOrderDefect"));
        CONTROLS.put("unknown record dropped", List.of("CodexExecStreamTest#anAgentMessageTextChangedOrAnUnknownRecordDroppedIsDetected", "OcsfOracleMutationTest#removedWholeRecordIsAGapInCoverageAndReconstruction"));
        CONTROLS.put("absent versus zero or null", List.of("OcsfOracleMutationTest#aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero", "OcsfOracleMutationTest#jo03RetainedJsonNumberTurnedIntoBooleanTrueIsDefective"));
        CONTROLS.put("false completion", List.of("WriterAcquisitionRegressionTest#aSourceEndedAsInterruptedThenCompletedIsPreservedButNotAcceptedAsComplete", "FullOneReviewRegressionTest#anHonestlyInterruptedSourceIsPreservedButNotAcceptedAsComplete", "CliCaptureTest#anAbnormalExitInsideARecordLeavesAnHonestIncompleteTailAndAFailedProcess"));
        CONTROLS.put("unknown version treated as compatible", List.of("UntestedCompatibilityTest#normalizingAfterANoMatchSelectionIsADefect", "CompatibilityTest#outsideEveryRangeOrOffEveryKeyIsNoMatchAndTheNearestEntryIsNotSelected"));
        CONTROLS.put("missing, altered or late declarations", List.of("StandaloneEventFileTest#removingOrAlteringAMandatoryDeclarationFailsInterpretationExplicitly", "DeclaredFileDiagnosticsTest#malformedDuplicateUnknownAndConflictingDeclarationsAreEachReported", "DeclaredFileDiagnosticsTest#aContractDeclarationAlteredUnderItsHashAndAnAttributeUsedBeforeItsAmendmentAreDefects"));
    }

    @Test
    void everyRuleAndCriterionIsAccountedForWithExecutedEvidenceOrANamedGap() throws Exception {
        List<EarsRequirement> criteria = EarsRequirement.fromStrict(REQUIREMENTS.resolve("criteria.md"), "sha256:" + sha256(Files.readAllBytes(REQUIREMENTS.resolve("criteria.md"))));
        List<Rfc2119Requirement> rules = Rfc2119Requirement.fromStrict(REQUIREMENTS.resolve("rules.md"), "sha256:" + sha256(Files.readAllBytes(REQUIREMENTS.resolve("rules.md"))));
        assertThat(CRITERIA.keySet()).as("every criterion accounted, none invented").containsExactlyElementsOf(criteria.stream().map(EarsRequirement::id).toList());
        assertThat(RULES.keySet()).as("every rule accounted, none invented").containsExactlyElementsOf(rules.stream().map(Rfc2119Requirement::id).toList());

        Map<String, Surefire> surefire = Surefire.read(List.of(TCK.resolve("target/surefire-reports"), WRITER.resolve("target/surefire-reports")));
        Map<String, Map<String, Object>> oracleStatus = oracleStatus();
        Map<String, Outcome> criterionOutcomes = new LinkedHashMap<>();
        Map<String, Status> criterionStatus = new LinkedHashMap<>();
        Map<String, List<Outcome>> criterionItems = new LinkedHashMap<>();
        for (EarsRequirement c : criteria) {
            List<Outcome> outcomes = new ArrayList<>();
            for (Item item : CRITERIA.get(c.id())) {
                outcomes.add(evaluate(item, surefire, oracleStatus));
            }
            criterionItems.put(c.id(), outcomes);
            criterionStatus.put(c.id(), reduce(outcomes));
        }
        Map<String, Status> ruleStatus = new LinkedHashMap<>();
        Map<String, List<Outcome>> ruleItems = new LinkedHashMap<>();
        Map<String, String> ruleDetail = new LinkedHashMap<>();
        for (Rfc2119Requirement r : rules) {
            RuleEvidence ev = RULES.get(r.id());
            List<Outcome> outcomes = new ArrayList<>();
            List<String> notPass = new ArrayList<>();
            for (String cid : ev.criteria()) {
                Status s = criterionStatus.get(cid);
                outcomes.add(new Outcome(new Item(Kind.ORACLE, cid, "criterion"), s, "criterion " + cid + " " + s));
                if (s != Status.PASS) {
                    notPass.add(cid + " " + s);
                }
            }
            for (Item item : ev.direct()) {
                Outcome o = evaluate(item, surefire, oracleStatus);
                outcomes.add(o);
                if (o.status() != Status.PASS && o.status() != Status.INFO) {
                    notPass.add(item.ref() + " " + o.status());
                }
            }
            if (ev.criteria().isEmpty() && ev.direct().isEmpty()) {
                outcomes.add(new Outcome(unexercised("no executable evidence named"), Status.ABSTAIN, "no evidence"));
            }
            ruleItems.put(r.id(), outcomes);
            ruleStatus.put(r.id(), reduce(outcomes));
            ruleDetail.put(r.id(), notPass.isEmpty() ? "every constituent established" : "not fully established: " + String.join(", ", notPass));
        }

        // the §4 controls: each ran and passed in this build
        Map<String, List<Outcome>> controls = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : CONTROLS.entrySet()) {
            List<Outcome> out = new ArrayList<>();
            for (String ref : e.getValue()) {
                out.add(evaluate(junit(ref, e.getKey()), surefire, oracleStatus));
            }
            controls.put(e.getKey(), out);
        }

        write(criteria, rules, criterionItems, criterionStatus, ruleItems, ruleStatus, ruleDetail, controls, surefire.size());

        List<String> errors = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        criterionItems.forEach((id, outs) -> outs.forEach(o -> {
            if (o.status() == Status.ERROR) {
                errors.add(id + ": " + o.item().ref() + ": " + o.detail());
            }
            if (o.status() == Status.FAIL) {
                failures.add(id + ": " + o.item().ref() + ": " + o.detail());
            }
        }));
        ruleItems.forEach((id, outs) -> outs.forEach(o -> {
            if (o.status() == Status.ERROR && o.item().kind() != Kind.ORACLE) {
                errors.add(id + ": " + o.item().ref() + ": " + o.detail());
            }
            if (o.status() == Status.FAIL && o.item().kind() != Kind.ORACLE) {
                failures.add(id + ": " + o.item().ref() + ": " + o.detail());
            }
        }));
        controls.forEach((name, outs) -> outs.forEach(o -> {
            if (o.status() != Status.PASS) {
                errors.add("control '" + name + "': " + o.item().ref() + ": " + o.status() + " " + o.detail());
            }
        }));
        assertThat(errors).as("missing instruments (evidence named but not executed in this build) and failed controls").isEmpty();
        assertThat(failures).as("failed obligations").isEmpty();
        long criteriaPass = criterionStatus.values().stream().filter(s -> s == Status.PASS).count();
        long rulesPass = ruleStatus.values().stream().filter(s -> s == Status.PASS).count();
        System.out.println("requirements accounting: criteria PASS " + criteriaPass + "/" + criteria.size() + " (" + statusCounts(criterionStatus) + "); rules PASS " + rulesPass + "/" + rules.size()
                + " (" + statusCounts(ruleStatus) + "); surefire results of " + surefire.size() + " test classes read");
    }

    /** RULE-23 directly: the writer's build file declares no evaluation or model dependency outside test scope. */
    @Test
    void writerPomIsFreeOfRuntimeEvaluationDependencies() throws IOException {
        String pom = Files.readString(WRITER.resolve("pom.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL).matcher(pom);
        while (m.find()) {
            String d = m.group(1);
            if (d.contains("agent-judge") || d.contains("spring-ai") || d.contains("langchain4j")) {
                assertThat(d).as("evaluation/model dependency must be test-scoped: " + d.strip()).contains("<scope>test</scope>");
            }
        }
    }

    // ---- evaluation ---------------------------------------------------------------------------------------------------

    static Outcome evaluate(Item item, Map<String, Surefire> surefire, Map<String, Map<String, Object>> oracle) throws IOException {
        switch (item.kind()) {
            case ORACLE -> {
                Map<String, Object> st = oracle.get(item.ref());
                if (st == null) {
                    return new Outcome(item, Status.ERROR, "no oracle status for " + item.ref() + " in target/requirements-evidence/criteria-status.json of this build");
                }
                String s = String.valueOf(st.get("status"));
                Status status = switch (s) {
                    case "PASS" -> Status.PASS;
                    case "FAIL" -> Status.FAIL;
                    case "ERROR" -> Status.ERROR;
                    default -> Status.ABSTAIN;
                };
                return new Outcome(item, status, "oracle reporter: " + s + "; " + String.valueOf(st.get("reasoning")).replaceAll("\\s+", " "));
            }
            case JUNIT -> {
                Surefire.Result r = Surefire.find(surefire, item.ref());
                if (r == null) {
                    return new Outcome(item, Status.ERROR, "test not found in this build's surefire reports (not executed)");
                }
                return switch (r.outcome()) {
                    case "passed" -> new Outcome(item, Status.PASS, "passed in " + r.className() + " (" + r.seconds() + " s)");
                    case "skipped" -> new Outcome(item, Status.ABSTAIN, "skipped (gated) in " + r.className() + ": " + r.message());
                    default -> new Outcome(item, Status.FAIL, r.outcome() + " in " + r.className() + ": " + r.message());
                };
            }
            case VALIDATOR -> {
                List<String> problems = new ArrayList<>();
                int cores = 0;
                try (Stream<Path> s = Files.list(VALIDATION)) {
                    for (Path summary : s.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                        cores++;
                        String text = Files.readString(summary, StandardCharsets.UTF_8);
                        String fixture = field(text, "fixture");
                        String core = field(text, "core");
                        String recorded = field(text, "events_jsonl_sha256");
                        Path events = FIXTURES.resolve(fixture).resolve("package").resolve("main".equals(core) ? "" : core).resolve("events.jsonl").normalize();
                        if (!Files.isRegularFile(events)) {
                            problems.add(summary.getFileName() + ": " + events + " does not exist");
                            continue;
                        }
                        String actual = sha256(Files.readAllBytes(events));
                        if (!actual.equals(recorded)) {
                            problems.add(summary.getFileName() + ": stale: recorded hash " + recorded.substring(0, 12) + " differs from the current event file " + actual.substring(0, 12) + "; rerun validate-fixtures.sh");
                        }
                        Matcher m = Pattern.compile("\"error_events\":\\s*(\\d+)").matcher(text);
                        if (!m.find() || !"0".equals(m.group(1))) {
                            problems.add(summary.getFileName() + ": error events " + (m.find(0) ? m.group(1) : "unknown"));
                        }
                    }
                }
                // every committed core has a summary
                try (Stream<Path> s = Files.walk(FIXTURES)) {
                    for (Path events : s.filter(p -> p.getFileName().toString().equals("events.jsonl") && p.toString().contains("/package/")).toList()) {
                        String rel = FIXTURES.relativize(events).toString();
                        String fixture = rel.substring(0, rel.indexOf('/'));
                        String core = rel.contains("/children/") ? rel.substring(rel.indexOf("children/"), rel.lastIndexOf('/')) : "";
                        String id = fixture + (core.isEmpty() ? "" : "_" + core.replace('/', '_'));
                        if (!Files.isRegularFile(VALIDATION.resolve(id + ".json"))) {
                            problems.add("no committed validation summary for core " + rel);
                        }
                    }
                }
                if (!problems.isEmpty()) {
                    boolean stale = problems.stream().allMatch(p -> p.contains("stale") || p.contains("no committed") || p.contains("does not exist"));
                    return new Outcome(item, stale ? Status.ERROR : Status.FAIL, String.join("; ", problems));
                }
                return new Outcome(item, Status.PASS, cores + " committed core summaries, each tied to the current event file hash, 0 error events");
            }
            case LIVE -> {
                return new Outcome(item, Status.INFO, "informational: " + item.ref() + " (" + item.scope() + "); not executed in this build");
            }
            case DEFERRED -> {
                return new Outcome(item, Status.ABSTAIN, "DEFERRED (Owner disposition requested): " + item.ref());
            }
            case UNEXERCISED -> {
                return new Outcome(item, Status.ABSTAIN, "UNEXERCISED: " + item.ref());
            }
            case NOTE -> {
                return new Outcome(item, Status.INFO, item.ref());
            }
            case SEPARATE -> {
                return new Outcome(item, Status.ABSTAIN, "MEASURED SEPARATELY (not assessed in this build): " + item.ref());
            }
            default -> throw new IllegalStateException();
        }
    }

    static Status reduce(List<Outcome> outcomes) {
        boolean error = false, fail = false, abstain = false, pass = false;
        for (Outcome o : outcomes) {
            switch (o.status()) {
                case ERROR -> error = true;
                case FAIL -> fail = true;
                case ABSTAIN -> abstain = true;
                case PASS -> pass = true;
                case INFO -> {
                }
            }
        }
        return error ? Status.ERROR : fail ? Status.FAIL : abstain ? Status.ABSTAIN : pass ? Status.PASS : Status.ABSTAIN;
    }

    static String statusCounts(Map<String, Status> m) {
        Map<String, Integer> c = new TreeMap<>();
        m.values().forEach(s -> c.merge(s.name(), 1, Integer::sum));
        return c.toString();
    }

    // ---- inputs -----------------------------------------------------------------------------------------------------

    /** The requirements reporter's persisted criterion statuses from this build (TCK test phase), read with the writer's own JSON parser. */
    static Map<String, Map<String, Object>> oracleStatus() throws IOException {
        Path f = TCK.resolve("target/requirements-evidence/criteria-status.json");
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        Jv doc = Jv.parse(Files.readString(f, StandardCharsets.UTF_8));
        Jv items = doc.member("requirements");
        for (Jv item : items == null ? List.<Jv>of() : items.elements()) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("status", MapperSupport.text(item, "status"));
            Jv reasoning = item.path("judgment", "reasoning");
            st.put("reasoning", reasoning == null ? "" : String.valueOf(reasoning.text()));
            out.put(MapperSupport.text(item, "id"), st);
        }
        return out;
    }

    /** Surefire XML reports of both modules, indexed by simple class name and method name. */
    record Surefire(String className, Map<String, Result> methods) {
        record Result(String className, String outcome, String seconds, String message) {
        }

        static Map<String, Surefire> read(List<Path> dirs) throws IOException {
            Map<String, Surefire> out = new LinkedHashMap<>();
            for (Path dir : dirs) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                try (Stream<Path> s = Files.list(dir)) {
                    for (Path xml : s.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()) {
                        String text = Files.readString(xml, StandardCharsets.UTF_8);
                        int from = 0;
                        while (true) {
                            int at = text.indexOf("<testcase ", from);
                            if (at < 0) {
                                break;
                            }
                            int close = text.indexOf('>', at);
                            String open = text.substring(at, close + 1);
                            String body;
                            int next;
                            if (open.endsWith("/>")) {
                                body = "";
                                next = close + 1;
                            }
                            else {
                                int end = text.indexOf("</testcase>", close);
                                body = end < 0 ? "" : text.substring(close + 1, end);
                                next = end < 0 ? close + 1 : end + "</testcase>".length();
                            }
                            from = next;
                            String name = attribute(open, "name");
                            String cls = attribute(open, "classname");
                            if (name == null || cls == null) {
                                continue;
                            }
                            String method = name.replaceAll("\\(.*$", "").replaceAll("\\[.*$", "");
                            String outcome = body.contains("<failure") ? "failed" : body.contains("<error") ? "error" : body.contains("<skipped") ? "skipped" : "passed";
                            int msgAt = body.indexOf("message=\"");
                            String message = msgAt < 0 ? "" : body.substring(msgAt + 9, Math.max(msgAt + 9, body.indexOf('"', msgAt + 9)));
                            String simple = cls.substring(cls.lastIndexOf('.') + 1);
                            String outer = simple.contains("$") ? simple.substring(0, simple.indexOf('$')) : simple;
                            for (String key : List.of(simple, outer)) {
                                out.computeIfAbsent(key, k -> new Surefire(k, new LinkedHashMap<>())).methods().putIfAbsent(method, new Result(cls, outcome, attribute(open, "time"), message));
                            }
                        }
                    }
                }
            }
            return out;
        }

        static String attribute(String tag, String name) {
            int at = tag.indexOf(name + "=\"");
            if (at < 0) {
                return null;
            }
            int start = at + name.length() + 2;
            return tag.substring(start, tag.indexOf('"', start));
        }

        static Result find(Map<String, Surefire> all, String ref) {
            int hash = ref.indexOf('#');
            String cls = hash < 0 ? ref : ref.substring(0, hash);
            String method = hash < 0 ? null : ref.substring(hash + 1);
            Surefire s = all.get(cls);
            if (s == null) {
                return null;
            }
            if (method == null) {
                boolean allPassed = s.methods().values().stream().allMatch(r -> "passed".equals(r.outcome()));
                return new Result(cls, allPassed ? "passed" : "failed", "", "class-level");
            }
            return s.methods().get(method);
        }
    }

    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String sha256(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ---- output -----------------------------------------------------------------------------------------------------

    static void write(List<EarsRequirement> criteria, List<Rfc2119Requirement> rules, Map<String, List<Outcome>> criterionItems, Map<String, Status> criterionStatus,
            Map<String, List<Outcome>> ruleItems, Map<String, Status> ruleStatus, Map<String, String> ruleDetail, Map<String, List<Outcome>> controls, int executed) throws IOException {
        Path dir = WRITER.resolve("target/requirements-accounting");
        Files.createDirectories(dir);
        StringBuilder md = new StringBuilder();
        md.append("# Requirements accounting (this build)\n\n");
        md.append("Produced by `RequirementsAccountingIT` at the verify phase from this build's executed evidence: the requirements reporter's oracle statuses, ")
                .append(executed).append(" test classes' surefire results across both modules, and the committed upstream-validation summaries tied to the current event files. ")
                .append("NOTE items are scope statements (what was not observed is not claimed); they establish nothing and withhold nothing. ")
                .append("Evidence classes are kept apart: ORACLE (independent oracle on committed fixtures), JUNIT (deterministic Java checks; stand-in processes are labelled), ")
                .append("VALIDATOR (pinned ocsf-toolkit, committed result), LIVE (informational pointer to private steward evidence; establishes nothing here), ")
                .append("DEFERRED and UNEXERCISED (named gaps for Owner disposition). A PASS is scoped to the stated CLI/version/route; no general parity is claimed.\n\n");
        md.append("## Criteria\n\n| Criterion | Status | Evidence (class: reference; scope) |\n|---|---|---|\n");
        for (EarsRequirement c : criteria) {
            md.append("| ").append(c.id()).append(' ').append(escape(c.specification().title())).append(" | **").append(criterionStatus.get(c.id())).append("** | ");
            List<String> cells = new ArrayList<>();
            for (Outcome o : criterionItems.get(c.id())) {
                cells.add(o.item().kind() + " `" + escape(o.item().ref()) + "` " + o.status() + " (" + escape(o.item().scope()) + ")" + (o.status() == Status.PASS || o.status() == Status.INFO ? "" : ": " + escape(o.detail())));
            }
            md.append(String.join("<br>", cells)).append(" |\n");
        }
        md.append("\n## Rules\n\n| Rule | Status | Established by | Detail |\n|---|---|---|---|\n");
        for (Rfc2119Requirement r : rules) {
            md.append("| ").append(r.id()).append(" | **").append(ruleStatus.get(r.id())).append("** | ");
            List<String> cells = new ArrayList<>();
            for (Outcome o : ruleItems.get(r.id())) {
                cells.add((o.item().kind() == Kind.ORACLE && "criterion".equals(o.item().scope()) ? "criterion " : o.item().kind() + " ") + "`" + escape(o.item().ref()) + "` " + o.status());
            }
            md.append(String.join("<br>", cells)).append(" | ").append(escape(ruleDetail.get(r.id()))).append(RULES.get(r.id()).note().isEmpty() ? "" : "; " + escape(RULES.get(r.id()).note())).append(" |\n");
        }
        md.append("\n## Corruption controls (work order §4)\n\n| Control | Tests (each ran and passed in this build) |\n|---|---|\n");
        for (Map.Entry<String, List<Outcome>> e : controls.entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ");
            md.append(e.getValue().stream().map(o -> "`" + o.item().ref() + "` " + o.status()).reduce((a, b) -> a + "<br>" + b).orElse("")).append(" |\n");
        }
        md.append("\n## Totals\n\n- criteria: ").append(statusCounts(criterionStatus)).append(" of ").append(criteria.size()).append("\n- rules: ").append(statusCounts(ruleStatus)).append(" of ").append(rules.size()).append('\n');
        md.append("\nDeferred (Owner disposition requested) or measured separately: ");
        List<String> deferred = new ArrayList<>();
        criterionItems.forEach((id, outs) -> outs.forEach(o -> {
            if (o.item().kind() == Kind.DEFERRED || o.item().kind() == Kind.SEPARATE) {
                deferred.add(id);
            }
        }));
        md.append(deferred).append(". Unexercised scenarios are listed per criterion above.\n");
        Files.writeString(dir.resolve("ACCOUNTING.md"), md.toString(), StandardCharsets.UTF_8);

        StringBuilder json = new StringBuilder("{\n  \"criteria\": {\n");
        int i = 0;
        for (EarsRequirement c : criteria) {
            json.append(i++ > 0 ? ",\n" : "").append("    \"").append(c.id()).append("\": {\"status\": \"").append(criterionStatus.get(c.id())).append("\", \"items\": [");
            int j = 0;
            for (Outcome o : criterionItems.get(c.id())) {
                json.append(j++ > 0 ? ", " : "").append("{\"kind\": \"").append(o.item().kind()).append("\", \"ref\": \"").append(jsonEscape(o.item().ref())).append("\", \"scope\": \"")
                        .append(jsonEscape(o.item().scope())).append("\", \"status\": \"").append(o.status()).append("\", \"detail\": \"").append(jsonEscape(o.detail())).append("\"}");
            }
            json.append("]}");
        }
        json.append("\n  },\n  \"rules\": {\n");
        i = 0;
        for (Rfc2119Requirement r : rules) {
            json.append(i++ > 0 ? ",\n" : "").append("    \"").append(r.id()).append("\": {\"status\": \"").append(ruleStatus.get(r.id())).append("\", \"detail\": \"").append(jsonEscape(ruleDetail.get(r.id()))).append("\"}");
        }
        json.append("\n  }\n}\n");
        Files.writeString(dir.resolve("accounting.json"), json.toString(), StandardCharsets.UTF_8);
    }

    static String escape(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    static String jsonEscape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
