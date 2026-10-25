# Agent Journal acceptance criteria: observable capture behavior

EARS criteria for the OCSF-based run package. Each criterion's first line after its heading is the
requirement sentence the Agent Judge reader loads. A `Rule:` line links the constraint in `rules.md`;
a `Tests:` line names executable checks that exist in this repository, or says `none yet` when the
behavior is a target that no committed test establishes. A `Status:` line distinguishes behavior
demonstrated on recorded replay or labelled synthetic fixtures from behavior not yet implemented.
`RequirementsEvidenceTest` reports, per criterion, what the independent oracle established on the
committed public fixtures: a criterion passes only when every property it needs is reported for every
applicable owner of every applicable fixture and the scenario it names was actually exercised; a
configured check missing from the evidence is an error, a wrong observed value a failure, and an
unexercised scenario or insufficient evidence is not assessed. Every other criterion is reported as
not assessed.

## UC1: Recording, source selection and final assembly

### UC1-AC1: Assemble a complete capture automatically

When a capture is configured with a reviewed source profile and the run completes, the capture shall acquire the profile's declared sources and write their final assembly into a new `events.jsonl` without a further request.
Rule: RULE-1, RULE-5. Tests: `CliCaptureTest#aCompleteCaptureAssemblesTheFinalPackageAutomatically` (stand-in process; the original package is kept as lineage), `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (single-source capture). Status: changed meaning 2026-10-24 (was: normalize the stream); implemented for the Claude Code 2.1.304 profile through `CliCapture`; Copilot and Junie assemble from recorded inputs through `FinalAssembly`.

### UC1-AC2: Refuse a source of another execution

If a declared source reports another execution's session identity, or several, then the assembly shall refuse it, write none of its records and state the verdict in its declaration.
Rule: RULE-2. Tests: `FinalAssemblyClaudeTest#anotherExecutionsSessionFileIsRefusedAndWrittenNowhere`, `FinalAssemblyCopilotTest#anotherExecutionsTelemetryIsRefused`, `FinalAssemblyJunieTest#aSessionFileOfAnotherAcquisitionTakesNoIdentityFromASummary`. Status: changed meaning 2026-10-24 (was: import a native session file only when it is the only source).

### UC1-AC3: Bind a source without an identity of its own through its companion or acquisition

When a declared source reports no session identity, the assembly shall bind it through its declared companion declared in the same acquisition, or through its declared acquisition when that is the acquisition of the source that established the run, shall otherwise refuse it, and shall state which, as the caller's assertion.
Rule: RULE-2, RULE-4. Tests: `FinalAssemblyJunieTest#theSessionDirectoryAssemblesWithItsOutputsAndTheOracleVerifiesIt` (the session events through summary.json; standard error and the Copilot usage report by acquisition), `FinalAssemblyClaudeTest#anOptionalSourceWithoutAnIdentityBelongsOnlyThroughTheAnchorsAcquisition`, `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (archive-only material byte-identical). Status: changed meaning 2026-10-24 (was: archive the native session file unchanged; that obligation is RULE-4's and is kept); changed meaning 2026-10-24, R1 candidate (was: bound by a companion role or optionality alone).

### UC1-AC4: Declare a missing required source

If a required source of the profile is missing or refused, then the final file shall declare the assembly incomplete with the missing roles, never complete.
Rule: RULE-2, RULE-6, RULE-52. Tests: `FinalAssemblyClaudeTest#aMissingRequiredSourceMakesTheAssemblyIncomplete`, `FinalAssemblyJunieTest#withoutItsSummaryTheSessionFileHasNoIdentity`, `FinalAssemblyCopilotTest#anAbsentPerSpanCacheWriteStaysAbsent`. Status: changed meaning 2026-10-24 (was: report a stream gap instead of enriching from the archive).

### UC1-AC5: Treat an unsupported version as a gap

If no compatibility entry supports a source's CLI version or route, then the capture shall retain the source and report the gap, and shall not substitute another source outside the reviewed profile.
Rule: RULE-3. Tests: `UntestedCompatibilityTest#aVersionNoEntryCoversIsRetainedFlaggedAndNotNormalized`, `CliCaptureTest#aVersionTestedOnAnotherRouteOnlyIsNoMatchRetainedAndFlagged`. Status: the gap is the no-match compatibility declaration and the reader's warning.

### UC1-AC6: Declare every selected source

When a run is recorded, the event file's source declarations shall declare each selected source with its kind, artifact identity, record count and content hash; `run.json` may mirror them as an ancillary index.
Rule: RULE-21. Tests: `OcsfOracleMutationTest#sourceInventoryHashIsChecked`, `FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource`.

### UC1-AC7: Leave the originals unchanged

When final assembly runs, every original package and native input shall remain byte-identical, and archive-only material shall stay out of the assembled sources and the preservation denominator.
Rule: RULE-4. Tests: `FinalAssemblyClaudeTest#assemblyLeavesItsInputsAndTheOriginalPackageUnchanged`, `FinalAssemblyCopilotTest#theFinalFileReadsAloneAndTheOriginalsAreUnchanged`, `OracleFullSourceTest#anOmittedNativeArtifactIsUnresolvedAndBlocksAcceptance`. Status: changed meaning 2026-10-24 (was: do not credit the archive to preservation, kept as its second clause).

## UC2: CLI version and compatibility

### UC2-AC1: Keep the stream-reported version without a version command

When the stream reports the CLI version, the capture shall record that version in the session settings and shall not run a separate version command.
Rule: RULE-7. Tests: `LiveCaptureSeamTest#aStreamPipedToTheCaptureSeamIsRecordedWithItsReportedVersionAndJudgedAgainstTheBytes` (a launch without a version; the `cli_version` declaration records the stream-reported one), `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (the launching capture runs no version command for Claude), `OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect`. Status: demonstrated live on 2026-10-21 with Claude Code 2.1.304 (private evidence).

### UC2-AC2: Discover an unreported version once

When the selected source reports no CLI version, the capture shall apply the adapter's documented discovery path at most once per run and shall record the discovery basis.
Rule: RULE-8. Tests: `CliCaptureTest#codexVersionIsDiscoveredOnceByTheDocumentedCommandAndTheThreadIdBecomesTheSession` (one `codex --version` per run, its basis declared), `CliCaptureLifecycleTest#aHangingVersionProbeIsBoundedAndReported`, `CliCaptureUnitTest#theVersionProbeParsesBothOutputFormsAndRefusesUnrecognizableOutput`, `UntestedCompatibilityTest#aDiscoveredVersionCarriesItsBasis`. Status: implemented for Codex exec in the opt-in launching capture.

### UC2-AC3: Retain records that arrive before the version is known

When records arrive before the CLI version is known, the capture shall retain them whole, in order and unnormalized, declare the compatibility selection before it normalizes the record that reports a supported version, normalize that record under the selection, and never interpret, reinterpret or credit a record that arrived before the selection.
Rule: RULE-9. Tests: `CompatibilityTest#theVersionBootstrapReadsOnlyTheVersionAndItsLocation`, `UntestedCompatibilityTest#aSupportedInitRecordSelectsFirstAndIsThenNormalizedUnderTheSelection`, `UntestedCompatibilityTest#aRecordBeforeTheInitRecordStaysRetainedOnlyAndIsNotCredited`, `UntestedCompatibilityTest#aVersionNeverLearnedRetainsEveryRecordUnnormalizedAndUnresolved`, `PackageReaderTest#aPackageWrittenWithProvisionalPendingInterpretationStillReadsAndIsJudgedByTheCurrentPolicy`, `LiveCaptureSeamTest` (the records before the reported version are already persisted). Status: implemented; in every committed recording the record that reports the version is the first, so no record precedes a selection there, and the controls construct one.

### UC2-AC4: State the selected compatibility entry and its applicability

When a run is recorded, the event file's declarations shall name the compatibility entry applied to each source with its source-description revision, mapping contract and mapper identity, and whether the recorded CLI version is within that entry's tested range.
Rule: RULE-10. Tests: `CompatibilityTest#theDeclarationStatesEachStatusAndOnlyWhatTheSelectionKnows`, `UntestedCompatibilityTest#aTestedVersionOnItsRouteIsSelectedAndVerified`, `CompatibilityAndIdentityOracleTest#aTestedSelectionIsVerifiedAndEachInternalInconsistencyIsADefect`, `PinsTest#mappingContractsDeclareEveryRuleTheMappersExecute`. Status: implemented (`101f8ff`, exact entries since `4e11243`): each source's `compatibility` declaration names the entry, its range and the status (tested, in_range, no_match, version_unknown).

### UC2-AC5: Retain and flag an untested version

If the recorded CLI version, source format or launch route matches no compatibility entry, then the capture shall retain the acquired input, record the compatibility status as untested and surface a warning rather than select the nearest entry or record the version as supported.
Rule: RULE-33. Tests: `UntestedCompatibilityTest#aVersionNoEntryCoversIsRetainedFlaggedAndNotNormalized`, `UntestedCompatibilityTest#normalizingAfterANoMatchSelectionIsADefect`, `UntestedCompatibilityTest#aRecordBeforeTheVersionIsRetainedUnnormalizedThenNoMatchCreditsNothing`, `CompatibilityTest#outsideEveryRangeOrOffEveryKeyIsNoMatchAndTheNearestEntryIsNotSelected`, `CliCaptureTest#aVersionTestedOnAnotherRouteOnlyIsNoMatchRetainedAndFlagged`. Status: retention, the no-match declaration and the reader's `COMPATIBILITY_NO_MATCH` warning are implemented; a strict mode and any partial untested normalization remain proposals.

### UC2-AC6: Reject an ambiguous compatibility selection

If more than one compatibility entry matches a CLI, source profile, launch route and version, then selection shall fail as a configuration error rather than choose one of them.
Rule: RULE-10. Tests: `CompatibilityTest#overlappingEntriesAreRejectedWhenTheRegistryLoads`, `CompatibilityTest#everyOtherMisconfigurationIsRejectedWhenTheRegistryLoads`. Status: ambiguity is rejected when the registry loads, so the selection-time error is unreachable and untested.

### UC2-AC7: Stop normalizing on contradictory version assertions

If two version assertions within one capture contradict each other, then the capture shall keep both with their origins, declare the selection ambiguous (conflict) without preferring either, retain every later record whole and unnormalized while keeping what it wrote before, and the reader and the oracle shall report the source ambiguous rather than supported; an identical repeated assertion shall change nothing.
Rule: RULE-10. Tests: `CompatibilityTest#contradictoryVersionAssertionsAreAConflictThatSelectsNothing`, `UntestedCompatibilityTest#contradictoryStreamAssertionsStopNormalizationAndKeepThePrefix`, `UntestedCompatibilityTest#aLaunchDeclarationContradictedByTheStreamIsAConflict`, `UntestedCompatibilityTest#aLaunchProbeContradictedByARolloutRecordIsAConflict`, `UntestedCompatibilityTest#anIdenticalRepeatedAssertionIsIdempotent`. Status: implemented on labelled synthetic edits of committed recordings; the Codex exec stream reports no version, so its only assertion is the launch probe's.

## UC3: Mapping and preservation

### UC3-AC1: Preserve tool requests and results under their native identity

When the source reports a tool request and its result, the events shall carry the native call identifier, the exact tool name, the structured arguments, and the result under the identifier the result record itself names.
Rule: RULE-13, RULE-14. Tests: `OcsfOracleMutationTest#requestArgumentsAndToolKindAreExact`, `OcsfOracleMutationTest#identicalResultsSwappedBetweenOwnersAreADefect`.

### UC3-AC2: Preserve ordered response content blocks

When a model response carries content blocks, the response event shall preserve block order, block type, verbatim text and the native block data.
Rule: RULE-13. Tests: `OcsfOracleValueCheckTest#responseActivityStopReasonModelAndBlocksAreEachChecked`. Status: narrowed on 2026-10-21 from "a response or prompt" to responses; prompts are UC3-AC13.

### UC3-AC3: Preserve exact values, nulls and empty containers

When a native value is a number, boolean, null or empty container, the retained value shall keep its type and exact semantic value.
Rule: RULE-13, RULE-17. Tests: `OcsfOracleMutationTest#largeIntegerAndHighPrecisionDecimalAreExact`, `OcsfOracleMutationTest#jo03RetainedJsonNumberTurnedIntoBooleanTrueIsDefective`.

### UC3-AC4: Record usage as reported

When the source reports token usage, the event shall record each component at its reported scope with its provisional or final status, and shall not sum components or snapshots into a total, record absence as zero, or state a derived component except by a declared rule that names its basis and operands.
Rule: RULE-12. Tests: `OcsfOracleMutationTest#provisionalUsageMarkedFinalIsADefect`, `OcsfOracleMutationTest#aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero`, `AnalysisViewsTest#usageSelectsOneApplicableFinalPerGroupAndNeverSums`.

### UC3-AC5: Record cost as a reported total

When the source reports a monetary cost, the event shall record it as a total with currency, scope and source, with components only when the source reports components.
Rule: RULE-12. Tests: `OcsfOracleMutationTest#jo02WrongNormalizedCostAmountWithUnchangedRetainedSourceIsDefective`, `OcsfOracleValueCheckTest#costScopeIsChecked`.

### UC3-AC6: Retain unknown records and blocks whole

When a record or content block has no mapping rule, the capture shall retain it whole with its source position and a declared fallback status.
Rule: RULE-13, RULE-15. Tests: `CodexExecStreamTest#anAgentMessageTextChangedOrAnUnknownRecordDroppedIsDetected`, `UnexercisedBlockKindsTest#labelledSyntheticUnknownBlockTypeIsKindOtherWithExactData`, `PackageReaderTest#unknownClassAndUnknownAttributeAreRetainedAndReported`.

### UC3-AC7: Record permission decisions at their reported scope

When the source reports a permission decision, the event shall record it at the scope the source names with its source and reason, and shall not attach it to a tool call the source does not identify.
Rule: RULE-12, RULE-21. Tests: `OcsfOracleMutationTest#permissionDecisionValuesAndScopeAreExact`, `OcsfOracleValueCheckTest#decisionSourceReasonCandidatesAndMethodAreEachChecked`.

### UC3-AC8: Keep ambiguous attribution ambiguous

When an operation's parent call is not reported by the source, the event shall record the candidate parents and the inference basis rather than a verified link.
Rule: RULE-21. Tests: `OcsfOracleMutationTest#ambiguousOperationAttributedToOneCallIsADefect`, `OcsfOracleMutationTest#derivedAssociationPresentedAsReportedIsADefect`.

### UC3-AC9: Leave unreported fields absent

If a portable field has no reported value in the selected input and no declared, source-grounded rule establishes it exactly from reported values, then the event shall leave the field absent rather than default it, derive it by an undeclared or merely assumed formula, or fill it from another artifact.
Rule: RULE-12. Tests: `OcsfOracleValueCheckTest#responseContentPresentWhereTheSourceHasNoneIsADefect`, `OcsfOracleMutationTest#aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero`.

### UC3-AC10: Preserve the original time and its basis

When a record carries a source timestamp, the event shall preserve the original timestamp text and shall record capture time and the time basis separately.
Rule: RULE-13. Tests: `OcsfOracleValueCheckTest#fallbackTimeBasisClaimedAsSourceTimeIsADefect`, `OcsfOracleMutationTest#changedOriginalTimeOrFramingIsADefect`.

### UC3-AC11: Reference the source occurrence

When an event derives from a native record, it shall reference the source artifact and the one-based record index, and events of one artifact shall not go backwards in record order.
Rule: RULE-21. Tests: `OcsfOracleValueCheckTest#recordIndexGoingBackwardsWithinAnArtifactIsAnOrderDefect`, `OcsfOracleMutationTest#missingDuplicatedAndReorderedRowsAreDefects`.

### UC3-AC12: Compact residuals without losing facts

When compact residual extraction replaces whole-record retention, independent tests shall show that every native value, null, absence, empty container, owner and order remains reachable from the package.
Rule: RULE-15, RULE-16. Tests: none yet. Status: not implemented; whole records are retained beside portable fields.

### UC3-AC13: Preserve ordered prompt content blocks

When a prompt carries content blocks, the prompt event shall preserve block order, block type, verbatim text and the native block data.
Rule: RULE-13. Tests: `FullOneReviewRegressionTest#changedRenderedTextIsAContextValueDefectWhileTheRawRecordIsIntact` (context text), `OcsfOracleMutationTest`. Status: split from UC3-AC2 on 2026-10-21 so prompt evidence is required on its own. The `-p` stream does not echo the main thread's prompt; a child scope's prompt is echoed, and the live Claude recording establishes it (`LiveFixtureFeaturesTest#theSubAgentPromptIsTheChildScopesVendorReportedPrompt`), so `RequirementsEvidenceTest` assesses it on that child prompt only.

### UC3-AC14: Dispose of every meaningful acquired field

When a source field family is accepted into the maintained source inventory, each meaningful field shall carry one recorded disposition of normalized, retained, omitted under a tested rule, or unresolved with retention.
Rule: RULE-34. Tests: `FieldDispositionTest` (one disposition per observed and vendor-declared field, `schema/vendor/<cli>/<profile>/dispositions.json`), `VendorStreamInventoryTest`, `InventoryDriftTest` (observed inventory). Status: implemented for the Claude stream and Codex exec routes.

### UC3-AC15: Label Journal-generated time and identity as Journal's

When an event's time or identity was supplied by Journal rather than reported by the source, the event shall label that value with its Journal basis and shall not present it as a vendor-reported value.
Rule: RULE-12. Tests: `OcsfOracleValueCheckTest#fallbackTimeBasisClaimedAsSourceTimeIsADefect`, `OcsfOracleMutationTest#derivedAssociationPresentedAsReportedIsADefect`.

### UC3-AC16: Embed MCP data in the pinned official definitions

When a source reports MCP protocol data, the writer shall embed it in typed objects generated from the pinned official MCP schema of a declared revision, declare a partial projection with the required members it lacks, and shall not synthesize request ids, protocol revision, isError or success.
Rule: RULE-53. Tests: `McpProtocolContainmentTest`, `McpLiveFixtureTest`, `FinalAssemblyCopilotTest#theCanonicalEventsCarryTheUnionOfTheSources`.

## UC4: Lifecycle and children

### UC4-AC1: Read a consistent prefix while recording

While the writer is open, when a flush has been declared, a reader opening the package shall read a consistent prefix in state `RECORDING` without a final hash.
Rule: RULE-19. Tests: `WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof`.

### UC4-AC2: Finalize a complete run

When every declared source has ended at end-of-file with its last record whole, the writer shall finalize the package as `COMPLETED` with final counts and hashes.
Rule: RULE-20. Tests: `WriterLifecycleTest#completeRequiresEveryDeclaredSourceToHaveEndedAndInputsCannotBeMixed`.

### UC4-AC3: Finalize an interrupted run honestly

When capture is interrupted, the writer shall end open sources as interrupted, retain any incomplete tail record, and finalize the acquired prefix as `INTERRUPTED`.
Rule: RULE-20. Tests: `WriterLifecycleTest#interruptionEndsOpenSourcesAndFinalizesWhatWasAcquired`, `WriterLifecycleTest#eofInsideARecordRetainsTheIncompleteTailHonestly`, `StandalonePrefixTest#anInterruptedFileReadAloneSaysSoAndACutFileKeepsEveryWholeLine`; `RequirementsEvidenceTest` assesses it on the replay-interrupted fixture `claude-child-2.1.299-interrupted` (labelled, not live).

### UC4-AC4: Report a write failure as failed

If a persistence write fails, then the manifest shall state `FAILED` with the confirmed persisted boundary and shall not claim completion.
Rule: RULE-20. Tests: `WriterLifecycleTest#injectedWriteFailureLeavesAnHonestFailedPackage`.

### UC4-AC5: Keep storage finalization and acquisition completeness separate

When a package is finalized but a source was interrupted or cut, the package shall declare acquisition incomplete separately from storage finalization, and acceptance shall not treat the package as a complete capture.
Rule: RULE-20. Tests: `WriterAcquisitionRegressionTest#aSourceEndedAsInterruptedThenCompletedIsPreservedButNotAcceptedAsComplete`, `FullOneReviewRegressionTest#anHonestlyInterruptedSourceIsPreservedButNotAcceptedAsComplete`; `RequirementsEvidenceTest` assesses it on the replay-interrupted fixture (labelled, not live).

### UC4-AC6: Route child records to their own core

When a record names a child owner such as a spawning tool call, the writer shall append it to that child's own event file, which carries its own declarations and its parent and spawn identities, while the parent file identifies the child's location without claiming to contain its content.
Rule: RULE-21, RULE-24. Tests: `WriterLifecycleTest#childRecordsAreRoutedToTheirOwnCoreAsTheyArrive`, `StandaloneEventFileTest#theChildEventFileAloneCarriesItsOwnDeclarationsAndIsJudgedOnItsOwn`, `StandalonePrefixTest#aChildFileWrittenDuringTheRunIsInterpretableAloneBeforeTheRunEnds`. Status: amended and implemented 2026-10-21 (layout 0.3.0-prototype).

### UC4-AC7: Show an open request as open

When a tool request's result is never observed, the package shall show the request open and shall not report it as denied, failed or completed.
Rule: RULE-12. Tests: `OcsfOracleValueCheckTest#resultStructuredDetailAndMissingResultAreEachChecked`, `CodexExecStreamTest#aDroppedStartOrASwappedIdentityIsAPopulationDefect`.

### UC4-AC8: Record a live process incrementally and check it independently

When a live CLI process is recorded, the persisted package shall be readable before the process exits and the final package shall be checked against an independently acquired copy of the selected stream.
Rule: RULE-18, RULE-19. Tests: `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (stand-in process through the launching capture), `LiveCaptureSeamTest` (recorded stream through the `capture` seam: replay evidence about the seam, not live). Status: demonstrated live on 2026-10-21 through the seam (Claude Code 2.1.304) and through the opt-in launching capture (five runs of Claude Code 2.1.304 and Codex CLI 0.160.1, packages read before exit, judged against independent copies; private steward evidence, not Maven tests). Both paths are non-default; the production recorders are unchanged.

## UC5: Portable output and testing

### UC5-AC1: Bootstrap from a copied event file

When `events.jsonl` alone is copied to a fresh directory without `run.json`, sibling files, network access or a schema cache, a compatible Java reader shall discover the declared schema, enumerate its fields, types and enumerations, resolve the essential references and read the recorded execution facts and vendor data.
Rule: RULE-24, RULE-25, RULE-26. Tests: `StandaloneEventFileTest#theParentEventFileAloneIsInterpretedFromItsDeclarations`, `StandaloneEventFileTest#theWholeRunAloneIsAcceptedByTheOracleAndTheToolWithoutAnyManifest`, `StandalonePrefixTest` (flushed prefix, interruption, cut, failure, child); `IsolatedBootstrapTest` (two-file core). Status: changed meaning 2026-10-21, from the copied two-file core to the event file alone; implemented in layout 0.3.0-prototype.

### UC5-AC2: Fail explicitly on a missing or altered declaration

If a mandatory declaration or reference target is missing from or altered in `events.jsonl`, then the reader shall fail interpretation of the affected records explicitly with a diagnostic rather than guess the schema or silently skip them.
Rule: RULE-25. Tests: `StandaloneEventFileTest#removingOrAlteringAMandatoryDeclarationFailsInterpretationExplicitly`, `PackageReaderTest#missingDefinitionsRefuseInterpretationButKeepTheEvents`, `PackageReaderTest#missingDefinitionReferenceIsADefect`. Status: retired meaning 2026-10-21: this criterion required refusing an `events.jsonl` presented without `run.json`; a standalone event file is now the accepted form (a detached file of the historical two-file layout is still refused) and refusal applies to missing or altered declarations inside it.

### UC5-AC3: Check saved values against the embedded definitions

When a package is read, the saved values shall be checked by the Java reader against the embedded definitions' declared types, enumerations and requirements, with every mismatch reported as a diagnostic.
Rule: RULE-11. Tests: `SchemaCatalogTest#savedValuesAreCheckedAgainstDeclaredTypesEnumsAndRequirements`, `PackageReaderTest#typeMismatchAndMissingRequiredAreDefects`. Status: narrowed 2026-10-21 to the Java catalog check; the upstream validator is UC5-AC11.

### UC5-AC4: Judge against a frozen source denominator

When acceptance is judged, the expected source set shall come from a manifest frozen before judging, and an omitted, extra, renamed or re-scoped source shall be a defect.
Rule: RULE-29. Tests: `FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource`, `FullOneReviewRegressionTest#aRenamedSourceIsBothAnOmissionAndAnUnexpectedSource`.

### UC5-AC5: Refuse acceptance when a required check is missing

If a required check is absent from an oracle report, then acceptance shall be refused regardless of the remaining outcomes.
Rule: RULE-29. Tests: `FullOneReviewRegressionTest#aRequiredCheckMissingFromTheReportBlocksAcceptance`.

### UC5-AC6: Label the evidence class

When a fixture, reference or example is published, it shall state one evidence class from the declared vocabularies (a fixture: a live recording, a sanitized real stream or labelled synthetic; a reference: specification, source inspection, synthetic, replay, live, persisted readback or publisher-normalized), and an artifact that states no class, or whose provenance contradicts its class, shall be reported.
Rule: RULE-30, RULE-31. Tests: `FixtureEvidenceClassTest#eachCommittedFixtureHasExactlyItsStatedClass`, `FixtureEvidenceClassTest#aRelabelledOrMixedClassIsReported`, `LiveFixtureFeaturesTest#everyFixtureStatesItsEvidenceClassAndLiveOnesCarryProvenance`, `CoverageDocumentSchemaTest#aPublisherNormalizedCorpusIsItsOwnEvidenceClassAndNeverAnExamplesNativeInput`. Status: enforced for every fixture under `ocsf/fixtures`, every reference and every example; the historical fixtures, the `verify/` resources and the per-CLI module fixtures state their origin in README files and names only.

### UC5-AC7: Back each claimed feature with an observed fixture

When a feature is claimed as supported, a committed fixture shall exhibit the feature and an independent assertion shall check it.
Rule: RULE-28. Tests: `CodexExecStreamTest#controlVerifiesEveryFamilyWithStatedPopulationsAndIsAccepted`, `OcsfOracleFixtureTest#sanitizedClaudeStreamWithChildCoreIsJudgedPerCoreWithoutDefect`, `LiveFixtureFeaturesTest#theCancelledRecordingsAreHonestInterruptedPrefixes`. Status: interruption has live recordings of both CLIs; media and redacted reasoning have no fixtures and are not claimed.

### UC5-AC8: Load the complete requirement roster

When the requirement documents are loaded through Agent Judge, the parsed roster shall equal the intended identifiers, each exactly once, for both documents.
Rule: RULE-32. Tests: `RequirementsRosterTest`, `NativeRequirementTest`.

### UC5-AC9: Report an unassessed requirement as unassessed

When no executable check establishes a requirement, the requirements report shall show that requirement as not assessed and shall not roll the roster up to a pass.
Rule: RULE-32. Tests: `RequirementsEvidenceTest`.

### UC5-AC10: Publish mutation sensitivity separately

When the oracle is mutation-tested, the killed, surviving and uncovered mutation counts and the threshold shall be reported separately from ordinary test results.
Rule: RULE-29. Tests: the `pit` Maven profile in `journal-capture-tck/pom.xml`. Status: a measurement, not a gate on every build.

### UC5-AC11: Validate execution records with the pinned upstream OCSF validator

When a package is written, its execution event records, selected by the documented record-kind rule, shall validate without error under the pinned upstream OCSF validator against the pinned compiled extension schema.
Rule: RULE-11. Tests: `prototype/ocsf-acp/scripts/validate-fixtures.sh` with the pinned toolkit writes one committed summary per fixture core (`ocsf/validation/`); the requirements accounting reads them. Status: split from UC5-AC3 on 2026-10-21. Maven never runs the upstream validator: the accounting establishes this criterion only from a committed summary whose recorded event-file hash equals the current file, whose toolkit and compiled schema equal the pinned ones, and which reports no error, for every committed core; regenerating fixtures without rerunning the validator therefore fails the build. `RequirementsEvidenceTest` (the oracle reporter) does not assess it.

### UC5-AC12: Report a partially evidenced criterion as not passed

When a criterion's required property, applicable owner or named scenario is absent from the evidence, the requirements report shall report that criterion as error or not assessed and shall not report it as passed.
Rule: RULE-36. Tests: `RequirementsEvidenceTest`, `RequirementsReporterReproductionTest`.

### UC5-AC13: Derive the comparison matrix from the mapping catalog

When common-model coverage is compared across CLIs, the matrix shall be generated from reviewed mapping-catalog entries with verified, retained-only, unavailable-at-source, unexercised and unresolved states kept separate.
Rule: RULE-35. Tests: `MappingCatalogTest#theCommonConceptMatrixIsDerivedFromTheEntriesAndThisRunsOracleResultsWithStatesKeptSeparate`, `MappingCatalogTest#everyCatalogReferenceResolvesInJava`. Status: implemented 2026-10-21 (ACCEPT-1); "mapping catalog" replaces the earlier term "crosswalk". Repaired 2026-10-22 (FND-1): the coverage summary (`CatalogCoverage`, reproduced by `MappingCatalogSummaryTest`) owes every listed property for every owner the frozen expected sources establish, at its declared scope, with an occurrence of the concept's native scenario; a missing owed result blocks verification; the table is written to `target/mapping-catalog/CLI-COVERAGE.md`, one column per admitted CLI/version/profile/route.

### UC5-AC14: Refuse a concept verification when an applicable result is missing or not applicable

If a required applicable result of a mapping-catalog concept is missing from the oracle report or is reported NOT_APPLICABLE while the owner's native records hold occurrences of its trigger, then the coverage summary shall not report the concept verified and shall name the property and owner.
Rule: RULE-36, RULE-40. Tests: `MappingCatalogSummaryTest#aRequiredApplicableResultReportedNotApplicableCannotVerifyBesideAVerifiedPopulation`, `MappingCatalogSummaryTest#aMissingRequiredPropertyBlocksVerificationAndIsNamed`, `MappingCatalogSummaryTest#aResultOwnerNotApplicableDerivedFromTheCandidateIsRejectedAgainstTheNativeResults`.

### UC5-AC15: Report a genuinely absent trigger as unexercised with its basis

When the owner's native records hold zero occurrences of a property's declared trigger, the coverage summary shall report the obligation unexercised with that zero-occurrence basis, distinct from a check missing from the report.
Rule: RULE-40. Tests: `MappingCatalogSummaryTest#anUnexercisedConceptStatesTheZeroOccurrenceBasisOfEachNotApplicableObligation`, `MappingCatalogSummaryTest#zeroApplicableOccurrencesAreUnexercisedButADroppedCheckIsNot`, `MappingCatalogSummaryTest#aGenuinelyAbsentTriggerForOneOwnerDoesNotBlockTheOwnerThatHasIt`.

### UC5-AC16: Refuse compensation by another owner or occurrence

If one expected owner or native occurrence of an obligation lacks its established result, then the coverage summary shall not report the concept verified, whatever another owner's or a sibling property's result says.
Rule: RULE-36. Tests: `MappingCatalogSummaryTest#anotherOwnersVerifiedResultCannotSatisfyAnExpectedChildsNotApplicable`, `MappingCatalogSummaryTest#aValueResultCoveringFewerOccurrencesThanTheNativePopulationCannotVerify`, `MappingCatalogSummaryTest#aResultForAnUnrelatedOwnerCannotEstablishTheTargetOwnersObligation`, `MappingCatalogSummaryTest#aRepresentationDifferenceOnAValuePropertyIsNotAnAllowedNonSemanticDifference`.

### UC5-AC17: Refuse a stale approval of a changed meaning

If a semantic claim's normative content changes after the Owner approved an earlier revision, then validation shall refuse the old approval for the changed claim.
Rule: RULE-41, RULE-38. Tests: `SemanticClaimTest#aChangedMeaningUnderAnOldApprovalIsRefused`, `SemanticClaimTest#anApprovalNeedsARecordedDecisionThatListsTheExactRevisionAndDigest`.

### UC5-AC18: Keep an approval when only supporting evidence grows

When an unchanged semantic claim gains supporting evidence or checks, the claim's digest and its existing approval shall remain valid under the documented digest boundary.
Rule: RULE-41. Tests: `SemanticClaimTest#addedEvidenceAndChecksLeaveTheDigestAndTheApprovalValid`.

### UC5-AC19: Expose a removed or altered evidence link

If a claim's pinned reference, normative example, destination, check or route is removed or altered, or an example's native input changes, then validation shall report the missing or inconsistent link.
Rule: RULE-39. Tests: `SemanticClaimTest#aRemovedOrAlteredLinkIsExposed`, `SemanticClaimTest#theCommittedClaimsExamplesReferencesAndDecisionsAreConsistent`.

### UC5-AC20: Reject a wrong meaning despite equal numbers

If cumulative usage is labelled with a turn or interval scope, then an independently specified expected case shall reject that meaning although every numeric component is equal.
Rule: RULE-39. Tests: `SemanticExampleTest#aPerTurnLabelOnTheCumulativeUsageIsRejectedThoughEveryComponentIsEqual`, `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`.

### UC5-AC21: Keep a candidate meaning out of approved support

When a proposed interpretation passes its behaviour tests without the Owner's approval, the evidence shall retain the technical results and shall report the claim as a candidate, not as approved support.
Rule: RULE-38. Tests: `SemanticClaimTest#aProposedClaimWithPassingChecksIsACandidateNotApprovedSupport`; the requirements accounting reports each claim's approval and executed checks apart.

### UC5-AC22: Interpret a repeated member only at its declared exact location

If a native record repeats a member anywhere other than the declared location, or a member's name only spells the declared path with dots, slashes or tildes, then the writer and the oracle profile shall each retain the record as an opaque record, state no interpreted fact from it and report the repeat at its exact location.
Rule: RULE-42. Tests: `DuplicateMemberAddressingTest#onlyTheExactDeclaredLocationIsInterpretedAndEveryRepeatHasItsExactLocation`, `DuplicateMemberAddressingTest#aRootMemberLiterallyNamedItemIdIsNotTheDeclaredForm`, `DuplicateMemberAddressingTest#theWholeOracleAcceptsTheDottedLiteralLineOnlyAsAnOpaqueRecord`, `CodexDuplicateMemberProfileTest#onlyTheExactDeclaredLocationStatesARequestAndEveryRepeatHasItsExactLocation`, `CodexDuplicateMemberProfileTest#rootMembersLiterallyNamedItemIdAreNotTheDeclaredForm`.

### UC5-AC23: State equal usage facts as equal tranches and an unproven tranche as unavailable

When two sources report the same usage facts in different representations, the writer and the oracle profile shall state equal canonical tranches with each component's basis, and if a source's reported components do not establish a tranche exactly, then they shall state it unavailable and write no canonical value for it.
Rule: RULE-43. Tests: `SemanticExampleTest#equivalentClaudeAndCodexUsageStateEqualTranchesAndTheUnestablishedOneOnlyConditionally`, `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `CodexExecStreamTest#anOffByOneUsageValueOrAMissingComponentIsAUsageValueDefect`.

### UC5-AC24: Keep the reported stage, name, family and namespace of a tool activity

If a source reports a tool invocation as started, or only its completion, or an MCP tool whose name equals a built-in tool's, then the writer and the oracle profile shall record only the reported stages, the reported name and family apart, and the kind of a reviewed binding of the tool's exact namespace and name, or otherwise Other, never a model request or a built-in kind inferred from the bare name.
Rule: RULE-45. Tests: `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `PackageReaderTest#aPackageWrittenWithExtension050StillReadsAndShowsOnlyTheRevisedMeanings`, `ToolClassificationTest#onlyAReviewedExactNamespaceAndNameBindingGivesAnMcpToolAKind`, `ToolClassificationTest#claudesExactNameTableDoesNotGiveAnMcpToolTheKindOfABuiltInWithTheSameShortName`, `IdentityClassificationControlTest#aBuiltInKindGivenToAnMcpToolWithoutAReviewedBindingIsADefect`. Status: no reviewed binding exists in the bundled contracts; the reviewed-binding case is exercised on the writer with a test contract only, and the independent profile has no binding reading yet, so it refuses any kind other than Other for MCP until a reviewed binding is added to both.

### UC5-AC25: Scope a stream-local identifier to its invocation and artifact

When two invocations of one run report the same stream-local item identifier, the writer shall record two items, each with its stream-local identity basis and its own invocation and artifact, and shall present neither as a model-facing call identifier or a provider's response identifier.
Rule: RULE-44. Tests: `CodexStreamMapperTest#equalItemIdsOfTwoInvocationsAreTwoItemsOfTheirOwnNamespaces`, `CodexStreamMapperTest#equalToolItemIdsOfTwoInvocationsAreTwoStreamLocalCallsNeverModelFacingOrResponses`, `IdentityClassificationControlTest#aForgedResponseIdentityOnAStreamLocalToolItemIsADefect`, `IdentityClassificationControlTest#aModelFacingIdentityBasisOnAStreamLocalToolItemIsADefect`.

### UC5-AC26: Record a reasoning summary and an answer as two observations of unidentified responses

When a source reports a reasoning summary and an answer as separate content items without the response they belong to, the writer and the oracle profile shall record two content observations in source order, the summary as a summary, and no response identity on either.
Rule: RULE-46, RULE-44. Tests: `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `LiveFixtureFeaturesTest#theCodexRecordingExhibitsOperationsFailureRepetitionAndUsage`.

### UC5-AC27: Carry an approved meaning across a pin-only binding change, never to an unreviewed route

When an approved claim's binding changes only its version pins or example file bytes and the definitions and rules its meaning depends on are unchanged, validation shall keep the approval and report the binding as carried; if the binding adds a route, then the approval shall cover only the previously approved routes and the added route shall await binding review.
Rule: RULE-41. Tests: `SemanticClaimTest#anUnrelatedVersionBumpCarriesAnApprovedMeaning`, `SemanticClaimTest#anExpandedRouteAwaitsBindingReviewWhileTheApprovedRouteStaysApproved`.

### UC5-AC28: Refuse an approved binding whose relevant definition or rule changed

If the extension definition of an approved claim's destination or a contract rule its binding names changes, then validation shall refuse the approval for the changed binding, and if checks of an approved, unchanged meaning fail, then the claim shall be reported approved but not verified.
Rule: RULE-41, RULE-38. Tests: `SemanticClaimTest#aChangedDefinitionOrRuleRefusesTheApprovedBinding`, `SemanticClaimTest#anUnchangedMeaningWithAFailedImplementationIsApprovedButNotVerified`.

### UC5-AC29: State a possibly defaulted usage component and keep a formula that depends on it conditional

If a source can emit a usage component's value as a default without a measurement, then the writer and the oracle profile shall state a value that may be that default possibly default, keep every reported value, and state the result of a formula that depends on it only as that component's conditional value naming the assumption, with no canonical value for the tranche.
Rule: RULE-43, RULE-12. Tests: `UsageComponentMeasurementTest#aPossiblyDefaultedZeroInsideNonZeroUsageIsMarkedAndItsDerivationConditional`, `UsageComponentMeasurementTest#aZeroRequiredMemberIsALowerBoundNotADefaultBesideDefaultedZeros`, `UsageComponentMeasurementProfileTest#theProfileMarksAPossiblyDefaultedZeroAndItsConditionalDerivation`, `UsageUncertaintyControlTest`, `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`.

### UC5-AC30: Do not establish complete contributions from a positive cumulative value

If a cumulative usage value sums contributions of which the source may have received only some, or for which it may have written its default, then the writer and the oracle profile shall state that value a lower bound, the actual quantity being at least the reported value and possibly more (a reported 100 is compatible with an actual 150), never measured and never raised to an assumed total, however large it is, and shall keep the number.
Rule: RULE-43. Tests: `UsageComponentMeasurementTest#aPositiveRunningTotalWithAnOmittedContributionIsALowerBoundNotAMeasurement`, `UsageComponentMeasurementProfileTest#theProfileReadsAPositiveRunningTotalAsALowerBoundNotAMeasurement`, `UsageUncertaintyControlTest`, `UsageUncertaintyControlTest#aLowerBoundRaisedToAnAssumedCompleteTotalIsADefect`, `UsageUncertaintyControlTest#equalPricesRemoveTheAllocationUncertaintyButNotTheMissingContributions`, the reviewed example `codex-usage-reported-100-actual-150-synthetic` through `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`.


### UC5-AC31: Record one invocation for a shell command whatever its text runs

When a source reports one command or shell tool invocation whose text runs further commands, pipelines or interpreters, the writer and the oracle shall record exactly the invocation and the operations the source reports, with the command text verbatim, a start and a completion the source reports for it as two observations of that one invocation, and no additional tool call, operation, argument vector or operating-system action derived from that text.
Rule: RULE-45. Tests: `ToolClassificationTest#aShellCommandWithGrepAPipeAndPythonIsOneReportedInvocation`, `IdentityClassificationControlTest#anInvocationOrOperationInventedFromAShellCommandsTextIsAPopulationDefect`, `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`. Status: the Codex pipeline example is a normative example of `codex.exec-stream.standalone-operation-command` (r4) and the Claude Bash example of `claude.stream.shell-command-invocation` (r1), each scoped to its own route; both claims are proposed.

### UC5-AC32: Account for every pinned Tier 1 field once and keep proposed exclusions visible

When the parity ledger is checked against the pinned foundation inventory, every pinned field shall be assigned to exactly one facet, the denominator and the roster of proposed exclusions shall equal their pins, and a field left unassigned, assigned twice, unknown to the inventory or dropped from it, or an exclusion hidden, re-tiered or reported as accepted without a recorded decision, shall fail the check while executed verification stays apart from the ledger's reviewed states.
Rule: RULE-47. Tests: `ParityLedgerTest#everyPinnedFieldIsAccountedForExactlyOnce`, `ParityLedgerTest#eachCorruptionIsDetected`, `ParityLedgerTest#theLedgerAndTheTopicExplanationAreGenerated`. Status: accounting and consistency only; the ledger's facet states and the eleven proposed exclusions are reviewed proposals, not approved; whether a facet's parts agree with executed evidence is UC5-AC33.

### UC5-AC33: Rest each part of a facet on its own executed evidence per route

When a parity facet's support differs by role or aspect, the ledger shall state each part with one of the facet's concepts and a state per route that agrees with that concept's executed state on that route, and a part claimed mapped where its concept is unavailable at source or unexercised, retained bytes claimed as verified meaning, a part citing another part's concept or a concept outside the facet, a removed part, or a facet reported covered while a part is unexercised shall fail the check.
Rule: RULE-47. Tests: `ParityLedgerTest#everyFacetAspectAgreesWithItsConceptsExecutedStatePerRoute`, `ParityLedgerTest#theLedgerAndTheTopicExplanationAreGenerated`. Status: two facets state parts: `operation.unknown` (the portable marking and exact retention of an operation of unknown kind; its semantics excluded) and `content.roles` (user, assistant, tool and injected-context roles per route). The parts are reviewed proposals; their agreement with this build's executed states is checked, which approves no meaning.

## UC6: Final assembly

### UC6-AC1: Consolidate one entity observed by several sources

When several declared sources observe one logical entity at one lifecycle stage, the final file shall carry one consolidated canonical event naming its supporting observations, each kept as evidence and never counted again.
Rule: RULE-5, RULE-48. Tests: `FinalAssemblyClaudeTest#thePairAssemblesIntoOneCanonicalExecutionTheOracleVerifies`, `FinalAssemblyCopilotTest#theFourSourcesAssembleIntoOneExecutionTheOracleVerifies`.

### UC6-AC2: Join only on declared identities

If an identity repeats within one source at the same lifecycle stage, then the assembly shall pair none of its observations, keeping each canonical and flagged ambiguous; the same identity at different stages of a declared staged join is one entity's lifecycle; observations are never paired by position, time or similar text.
Rule: RULE-48. Tests: `FinalAssemblyClaudeTest#anIdentityRepeatedWithinOneSourceStaysUnpairedAndFlagged`, `AssemblyLifecycleIdentityTest`. Status: changed meaning 2026-10-24, R1 candidate (was: any repeat within one source).

### UC6-AC3: Keep conflicts visible

When observations of one entity disagree on a field, the consolidated event shall list every value with its occurrence and shall carry a value only by a field authority the profile declares with its reason.
Rule: RULE-49. Tests: `FinalAssemblyClaudeTest#thePairAssemblesIntoOneCanonicalExecutionTheOracleVerifies` (the stream's MCP structured result by declared authority), `FinalAssemblyCopilotTest#theCanonicalEventsCarryTheUnionOfTheSources`.

### UC6-AC4: Import idempotently and keep distinct origins

When the same origin and bytes are imported again, the assembly shall add nothing; when identical bytes arrive from a distinct origin declared a copy of an earlier source, it shall keep both sources and make their observations one entity; without that declared lineage it shall refuse the second with its reason, neither consolidating it by position nor counting it again.
Rule: RULE-51. Tests: `FinalAssemblyClaudeTest#replayingTheSameImportAddsNothingWhileIdenticalBytesFromAnotherOriginStayDistinct`. Status: changed meaning 2026-10-24, R1 candidate (was: identical bytes from distinct origins are one entity).

### UC6-AC5: Keep quantity kinds apart

When sources report a model cost multiplier sum and premium requests for one run, the final file shall carry them as distinct quantity kinds and units, never compared, added or read as money; a cost whose unit the source does not name shall not be stated as money.
Rule: RULE-50. Tests: `FinalAssemblyCopilotTest#aMultiplierSumAndPremiumRequestsAreDistinctQuantities`, `FinalAssemblyJunieTest#theSessionDirectoryAssemblesWithItsOutputsAndTheOracleVerifiesIt`.

### UC6-AC6: Read the final file alone

When the final `events.jsonl` is copied alone, a compatible reader shall read the consolidated execution and its assembly declaration without the native files, `run.json` or schema caches; a file of several sources without an assembly declaration it shall read as an unvalidated evidence log (declared, or written before the combination policy), which the oracle reports unresolved and never accepts as one combined execution.
Rule: RULE-52. Tests: `FinalAssemblyClaudeTest#theFinalFileReadsAloneWithoutNativeFilesManifestOrSchemaCaches`, `FinalAssemblyCopilotTest#theFinalFileReadsAloneAndTheOriginalsAreUnchanged`, `FinalAssemblyJunieTest#theFinalFileReadsAlone`, `CombinedSourceAdmissionTest`. Status: extended 2026-10-24, R1 candidate (the combination policy).

### UC6-AC7: Judge the assembly independently

When a final file is judged, the assembly oracle shall derive the gate verdicts, entities, references, consolidated values and accounting from the native records and a written expectation alone, and shall report an altered consolidation as defective.
Rule: RULE-48, RULE-49, RULE-50. Tests: `FinalAssemblyClaudeTest#theOracleRejectsAlteredConsolidation`, `FinalAssemblyCopilotTest#theFourSourcesAssembleIntoOneExecutionTheOracleVerifies`.

## UC7: Source onboarding gates

### UC7-AC1: Reject an absence the inventory contradicts

If a source specification claims a fact family absent while its inventory observes paths of that family, then the availability gate shall report the claim as defective.
Rule: RULE-54. Tests: `SourceAvailabilityTest#aWhollyAbsentUsageClaimIsRejected`.

### UC7-AC2: Treat a zero-match selector as unexercised

If a selector matches no observed record, then the availability gate shall report it unexercised and shall reject it when it is marked exercised.
Rule: RULE-54. Tests: `SourceAvailabilityTest#aZeroMatchSelectorMarkedExercisedIsRejected`.

### UC7-AC3: Keep nested vendor usage inventoried

If vendor usage lies under a subtree a specification inventories as tool data, or an observed usage path is missing from the specification, then the availability gate shall report it.
Rule: RULE-54. Tests: `SourceAvailabilityTest#nestedUsageMayNotDisappear`, `SourceAvailabilityTest#theAuditOfTheEarlierRoutesFindsExactlyTheRecordedGaps`.

## Change log

2026-10-21, consolidated event-file work order (REQ-1 amendment). Retained identifiers with changed
meaning: UC2-AC4 (compatibility entry instead of contract name in `run.json`), UC2-AC5 (retain and
flag, rule RULE-33), UC3-AC2 (responses only; prompts moved to UC3-AC13), UC4-AC6 (child file
carries its own declarations), UC5-AC1 (event file alone instead of the two-file core), UC5-AC2
(detached-file refusal retired; explicit failure on a missing or altered declaration), UC5-AC3
(Java catalog check only; upstream validator moved to UC5-AC11). Added UC2-AC6, UC3-AC13 to
UC3-AC15 and UC5-AC11 to UC5-AC13. No criterion was removed; `RequirementsRosterTest` pins the
per-use-case counts 7, 6, 15, 8 and 13.

2026-10-21, two-CLI streaming acceptance (ACCEPT-1): UC5-AC13 renamed in wording only ("mapping catalog" for "crosswalk") and marked implemented; no criterion added or removed.

2026-10-22, foundation comparison (FND-1): UC1-AC6 now names the event file's source declarations as the declaring
records (`run.json` mirrors them), matching the standalone layout in force since `f4e68f8`; UC2-AC4's status line
updated to the implemented compatibility declaration; UC5-AC13's status records the repaired coverage summary. No
criterion added or removed.

2026-10-22, semantic evidence consolidation (SEM-1): added UC5-AC14 to UC5-AC21 (a missing or unjustified
NOT_APPLICABLE result cannot verify a concept; a genuinely absent trigger is unexercised with its basis; no
compensation by another owner or occurrence; stale approvals refused; added evidence keeps an approval; a removed or
altered evidence link is exposed; equal numbers do not establish a meaning; a candidate is not approved support). No
criterion removed; `RequirementsRosterTest` pins the per-use-case counts 7, 6, 15, 8 and 21.

2026-10-22, delegated semantic closure (SEM-CLOSE): added UC5-AC22 (a repeated member is interpreted only at its
declared exact location; a dotted, slash or tilde look-alike stays opaque in the writer and the oracle profile). No
criterion removed; `RequirementsRosterTest` pins the per-use-case counts 7, 6, 15, 8 and 22. Then added UC5-AC23 to
UC5-AC26 (equal usage facts as equal tranches, an unproven tranche unavailable; the reported stage, name, family and
namespace of a tool activity; a stream-local id scoped to its invocation and artifact; a reasoning summary and an
answer as two observations of unidentified responses); the counts are 7, 6, 15, 8 and 26.

2026-10-22, Tier 1 foundation wave: added UC5-AC27 (a pin-only binding change carries an approved meaning; an added
route awaits binding review) and UC5-AC28 (a changed definition or rule refuses the approved binding; failed checks
leave an approval unverified) and UC5-AC29 (a possibly defaulted usage component is stated possibly default and a
tranche derived from it conditional, the number kept). No criterion removed; `RequirementsRosterTest` pins the
per-use-case counts 7, 6, 15, 8 and 29.

2026-10-23, requirements consistency reconciliation. Retained identifiers with clarified wording: UC3-AC4 (no total from
components or snapshots, no absence as zero, a derived component only by a declared rule naming its basis and operands;
it no longer forbids every derivation) and UC3-AC9 (absent unless reported or established exactly by a declared rule;
an assumed formula does not establish a value). UC5-AC23 is retained, now explicit that an inexactly established
tranche has no canonical value. UC5-AC29 is retained with changed meaning: the result of a formula depending on a
possibly default value is only that component's conditional value, never the canonical tranche, and a zero required
member is no longer stated measured. Added UC5-AC30 (a positive cumulative value is a lower bound, not a measurement).
UC5-AC24 is narrowed to its rule: the kind of a reviewed binding of the exact namespace and name, otherwise Other, never
a built-in kind inferred from the bare name (it no longer reads as forbidding a reviewed binding's kind). UC5-AC25 is
narrowed to its rule: a stream-local identifier is never presented as a model-facing call identifier or a provider's
response identifier (it no longer forbids the stream-local call identity Journal states with its basis). Added UC5-AC31
(one invocation for a shell command whatever its text runs). UC5-AC6 is retained with changed meaning: one evidence
class from the declared vocabularies for fixtures, references and examples, a missing or contradicted class reported
(it named three labels for fixtures and reports only). Added UC5-AC32 (every pinned Tier 1 field accounted once, the
denominator and the proposed-exclusion roster pinned). UC5-AC1 also names RULE-26, which the accounting already
linked. Annotations only, no criterion's obligation changed: the Tests and Status lines of UC1-AC1, UC1-AC3, UC1-AC4,
UC1-AC5, UC1-AC7, UC2-AC1 to UC2-AC6, UC3-AC13, UC3-AC14, UC4-AC8, UC5-AC7 and UC5-AC11 now match the implemented
behaviour and the executed evidence (UC5-AC11: Maven reads pinned validator summaries tied to the current event-file
hashes, toolkit and compiled schema; it does not run the validator). No criterion removed; `RequirementsRosterTest`
pins the per-use-case counts 7, 6, 15, 8 and 32.

2026-10-23, review closure. Retained identifiers with changed meaning: UC2-AC3 now requires selection before
normalization (the record reporting a supported version is normalized after the selection is declared; an earlier
record stays whole, unnormalized and uncredited) in place of the provisional interpretation its Status line
described. UC5-AC30 now states the lower bound in its direction: the actual quantity is at least the reported value
(a reported 100 is compatible with an actual 150), never raised to an assumed total. UC5-AC31 is clarified: a start
and a completion the source reports stay two observations of the one invocation. Added UC2-AC7 (contradictory
version assertions stop normalization, keep both assertions and report the source ambiguous; an identical assertion is
idempotent) and UC5-AC33 (each part of a facet rests on its own concept's executed state per route). Annotations only:
the Tests or Status lines of UC2-AC5 and UC5-AC32. No criterion removed; `RequirementsRosterTest` pins the per-use-case
counts 7, 7, 15, 8 and 33.

2026-10-24, final Level 1 assembly, first-class MCP and onboarding repair (Owner-authorized). Retained identifiers with
changed meaning: UC1-AC1 (assemble a complete capture automatically; was: normalize the stream), UC1-AC2 (refuse a
source of another execution; was: import a session file only when it is the only source), UC1-AC3 (bind a source
without its own identity through its companion or acquisition; the archive obligation stays RULE-4's), UC1-AC4
(declare a missing required source; was: report a stream gap instead of enriching), UC1-AC5 (an unsupported version is
a gap, no substitution outside the profile), UC1-AC7 (originals unchanged; archive-only material outside the assembled
sources and the denominator). Added UC3-AC16 (MCP in the pinned official definitions), UC6-AC1 to UC6-AC7 (final
assembly) and UC7-AC1 to UC7-AC3 (source availability gates). No criterion removed; `RequirementsRosterTest` pins the
per-use-case counts 7, 7, 16, 8, 33, 7 and 3.
