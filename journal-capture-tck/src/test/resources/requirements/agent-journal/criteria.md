# Agent Journal acceptance criteria: observable capture behavior

EARS criteria for the OCSF-based run package. Each criterion's first line after its heading is the
requirement sentence the Agent Judge reader loads. A `Rule:` line links the constraint in `rules.md`;
a `Tests:` line names executable checks that exist in this repository, or says `none yet` when the
behavior is a target that no committed test establishes. A `Status:` line distinguishes behavior
demonstrated on recorded replay or labelled synthetic fixtures from behavior not yet implemented.
`RequirementsEvidenceTest` reports, per criterion, what the independent oracle established on the
committed public fixtures and reports every other criterion as not assessed.

## UC1: Recording and source selection

### UC1-AC1: Normalize the stream of a streaming CLI

When a CLI offers stream output, the capture shall normalize that stream into `events.jsonl` as the selected input.
Rule: RULE-1. Tests: `PrefixReplayTest#everyPrefixOfTheClaudeStreamIsJudgedDefectFreeByTheOracle`, `PrefixReplayTest#everyPrefixOfTheCodexExecStreamIsJudgedDefectFreeByTheOracle` (recorded and synthetic replay). Status: no production recorder uses the new writer yet.

### UC1-AC2: Import a native session file only when it is the only source

When a CLI offers no stream output and writes a native session file, the capture shall import that file through the same writer entry point as its selected input.
Rule: RULE-2. Tests: `PrefixReplayTest#everyPrefixOfTheCodexRolloutIsJudgedDefectFreeByTheOracle` (mechanism only). Status: the source-selection decision itself is not implemented or tested.

### UC1-AC3: Archive the native session file unchanged

When a streaming CLI writes a native session file, the capture shall archive it byte-identical with its checksum and shall declare it outside the selected input.
Rule: RULE-4. Tests: none yet in the new package layout. Status: not implemented in the new writer.

### UC1-AC4: Report a stream gap instead of enriching from the archive

While a stream is the selected input, if a fact such as final response usage is absent from the stream, then the capture shall record that limitation and shall not read the native session file to supply it.
Rule: RULE-5, RULE-6. Tests: none yet. Status: the prototype can admit a later session file experimentally; that path is not the target behavior.

### UC1-AC5: Treat an unsupported stream as a gap

If no stream adapter supports a streaming CLI's version or launch mode, then the capture shall report an unsupported-stream gap and shall not select the native session file instead.
Rule: RULE-3. Tests: none yet. Status: not implemented.

### UC1-AC6: Declare every selected source

When a run is recorded, `run.json` shall declare each selected source with its kind, artifact identity, record count and content hash.
Rule: RULE-21. Tests: `OcsfOracleMutationTest#sourceInventoryHashIsChecked`, `FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource`.

### UC1-AC7: Do not credit the archive to preservation

When preservation is judged, the archived native session file shall not be counted toward the selected-input denominator.
Rule: RULE-4. Tests: `OracleFullSourceTest#anOmittedNativeArtifactIsUnresolvedAndBlocksAcceptance` (frozen manifest defines the denominator). Status: archive-only disposition is not yet expressible in the expected-sources manifest.

## UC2: CLI version and compatibility

### UC2-AC1: Keep the stream-reported version without a version command

When the stream reports the CLI version, the capture shall record that version in the session settings and shall not run a separate version command.
Rule: RULE-7. Tests: `OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect` (recorded value). Status: no test observes that no version subprocess is launched.

### UC2-AC2: Discover an unreported version once

When the selected source reports no CLI version, the capture shall apply the adapter's documented discovery path at most once per run and shall record the discovery basis.
Rule: RULE-8. Tests: none yet. Status: not implemented; Codex's exec stream is the known case.

### UC2-AC3: Retain records that arrive before the version is known

When records arrive before the CLI version is known, the capture shall retain them whole and in order.
Rule: RULE-9. Tests: `WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof` (prefix retention). Status: version timing is not varied by any test.

### UC2-AC4: State the mapping contract and its applicability

When a run is recorded, `run.json` shall name the mapping contract applied to each source and whether the recorded CLI version is within that contract's tested applicability.
Rule: RULE-10. Tests: `PinsTest#mappingContractsDeclareEveryRuleTheMappersExecute`, `VendorSpecificationsTest`. Status: contracts are named; the applicability status is not yet written.

### UC2-AC5: Record an untested version as untested

If the recorded CLI version is outside every tested applicability range, then the package shall record the compatibility status as untested rather than as supported.
Rule: RULE-10. Tests: none yet. Status: not implemented; warning, retention and strict-mode behavior remain proposals.

## UC3: Mapping and preservation

### UC3-AC1: Preserve tool requests and results under their native identity

When the source reports a tool request and its result, the events shall carry the native call identifier, the exact tool name, the structured arguments, and the result under the identifier the result record itself names.
Rule: RULE-13, RULE-14. Tests: `OcsfOracleMutationTest#requestArgumentsAndToolKindAreExact`, `OcsfOracleMutationTest#identicalResultsSwappedBetweenOwnersAreADefect`.

### UC3-AC2: Preserve ordered content blocks

When a response or prompt carries content blocks, the event shall preserve block order, block type, verbatim text and the native block data.
Rule: RULE-13. Tests: `OcsfOracleValueCheckTest#responseActivityStopReasonModelAndBlocksAreEachChecked`, `FullOneReviewRegressionTest#changedRenderedTextIsAContextValueDefectWhileTheRawRecordIsIntact`.

### UC3-AC3: Preserve exact values, nulls and empty containers

When a native value is a number, boolean, null or empty container, the retained value shall keep its type and exact semantic value.
Rule: RULE-13, RULE-17. Tests: `OcsfOracleMutationTest#largeIntegerAndHighPrecisionDecimalAreExact`, `OcsfOracleMutationTest#jo03RetainedJsonNumberTurnedIntoBooleanTrueIsDefective`.

### UC3-AC4: Record usage as reported

When the source reports token usage, the event shall record each component at its reported scope with its provisional or final status, and shall not derive a total or record absence as zero.
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

If a portable field has no reported value in the selected input, then the event shall leave the field absent rather than defaulting or deriving it.
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

## UC4: Lifecycle and children

### UC4-AC1: Read a consistent prefix while recording

While the writer is open, when a flush has been declared, a reader opening the package shall read a consistent prefix in state `RECORDING` without a final hash.
Rule: RULE-19. Tests: `WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof`.

### UC4-AC2: Finalize a complete run

When every declared source has ended at end-of-file with its last record whole, the writer shall finalize the package as `COMPLETED` with final counts and hashes.
Rule: RULE-20. Tests: `WriterLifecycleTest#completeRequiresEveryDeclaredSourceToHaveEndedAndInputsCannotBeMixed`.

### UC4-AC3: Finalize an interrupted run honestly

When capture is interrupted, the writer shall end open sources as interrupted, retain any incomplete tail record, and finalize the acquired prefix as `INTERRUPTED`.
Rule: RULE-20. Tests: `WriterLifecycleTest#interruptionEndsOpenSourcesAndFinalizesWhatWasAcquired`, `WriterLifecycleTest#eofInsideARecordRetainsTheIncompleteTailHonestly`.

### UC4-AC4: Report a write failure as failed

If a persistence write fails, then the manifest shall state `FAILED` with the confirmed persisted boundary and shall not claim completion.
Rule: RULE-20. Tests: `WriterLifecycleTest#injectedWriteFailureLeavesAnHonestFailedPackage`.

### UC4-AC5: Keep storage finalization and acquisition completeness separate

When a package is finalized but a source was interrupted or cut, the package shall declare acquisition incomplete separately from storage finalization, and acceptance shall not treat the package as a complete capture.
Rule: RULE-20. Tests: `WriterAcquisitionRegressionTest#aSourceEndedAsInterruptedThenCompletedIsPreservedButNotAcceptedAsComplete`, `FullOneReviewRegressionTest#anHonestlyInterruptedSourceIsPreservedButNotAcceptedAsComplete`.

### UC4-AC6: Route child records to their own core

When a record names a child owner such as a spawning tool call, the writer shall append it to that child's own core, linked from the parent and independently portable.
Rule: RULE-21, RULE-24. Tests: `WriterLifecycleTest#childRecordsAreRoutedToTheirOwnCoreAsTheyArrive`, `PackageReaderTest#childCoresAreLinkedAndJudgedSeparately`.

### UC4-AC7: Show an open request as open

When a tool request's result is never observed, the package shall show the request open and shall not report it as denied, failed or completed.
Rule: RULE-12. Tests: `OcsfOracleValueCheckTest#resultStructuredDetailAndMissingResultAreEachChecked`, `CodexExecStreamTest#aDroppedStartOrASwappedIdentityIsAPopulationDefect`.

### UC4-AC8: Record a live process incrementally and check it independently

When a live CLI process is recorded, the persisted package shall be readable before the process exits and the final package shall be checked against an independently acquired copy of the selected stream.
Rule: RULE-18, RULE-19. Tests: none yet. Status: not implemented; live integration of the existing writer is the next slice.

## UC5: Portable output and testing

### UC5-AC1: Bootstrap from a copied core

When a core and its linked children are copied alone to a fresh directory without network access, a compatible Java reader shall discover the saved model, enumerate its fields and answer the agreed queries.
Rule: RULE-24, RULE-25. Tests: `IsolatedBootstrapTest#aCopiedCoreWithItsChildBootstrapsWithoutAnythingElse`.

### UC5-AC2: Refuse a detached events file

If `events.jsonl` is presented without its `run.json`, then the reader shall refuse interpretation explicitly rather than guess the schema.
Rule: RULE-25. Tests: `PackageReaderTest#discoversTheManifestFromEventsJsonlAndFromTheDirectory`, `OcsfReaderToolTest` (detached file exit 2).

### UC5-AC3: Validate events against the pinned schema

When a package is written, its events shall validate against the pinned compiled OCSF extension schema and the saved values shall match the declared types, enumerations and requirements.
Rule: RULE-11. Tests: `SchemaCatalogTest#savedValuesAreCheckedAgainstDeclaredTypesEnumsAndRequirements`, `PackageReaderTest#typeMismatchAndMissingRequiredAreDefects`. Status: the upstream validator run is a separately pinned tool step, not a Maven test.

### UC5-AC4: Judge against a frozen source denominator

When acceptance is judged, the expected source set shall come from a manifest frozen before judging, and an omitted, extra, renamed or re-scoped source shall be a defect.
Rule: RULE-29. Tests: `FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource`, `FullOneReviewRegressionTest#aRenamedSourceIsBothAnOmissionAndAnUnexpectedSource`.

### UC5-AC5: Refuse acceptance when a required check is missing

If a required check is absent from an oracle report, then acceptance shall be refused regardless of the remaining outcomes.
Rule: RULE-29. Tests: `FullOneReviewRegressionTest#aRequiredCheckMissingFromTheReportBlocksAcceptance`.

### UC5-AC6: Label the evidence class

When a fixture or report is published, it shall state whether it is live capture, real recorded replay or labelled synthetic.
Rule: RULE-30. Tests: `PublicFixtureRegenerationTest` (labelled synthetic fixtures). Status: no assertion enforces the label on every fixture.

### UC5-AC7: Back each claimed feature with an observed fixture

When a feature is claimed as supported, a committed fixture shall exhibit the feature and an independent assertion shall check it.
Rule: RULE-28. Tests: `CodexExecStreamTest#controlVerifiesEveryFamilyWithStatedPopulationsAndIsAccepted`, `OcsfOracleFixtureTest#sanitizedClaudeStreamWithChildCoreIsJudgedPerCoreWithoutDefect`. Status: interruption, media and redacted reasoning have only labelled-synthetic or no fixtures.

### UC5-AC8: Load the complete requirement roster

When the requirement documents are loaded through Agent Judge, the parsed roster shall equal the intended identifiers, each exactly once, for both documents.
Rule: RULE-32. Tests: `RequirementsRosterTest`, `NativeRequirementTest`.

### UC5-AC9: Report an unassessed requirement as unassessed

When no executable check establishes a requirement, the requirements report shall show that requirement as not assessed and shall not roll the roster up to a pass.
Rule: RULE-32. Tests: `RequirementsEvidenceTest`.

### UC5-AC10: Publish mutation sensitivity separately

When the oracle is mutation-tested, the killed, surviving and uncovered mutation counts and the threshold shall be reported separately from ordinary test results.
Rule: RULE-29. Tests: the `pit` Maven profile in `journal-capture-tck/pom.xml`. Status: a measurement, not a gate on every build.
