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

When a run is recorded, the event file's source declarations shall declare each selected source with its kind, artifact identity, record count and content hash; `run.json` may mirror them as an ancillary index.
Rule: RULE-21. Tests: `OcsfOracleMutationTest#sourceInventoryHashIsChecked`, `FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource`.

### UC1-AC7: Do not credit the archive to preservation

When preservation is judged, the archived native session file shall not be counted toward the selected-input denominator.
Rule: RULE-4. Tests: `OracleFullSourceTest#anOmittedNativeArtifactIsUnresolvedAndBlocksAcceptance` (frozen manifest defines the denominator). Status: archive-only disposition is not yet expressible in the expected-sources manifest.

## UC2: CLI version and compatibility

### UC2-AC1: Keep the stream-reported version without a version command

When the stream reports the CLI version, the capture shall record that version in the session settings and shall not run a separate version command.
Rule: RULE-7. Tests: `LiveCaptureSeamTest#aStreamPipedToTheCaptureSeamIsRecordedWithItsReportedVersionAndJudgedAgainstTheBytes` (a launch without a version; the `cli_version` declaration records the stream-reported one), `OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect`. Status: the capture seam launches no subprocess at all (the driver launches the CLI); demonstrated live on 2026-10-21 with Claude Code 2.1.304 (private evidence).

### UC2-AC2: Discover an unreported version once

When the selected source reports no CLI version, the capture shall apply the adapter's documented discovery path at most once per run and shall record the discovery basis.
Rule: RULE-8. Tests: none yet. Status: not implemented; Codex's exec stream is the known case.

### UC2-AC3: Retain records that arrive before the version is known

When records arrive before the CLI version is known, the capture shall retain them whole and in order.
Rule: RULE-9. Tests: `LiveCaptureSeamTest` (the version is declared after the settings record that reports it; the records before it are already persisted), `WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof`.

### UC2-AC4: State the selected compatibility entry and its applicability

When a run is recorded, the event file's declarations shall name the compatibility entry applied to each source with its source-description revision, mapping contract and mapper identity, and whether the recorded CLI version is within that entry's tested range.
Rule: RULE-10. Tests: `PinsTest#mappingContractsDeclareEveryRuleTheMappersExecute`, `VendorSpecificationsTest`. Status: implemented (`101f8ff`, exact entries since `4e11243`): each source's `compatibility` declaration names the entry, its range and the status (tested, in_range, no_match, version_unknown).

### UC2-AC5: Retain and flag an untested version

If the recorded CLI version, source format or launch route matches no compatibility entry, then the capture shall retain the acquired input, record the compatibility status as untested and surface a warning rather than select the nearest entry or record the version as supported.
Rule: RULE-33. Tests: none yet. Status: not implemented; warning, retention and strict-mode behavior remain proposals.

### UC2-AC6: Reject an ambiguous compatibility selection

If more than one compatibility entry matches a CLI, source profile, launch route and version, then selection shall fail as a configuration error rather than choose one of them.
Rule: RULE-10. Tests: none yet. Status: the registry data shape and its selection test are a bounded next step.

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
Rule: RULE-13. Tests: `FullOneReviewRegressionTest#changedRenderedTextIsAContextValueDefectWhileTheRawRecordIsIntact` (context text), `OcsfOracleMutationTest`. Status: split from UC3-AC2 on 2026-10-21 so prompt evidence is required on its own; no committed public fixture carries a prompt occurrence (the `-p` stream does not echo the prompt), so `RequirementsEvidenceTest` reports it not assessed.

### UC3-AC14: Dispose of every meaningful acquired field

When a source field family is accepted into the maintained source inventory, each meaningful field shall carry one recorded disposition of normalized, retained, omitted under a tested rule, or unresolved with retention.
Rule: RULE-34. Tests: `InventoryDriftTest` (observed inventory). Status: dispositions are not yet recorded per field.

### UC3-AC15: Label Journal-generated time and identity as Journal's

When an event's time or identity was supplied by Journal rather than reported by the source, the event shall label that value with its Journal basis and shall not present it as a vendor-reported value.
Rule: RULE-12. Tests: `OcsfOracleValueCheckTest#fallbackTimeBasisClaimedAsSourceTimeIsADefect`, `OcsfOracleMutationTest#derivedAssociationPresentedAsReportedIsADefect`.

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
Rule: RULE-18, RULE-19. Tests: `LiveCaptureSeamTest` (recorded stream through the `capture` seam: replay evidence about the seam, not live). Status: demonstrated live on 2026-10-21 (Claude Code 2.1.304, normal completion and driver interruption, package read before exit, judged against an independent tee copy; private steward evidence, not a Maven test). The seam is non-default; the production recorder is unchanged.

## UC5: Portable output and testing

### UC5-AC1: Bootstrap from a copied event file

When `events.jsonl` alone is copied to a fresh directory without `run.json`, sibling files, network access or a schema cache, a compatible Java reader shall discover the declared schema, enumerate its fields, types and enumerations, resolve the essential references and read the recorded execution facts and vendor data.
Rule: RULE-24, RULE-25. Tests: `StandaloneEventFileTest#theParentEventFileAloneIsInterpretedFromItsDeclarations`, `StandaloneEventFileTest#theWholeRunAloneIsAcceptedByTheOracleAndTheToolWithoutAnyManifest`, `StandalonePrefixTest` (flushed prefix, interruption, cut, failure, child); `IsolatedBootstrapTest` (two-file core). Status: changed meaning 2026-10-21, from the copied two-file core to the event file alone; implemented in layout 0.3.0-prototype.

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

### UC5-AC11: Validate execution records with the pinned upstream OCSF validator

When a package is written, its execution event records, selected by the documented record-kind rule, shall validate without error under the pinned upstream OCSF validator against the pinned compiled extension schema.
Rule: RULE-11. Tests: `prototype/ocsf-acp/scripts/validate.sh` with the pinned toolkit (a separately executed tool step, not a Maven test). Status: split from UC5-AC3 on 2026-10-21; `RequirementsEvidenceTest` reports it not assessed because no Maven check runs the validator.

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

If a source reports a tool invocation as started, or only its completion, or an MCP tool whose name equals a built-in tool's, then the writer and the oracle profile shall record only the reported stages, the reported name and family apart, and the kind of a reviewed binding or Other, never a model request or a built-in kind.
Rule: RULE-45. Tests: `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `PackageReaderTest#aPackageWrittenWithExtension050StillReadsAndShowsOnlyTheRevisedMeanings`.

### UC5-AC25: Scope a stream-local identifier to its invocation and artifact

When two invocations of one run report the same stream-local item identifier, the writer shall record two items, each with its stream-local identity basis and its own invocation and artifact, and shall write neither as a call or response identity.
Rule: RULE-44. Tests: `CodexStreamMapperTest#equalItemIdsOfTwoInvocationsAreTwoItemsOfTheirOwnNamespaces`.

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

If a cumulative usage value sums contributions of which the source may have received only some, or for which it may have written its default, then the writer and the oracle profile shall state that value at least the quantity it reports (a lower bound) and not measured, however large it is, and shall keep the number.
Rule: RULE-43. Tests: `UsageComponentMeasurementTest#aPositiveRunningTotalWithAnOmittedContributionIsALowerBoundNotAMeasurement`, `UsageComponentMeasurementProfileTest#theProfileReadsAPositiveRunningTotalAsALowerBoundNotAMeasurement`, `UsageUncertaintyControlTest`, `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`.

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
No criterion removed; `RequirementsRosterTest` pins the per-use-case counts 7, 6, 15, 8 and 30.
