# Agent Journal rules: source-aware capture, final assembly, preservation and the Java writer

Normative constraints on how Agent Journal records an agentic CLI execution into its OCSF-based run
package. Each rule states one obligation with an RFC 2119 keyword and a reason. Observable behavior
is specified separately in `criteria.md`; the two documents link by identifier and are not copies of
each other.

These rules are inert test resources. `RequirementsRosterTest` checks that every rule heading below
is parsed exactly once by the Agent Judge reader; a green parse establishes that the roster loaded,
not that the implementation meets any rule. A `Tests:` line names executable checks that exist in
this repository; it is a link, not proof that the check ran or passed.

Terminology: **native session file** is the file a CLI writes on its own account (Claude Code's
session transcript, Codex's rollout file, Copilot's session events, Junie's session directory). **Stream** is
the structured output a CLI offers while it runs (`stream-json`, `exec --json`). **Source profile** is the
reviewed list of a CLI version and route's declared sources (standard output, native session files and
their summaries, native OTel exports, usage reports, standard error), each with its role, requirement,
framing and identity binding. **Selected input** is a declared source normalization reads. **Final
assembly** writes one new `events.jsonl` from the declared sources of one execution.

## Source selection, final assembly and the archive boundary

### RULE-1
**MUST** select a CLI's inputs from the reviewed source profile of its exact version and route, declaring each source's role, requirement, framing and acquisition evidence; there is no universal preference for standard output.
**Reason:** CLIs put different facts in different sources (Copilot reports per-call usage in its session file and OTel export, Junie its structured steps in the session directory); a fixed preference loses facts the reviewed profile shows a source reports.
Tests: `FinalAssemblyClaudeTest#thePairAssemblesIntoOneCanonicalExecutionTheOracleVerifies`, `FinalAssemblyCopilotTest#theFourSourcesAssembleIntoOneExecutionTheOracleVerifies`, `FinalAssemblyJunieTest#theSessionDirectoryAssemblesWithItsOutputsAndTheOracleVerifiesIt`; single-source capture keeps `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy`.

### RULE-2
**MUST** bind each declared source to the execution by a session identity the source itself reports, by the identity of a declared companion in the same native session directory, or by the acquisition that captured it, and **MUST** refuse a source that reports another execution, several, or (when required) none.
**Reason:** A source of another execution silently corrupts the record; identity reported by the source, not assumed from its location, is what lets an independent check confirm membership.
Tests: `FinalAssemblyClaudeTest#anotherExecutionsSessionFileIsRefusedAndWrittenNowhere`, `FinalAssemblyCopilotTest#anotherExecutionsTelemetryIsRefused`, `FinalAssemblyJunieTest#aSummaryOfAnotherSessionTakesItsEventsWithIt`, `FinalAssemblyJunieTest#withoutItsSummaryTheSessionFileHasNoIdentity`.

### RULE-3
**MUST NOT** treat a missing adapter, an unknown CLI version or a richer source as grounds to substitute one source for another outside the reviewed profile.
**Reason:** Those are implementation gaps or research observations; letting them change the inputs makes the capture boundary depend on adapter maturity instead of on a reviewed profile.
Tests: `UntestedCompatibilityTest#aVersionNoEntryCoversIsRetainedFlaggedAndNotNormalized`, `CliCaptureTest#aVersionTestedOnAnotherRouteOnlyIsNoMatchRetainedAndFlagged`.

### RULE-4
**MUST** keep archive-only material (a native file or log no profile declares) byte-identical with its checksum outside the assembled sources, and **MUST** leave every original package and native input unchanged by final assembly.
**Reason:** The archive and the originals are the fallback and the evidence; assembly writes a new file and never rewrites what it read.
Tests: `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (archived byte-identical with its sha256), `FinalAssemblyClaudeTest#assemblyLeavesItsInputsAndTheOriginalPackageUnchanged`, `FinalAssemblyCopilotTest#theFinalFileReadsAloneAndTheOriginalsAreUnchanged`.

### RULE-5
**MUST** assemble a complete capture's declared sources automatically after acquisition, without a per-run enrichment request, into a new `events.jsonl` written through the one writer, consolidating each logical entity several sources observe into one canonical event and keeping each source's observation as a supporting observation.
**Reason:** Consumers must not reconstruct an execution by merging several event streams themselves; one deterministic mechanism serves every CLI and differs only by its reviewed profile.
Tests: `CliCaptureTest#aCompleteCaptureAssemblesTheFinalPackageAutomatically`, `FinalAssemblyClaudeTest#thePairAssemblesIntoOneCanonicalExecutionTheOracleVerifies`, `FinalAssemblyCopilotTest#theCanonicalEventsCarryTheUnionOfTheSources`.

### RULE-6
**MUST** state a fact no declared source reports as absent from the declared sources, never fill it from an undeclared artifact, and keep an absent value absent, never zero.
**Reason:** A consumer can act on a declared gap; a filled gap hides which source reported the value, and a defaulted zero is a false measurement.
Tests: `StreamLimitationTest` (declared limitations travel in the event file), `FinalAssemblyCopilotTest#anAbsentPerSpanCacheWriteStaysAbsent`.

## CLI version and mapping applicability

### RULE-7
**MUST** retain the CLI version the stream reports and MUST NOT launch a separate version command when the stream reports one.
**Reason:** A redundant subprocess costs time, can disagree with the running process, and adds nothing the stream did not already say.
Tests: `LiveCaptureSeamTest` (launch without a version; `cli_version` declaration from the stream), `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (no version command for Claude), `OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect`; the capture seam spawns no process.

### RULE-8
**MUST** use one documented, adapter-specific discovery path when the selected source reports no version, without repeated probes.
**Reason:** Codex's tested exec stream carries no version; discovery must be bounded and explicit so it cannot become a hidden dependency on the native session file.
Tests: `CliCaptureTest#codexVersionIsDiscoveredOnceByTheDocumentedCommandAndTheThreadIdBecomesTheSession`, `CliCaptureLifecycleTest#aHangingVersionProbeIsBoundedAndReported`, `CliCaptureUnitTest#theVersionProbeParsesBothOutputFormsAndRefusesUnrecognizableOutput`, `UntestedCompatibilityTest#aDiscoveredVersionCarriesItsBasis`.

### RULE-9
**MUST** retain input that arrives before the version is known, without blocking persistence on mapping selection, and **MUST NOT** normalize a record's vendor semantics before a compatibility entry is selected: a declared version bootstrap may read only the version and its location, and when the record that reports a supported version arrives, the selection is declared first and that same record is then normalized under it; a record that arrived before any selection stays retained whole and unnormalized, is never interpreted provisionally or reinterpreted by a later selection, and is never counted as canonical coverage.
**Reason:** The first records of a stream are evidence; waiting for a version to choose a mapper would lose them or force a buffer the recorder cannot bound, while interpreting them under a mapper not yet selected would present facts no selected contract established. Reading the version alone lets the init record keep its own settings without guessing at anything earlier.
Tests: `CompatibilityTest#theVersionBootstrapReadsOnlyTheVersionAndItsLocation`, `UntestedCompatibilityTest#aSupportedInitRecordSelectsFirstAndIsThenNormalizedUnderTheSelection`, `UntestedCompatibilityTest#aRecordBeforeTheInitRecordStaysRetainedOnlyAndIsNotCredited`, `UntestedCompatibilityTest#aRecordBeforeTheVersionIsRetainedUnnormalizedThenNoMatchCreditsNothing`, `UntestedCompatibilityTest#aVersionNeverLearnedRetainsEveryRecordUnnormalizedAndUnresolved`, `PackageReaderTest#aPackageWrittenWithProvisionalPendingInterpretationStillReadsAndIsJudgedByTheCurrentPolicy`, `LiveCaptureSeamTest` (records before the version is known are persisted; the version is declared when reported). Status: implemented; no record is interpreted before a selection. A saved session that spans a CLI upgrade remains a separate, deferred case.

### RULE-10
**MUST** select the source description and Java mapper for a source through exactly one explicit compatibility entry naming the CLI, source format or profile, launch route or mode, inclusive minimum and maximum CLI versions, source-description revision, schema, mapper identity and tested evidence, and MUST record that selection with the version and whether the version is within the entry's tested range; and **MUST** treat contradictory version assertions within one capture (the launch declaration and a record, or two records) as an ambiguous selection: keep every assertion with its origin, record a persistent conflict, prefer none of them, stop vendor normalization for the rest of the capture while keeping what was already written, and never report the source as supported, while an identical repeated assertion changes nothing.
**Reason:** Recording a version does not implement version-based mapping selection; the file has to say which entry judged it and whether that pairing was ever tested. Choosing the first of two contradictory versions would silently pick a mapper the evidence does not support. Overlapping or ambiguous entries are configuration errors, and a description may keep the name of its lowest supported version while a reviewed range grows.
Tests: `CompatibilityTest` (exact and range selection, overlap and misconfiguration rejected when the registry loads, each declared status, the conflict selection), `UntestedCompatibilityTest#aTestedVersionOnItsRouteIsSelectedAndVerified`, `UntestedCompatibilityTest#contradictoryStreamAssertionsStopNormalizationAndKeepThePrefix`, `UntestedCompatibilityTest#aLaunchDeclarationContradictedByTheStreamIsAConflict`, `UntestedCompatibilityTest#aLaunchProbeContradictedByARolloutRecordIsAConflict`, `UntestedCompatibilityTest#anIdenticalRepeatedAssertionIsIdempotent`, `CompatibilityAndIdentityOracleTest` (the oracle's own check of the declared selection), `PinsTest#mappingContractsDeclareEveryRuleTheMappersExecute`. Status: implemented (`ocsf/compatibility.json`, exact tested entries); the selection-time ambiguity error is unreachable behind the load-time validation.

## Common model and preservation

### RULE-11
**MUST** define portable fields as OCSF extension classes and objects using ACP's interaction vocabulary, informed by Spring AI, OTel/OpenInference, Weave and Arize concepts.
**Reason:** OCSF supplies schema authoring and validation; ACP supplies familiar names; the observability models supply tested field meanings, so none of them needs to be reinvented.
Tests: `SchemaCatalogTest#savedValuesAreCheckedAgainstDeclaredTypesEnumsAndRequirements`, `PinsTest#bundledExtensionSourceListsEveryFileOfTheExtensionDirectory`.

### RULE-12
**MUST NOT** populate a portable field with a value that the selected source did not report and that no declared, source-grounded rule establishes exactly from what it did report, even when a framework or SDK defines a field for it; **MUST** keep source-reported values, declared derivations, Journal-generated values (identities, capture times) and caller-supplied values distinguishable by their stated basis, and **MUST NOT** present a derived, Journal-generated or caller-supplied value as source-reported.
**Reason:** A field's existence in a model is not evidence that this CLI reported the fact; absence, zero, derived totals and Journal's own bookkeeping must stay distinguishable from what the vendor said. A declared derivation is permitted when the pinned source establishes its arithmetic and operands, with its rule and operands stated (RULE-43); an invented execution fact never is, and a formula that holds only under an assumption is not an established value.
Tests: `OcsfOracleMutationTest#aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero`, `OcsfOracleValueCheckTest#responseContentPresentWhereTheSourceHasNoneIsADefect`, `UsageUncertaintyControlTest`, `OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect`, `CliCaptureTest#aCompleteRunIsReadableBeforeExitCompletedAcquiredAndJudgedAgainstTheIndependentCopy` (caller-supplied prompt provenance).

### RULE-13
**MUST** preserve every meaningful fact of the selected input either in a portable field or in vendor-specific residual or fallback data, including structured results, unknown content, exact numeric values, null versus absence, empty containers, source ownership and meaningful order.
**Reason:** Consumers must not have to reopen native files for information the selected input supplied.
Tests: `OcsfOracleMutationTest#removedWholeRecordIsAGapInCoverageAndReconstruction`, `OcsfOracleMutationTest#largeIntegerAndHighPrecisionDecimalAreExact`, `OcsfOracleMutationTest#jo03RetainedJsonNumberTurnedIntoBooleanTrueIsDefective`.

### RULE-14
**MUST NOT** apply length or value thresholds, value-search ownership, or silent truncation when preserving selected-input content.
**Reason:** Short identifiers, booleans and small integers are facts; matching by value pairs the wrong owners; a cap that is not declared is data loss.
Tests: `OcsfOracleMutationTest#identicalResultsSwappedBetweenOwnersAreADefect`, `OcsfOracleMutationTest#resultOwnedByAnIdQuotedInsideItsOwnTextIsADefect`.

### RULE-15
**MAY** retain whole native records beside portable fields while compact residual extraction is unverified.
**Reason:** Whole-record retention is a conservative, temporary implementation that keeps every value reachable until extraction has its own preservation tests.
Tests: `OcsfOracleMutationTest#normalizedRecordRetainedOnlyAsFallbackIsAMissingNormalization` (fallback is retention, not normalization).

### RULE-16
**MUST** justify each omission from residual data as proven redundancy or noise under an explicit, tested rule, and MUST treat identical values under different identifiers or owners as distinct facts.
**Reason:** Compaction that removes a value because an equal value exists elsewhere destroys ownership; only a reviewed rule with counterexamples can authorize an omission.
Tests: `AnalysisViewsTest#identicalResultContentUnderTwoOwnersIsKeptSeparate`; no residual-extraction rule exists yet.

### RULE-17
**MAY** differ from the native representation in JSON whitespace, line endings and equivalent numeric spelling without that counting as preservation loss.
**Reason:** Byte-exact reconstruction of a native file is not the product contract; semantic value, structure, ownership and order are.
Tests: `OracleFullSourceTest#changingARetainedNumberIsAStructureDefectAndRespellingItIsARepresentationDifference`, `OcsfOracleMutationTest#costSpellingIsRepresentationValueIsValue`.

## The Java writer

### RULE-18
**MUST** use one Java/Jackson writer implementation for both recorded replay and live capture input.
**Reason:** Two writers drift; replay evidence is only evidence about live capture if the same code produced both.
Tests: `WriterLifecycleTest#captureFacingRecordInputProducesTheSameBytesAsChunkedReplay`.

### RULE-19
**MUST** persist incrementally so that, after a declared flush, a reader can open a consistent package while the writer is still open.
**Reason:** A recorder that is only readable after the CLI exits loses everything on a crash and cannot be inspected during a long run.
Tests: `WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof`, `PrefixReplayTest`.

### RULE-20
**MUST** declare the recording state honestly: final counts and hashes only when the corresponding bytes are finalized, interrupted sources as interrupted, and a failed write as failed rather than complete.
**Reason:** A package that claims completion it cannot prove is worse than an honestly partial one.
Tests: `WriterLifecycleTest#injectedWriteFailureLeavesAnHonestFailedPackage`, `WriterAcquisitionRegressionTest#eofInsideARecordIsFinalizedHonestlyAndNotAcceptedAsComplete`.

### RULE-21
**MUST** assign stable identities for the run, each invocation and each source occurrence, and MUST keep native identifiers in their native scope rather than reusing one identifier across scopes.
**Reason:** Repeated invocations, repeated tool names, identical results and child runs are only distinguishable when every scope has its own identity.
Tests: `OcsfOracleMutationTest#missingDuplicatedAndReorderedRowsAreDefects`, `OcsfOracleValueCheckTest#recordIndexGoingBackwardsWithinAnArtifactIsAnOrderDefect`.

### RULE-22
**MUST NOT** compute cross-source logical counts, significant aggregation, scoring or conclusions in the live writer path.
**Reason:** Those are consumer analyses with their own provenance; the writer records identifiers, order, reported scope and finality, and final assembly relates observations only through declared identities and states each consolidation with its evidence, leaving counting to tests and consumers.
Tests: `CodexExecStreamTest#viewsMatchTheIndependentlyAuthoredAnswersAndStateTheirCountUnits` (views are test utilities, not writer output).

### RULE-23
**MUST NOT** depend on Agent Judge, a model runtime or any network service in the writer or the capture path.
**Reason:** Requirements are inert data evaluated by tests; capture must work offline and remain free of evaluation tooling.
Tests: the Agent Judge dependency is test-scoped in `journal-capture-tck/pom.xml` and `journal-ocsf-writer/pom.xml` (the accounting IT); neither module declares an evaluation or model dependency in a runtime scope, which the requirements accounting checks directly.

## Portable output

### RULE-24
**MUST** write `events.jsonl` as the independently portable unit: explicitly versioned declaration records carrying the actual schema definitions, the mapping-contract identity and the essential shared run, source and invocation context, each written once per file and before any event that references it, followed by the ordered execution events; `run.json` is an ancillary index and summary.
**Reason:** A run has to be interpretable after only its event file is copied somewhere else; per-event schema repetition, sibling-file dependencies and remote registries all fail that test. Changed meaning (2026-10-21): the portable unit was the two-file core with linked children; it is now the event file alone, with each child event file carrying its own declarations.
Tests: `StandaloneEventFileTest`, `StandalonePrefixTest` (layout 0.3.0-prototype); `IsolatedBootstrapTest#aCopiedCoreWithItsChildBootstrapsWithoutAnythingElse` (two-file core).

### RULE-25
**MUST** be readable by a compatible Java reader from `events.jsonl` alone, without `run.json`, sibling files, the CLI, a hosted service, a schema cache, external schema resolution, optional analysis files or generated accessor code.
**Reason:** Reader software is a versioned dependency; everything else the reader needs must travel inside the event file. A copy of the schema can speed reading but cannot supply a missing mandatory definition.
Tests: `StandaloneEventFileTest#theParentEventFileAloneIsInterpretedFromItsDeclarations`, `OcsfReaderToolTest#everyCommandReadsAnEventsFileAloneAndOnlyTheOptionalViewsNeedTheAncillaryManifest`, `IsolatedBootstrapTest`.

### RULE-26
**MUST NOT** make `run.json`, an optional analysis file, a feedback file or the native archive the only location of a selected-input execution fact or of a definition needed to interpret one.
**Reason:** Event-file acceptance omits those files; a fact or definition that lives only there is one the event file lost.
Tests: `OcsfOracleMutationTest#removedWholeRecordIsAGapInCoverageAndReconstruction` judges the core without optional files.

### RULE-27
**MUST** keep externally produced feedback durable with producer, time and target provenance when it is produced, without making it a prerequisite for capture.
**Reason:** Judgments attached to a run are valuable and must survive, but their absence says nothing about whether execution facts were preserved.

## Coverage, provenance and testing

### RULE-28
**MUST** demonstrate each claimed feature with a committed fixture in which the feature actually occurred and an independent assertion that checks it, and MUST report scenarios without such a fixture as gaps.
**Reason:** Asking a model to use a feature does not establish that it did; a finite corpus is scoped support, not universal vendor coverage. Schema validity, mutation sensitivity, recorded replay and live capture are different kinds of evidence and none substitutes for another.
Tests: `CodexExecStreamTest`, `OcsfOracleFixtureTest#sanitizedClaudeStreamWithChildCoreIsJudgedPerCoreWithoutDefect`, `LiveFixtureFeaturesTest`; interruption is exercised on live cancelled recordings of both CLIs, while media and redacted reasoning remain unexercised.

### RULE-29
**MUST** establish acceptance with the independent Java TCK, which MUST NOT call production capture parsers, SDK deserializers or mappers to compute expected values.
**Reason:** A checker built from the code it checks cannot disagree with it.
Tests: `OcsfOracleMutationTest`, `OcsfOracleValueCheckTest`, `FullOneReviewRegressionTest` (independent reader and hand-authored expectations).

### RULE-30
**MUST** label every fixture, reference, example, report and claim with its evidence class from the project's declared vocabularies, keeping what an artifact is (a pinned definition or specification; a pinned source, schema, type or serializer inspection; a sanitized recording of a live run; a sanitized real stream recorded elsewhere; labelled synthetic input; a publisher-normalized corpus) apart from how a check executed it (a live run, a replay of a recording, a synthetic replay), and **MUST NOT** present one class as another.
**Reason:** Replay through a recorded seam is regression evidence; only a live run establishes live lifecycle integration. A capability an SDK type or serializer declares is not observed until a recording shows it, and a prompt that asks for a feature does not establish that it was exercised. A publisher's normalized corpus (renamed tools, rewritten arguments, positional call ids) is neither native output nor a live run: it can motivate a reviewed synthetic case but never stand in for one, and one artifact can have a provenance class and a separate execution class.
Tests: `FixtureEvidenceClassTest`, `LiveFixtureFeaturesTest#everyFixtureStatesItsEvidenceClassAndLiveOnesCarryProvenance`, `LiveScenarioTest#anUntriggeredBehaviorLabelledObservedAndAMislabelledEvidenceClassAreDetected`, `CoverageDocumentSchemaTest#aPublisherNormalizedCorpusIsItsOwnEvidenceClassAndNeverAnExamplesNativeInput`, `SemanticClaimTest#theCommittedClaimsExamplesReferencesAndDecisionsAreConsistent`.

### RULE-31
**MUST** publish only deliberately reviewed and sanitized material as public test data, with a manifest of capture command, CLI version, mode, source, hashes, sanitization and origin.
**Reason:** Even synthetic prompts produce local paths, account metadata and injected instructions that must not leak.
Tests: `LiveFixtureFeaturesTest#everyFixtureStatesItsEvidenceClassAndLiveOnesCarryProvenance` (every fixture's spec, and each live recording's stream and provenance, carry no local user or session paths; live recordings name their capture command, hashes and sanitization steps). The automated check is narrow: it is not a general privacy review, which stays deliberate.

### RULE-32
**MUST** compare the requirement roster parsed by Agent Judge with every intended identifier in these documents, and MUST NOT infer compliance from a successful parse.
**Reason:** The readers accept specific heading shapes and skip malformed entries; a roster that silently shrank would evaluate a specification nobody wrote.
Tests: `RequirementsRosterTest`, `NativeRequirementTest`, `RequirementsEvidenceTest`.

## Compatibility, dispositions and common-model maintenance

### RULE-33
**MUST** retain and flag as untested any acquired input whose CLI version, source format or launch route matches no compatibility entry, and MUST NOT select the numerically nearest entry or imply support for it.
**Reason:** Two tested endpoints do not prove every intermediate release and nothing proves a future one; a no-match input is evidence to keep and a gap to report, not a reason to guess a mapper. Any partial untested normalization or strict mode is a separate tested proposal.
Tests: `UntestedCompatibilityTest`, `CompatibilityTest#outsideEveryRangeOrOffEveryKeyIsNoMatchAndTheNearestEntryIsNotSelected`, `CliCaptureTest#aVersionTestedOnAnotherRouteOnlyIsNoMatchRetainedAndFlagged`, `CompatibilityAndIdentityOracleTest#aNoMatchSelectionIsHonestOnlyWhenNothingAfterItWasNormalizedAndNoEntryIsNamed`. Status: a no-match leaves every record retained, the record that reported the version included.

### RULE-34
**MUST** give every meaningful acquired field exactly one disposition: normalized into a portable field, retained in vendor residual or fallback data, omitted under a reviewed and tested redundancy or noise rule, or unresolved with retention; and **MUST** give every field the pinned vendor inventory declares for a supported route a family-specific disposition, whether or not a recording has carried it (unexercised until one does).
**Reason:** A field whose fate is unknown is a field that may have been lost; unknown semantics justify retention, never silent omission. Arbitrary user and tool data keys are dispositioned apart from the vendor envelope.
Tests: `FieldDispositionTest` (every observed field; vendor-declared entries kept as unexercised), `VendorStreamInventoryTest` (every declared field dispositioned per family, every observed field declared, replay behavior as the inventory states), `InventoryDriftTest` (observed paths and presence).

### RULE-35
**MUST** derive common-model comparison matrices from reviewed structured mapping-catalog entries (the mapping catalog, formerly called the crosswalk, is the table of how each vendor's fields correspond to Journal's common concepts), each naming its source description and version range, record variant and field paths, canonical concept and destination, ownership and scope, mapper rule and independent test, with verified, retained-only, unavailable-at-source, unexercised and unresolved states kept separate, and MUST NOT discover a correspondence by matching equal field values.
**Reason:** Equal values and overlapping shapes do not establish equal meaning; human review proposes a correspondence and fixed Java checks challenge it with counterexamples. The matrix is a calculation over accepted entries, not a rediscovery.
Tests: `MappingCatalogTest` (reference validity in Java and the derived CLI coverage table), `CatalogCoverage` and `MappingCatalogSummaryTest` (owed results from frozen populations), `FoundationComparisonTest` (foundation, preservation and projection tables from the same catalog); the catalog's earlier Python reference checker is historical tooling.

### RULE-36
**MUST** establish every required property, every applicable owner, every native occurrence and every scenario a criterion or a mapping-catalog concept names before reporting that criterion as passed or that concept as verified, with each owed result retained as inspectable evidence, treating a configured check that is missing from the evidence as an error, an observed wrong value as a failure, and an unexercised trigger or insufficient evidence as not assessed.
**Reason:** A reducer that passes on whichever checks happened to appear accepts partial evidence as full; a pass must be scoped to the obligations, owners, occurrences and scenarios actually exercised, and its basis must be inspectable afterwards. Explicitly declared subject applicability may yield not-applicable; a trigger absent from a fixture is not such a declaration.
Tests: `RequirementsEvidenceTest`, `RequirementsReporterReproductionTest`, `MappingCatalogSummaryTest` (the catalog's coverage summary, with every judged obligation retained in `coverage-evidence.json`).

### RULE-37
**MUST NOT** repeat schema definitions per event, introduce a mapping or transformation language, generate a reader project, or call a model in a routine acceptance gate.
**Reason:** Each of these adds a second model, a second runtime or an unrepeatable judge; deterministic Java checks over declared definitions are the ordinary acceptance mechanism, and AI assistance stays in review proposals.
Tests: the RULE-23 build-file inspection in `RequirementsEvidenceTest` covers the writer's freedom from model and evaluation dependencies; the rest is enforced by review.

### RULE-38
**MUST** report a concept or claim as approved support only when a recorded decision approves the exact semantic claim revision and digest, made by the Project Owner or by a reviewer the Owner explicitly delegated for that decision pass and recorded under that delegated identity, and that claim names the CLI, profile, version and route it applies to.
**Reason:** A reviewed date, a source citation, an author's judgment or a passing build is not an approval; an approval of one wording does not extend to another, and support without its route is a claim about every route. A delegated decision is not presented as the Owner's direct review of each digest, and an implementing agent does not approve its own meanings.
Tests: `SemanticClaimTest` (decisions in `owner-decisions.json`, stale and borrowed approvals refused, the delegated identity stated).

### RULE-39
**MUST** link every semantic claim to pinned supporting evidence, its canonical destinations in the compiled schema and independently authored expected examples with counterexamples, each resolvable, and MUST NOT treat a citation or the agreement of the mapper and the oracle as proof of the interpretation.
**Reason:** A citation shows where a meaning came from, and two implementations can share a wrong premise; a concrete expected example, grounded in the pinned source and challenged by a counterexample, is what the Owner reviews and what the tests enforce on both implementations.
Tests: `SemanticClaimTest`, `SemanticExampleTest` (writer), `SemanticExampleProfileTest` (oracle profile).

### RULE-40
**MUST** accept NOT_APPLICABLE for an applicable obligation only when the owner's own native records, read independently of the candidate, hold zero occurrences of the property's declared trigger, and MUST report such an obligation as unexercised with that basis, distinct from a check that is missing.
**Reason:** Absence of evidence is not evidence of absence: a NOT_APPLICABLE beside a good sibling, or derived from the candidate's own output, would hide a required result; a genuinely absent trigger is an honest gap, not a failure and not a pass.
Tests: `MappingCatalogSummaryTest`.

### RULE-41
**MUST** require a new claim revision and a new approval for any change to an approved meaning (its statement, semantics, destinations or normative examples), and **MUST** refuse an approved binding whose relevant content (the extension definitions of the claim's destinations and the contract rules it names) changed; an approval **MAY** carry across a binding change that alters only version pins or example file bytes while that relevant content is unchanged, and **MUST NOT** extend to an added route before that route's binding is reviewed; data preservation and clearly labelled experimental verification of a pending meaning or binding continue.
**Reason:** An approval binds a meaning, not a mapper; supporting evidence may grow without reapproval, but a changed meaning under an old approval would present an unreviewed interpretation as approved. Version pins inside the meaning made every unrelated schema bump reset unchanged meanings, while removing pins from all protection would let a changed definition or conversion ride an old approval. A new route is a new source version whose behaviour has not been reviewed. Pending meanings still need their data kept and their candidate behaviour tested.
Tests: `SemanticClaimTest` (digest boundary 2: changed meaning refused, added evidence accepted, a pin-only bump carried, a changed definition or rule refused, an added route awaiting review, failed checks leaving an approval unverified).

## Exact addressing and canonical semantics

### RULE-42
**MUST** address an exact location in a native record by RFC 6901 JSON Pointer or equally unambiguous member and index steps, and **MUST** record a repeated object member at the exact location and occurrence of its containing object with every value in source order; **MUST** interpret a repeated member only in a declared form at that exact location, keep strict parsing strict otherwise, and write canonical events whose objects repeat no member.
**Reason:** A dotted path cannot tell the member `item.id` of the root from the member `id` of the root's `item`, and a pointer alone cannot tell repeated members of one object apart; a repeat at any other location, or one whose name only spells the declared path, must stay an opaque record with no interpreted fact. A pointer establishes a location, not a meaning or an ownership join.
Tests: `DuplicateMemberAddressingTest` (writer), `CodexDuplicateMemberProfileTest` (oracle profile), `CodexStreamMapperTest#onlyTheDeclaredWebSearchDuplicateIsInterpretedAndEveryOtherDuplicateStaysOpaque`.

### RULE-43
**MUST** state reported token usage in canonical tranches whose meaning is the same on every source: uncached input (excluding cache reads and cache writes), cache reads, cache writes with any reported retention or pricing tiers as parts of the writes, and output with the stated relation of its reasoning part; **MUST** keep the native usage object exactly; **MUST** write a tranche's canonical value only when it is copied from a member whose meaning is that tranche or derived by a declared rule whose arithmetic, operands and inclusion the source establishes, and otherwise state the tranche unavailable rather than zero or a guess; **MUST** state aggregation (scope total or cumulative snapshot) separately from scope and finality; **MUST**, where a declared rule establishes it, state for each component what the source establishes about its quantity (measured, possibly the source's default, at least the stated value, or conditional) apart from how Journal obtained it, keeping every reported number; **MUST** keep the result of a formula that is the tranche only under named assumptions as that component's conditional value, naming the assumptions, never as the canonical value; and **MUST NOT** treat a non-zero cumulative value as establishing that every contribution to it was reported or measured.
**Reason:** A field spelled the same with a different meaning per vendor is not normalized, and a convenient total hides tranches that are priced differently; an unproven formula that balances, or a missing component taken as zero, invents a measurement. Cumulative snapshots summed with each other or read as a unit's consumption double count. A zero the source also writes for an omitted member is not a measured zero even inside non-zero usage; the quantity a running total counts is at least that total when it adds only the contributions it received, however large the total (a reported 100 is compatible with an actual 150), and equal prices across tranches can make the allocation of an uncertain operand immaterial but never recover a missing contribution, so an estimate from a lower bound is itself only a lower bound; and a value that holds only on an assumption stays visibly conditional, so that neither a consumer summing a canonical field nor a price estimate silently inherits the assumption.
Tests: `SemanticExampleTest#equivalentClaudeAndCodexUsageStateEqualTranchesAndTheUnestablishedOneOnlyConditionally`, `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `CodexExecStreamTest#anOffByOneUsageValueOrAMissingComponentIsAUsageValueDefect`, `UsageComponentMeasurementTest`, `UsageComponentMeasurementProfileTest`, `UsageUncertaintyControlTest` (a lower bound raised to an assumed total, a conditional value written as canonical, equal prices not making an incomplete total exact).

### RULE-44
**MUST NOT** present a source-local item identifier as a model-facing call identifier or a provider's response identifier, and **MUST** state its identity basis and treat it as unique only within its source artifact and Journal invocation; **MUST NOT** require, derive or fabricate a response identity in order to record content the source reports without one.
**Reason:** An id a CLI's stream mints for its own items cannot be joined with the model's call ids, and equal ids in two invocations name two items; a content item recorded under an invented response id would count or merge model invocations the source never reported.
Tests: `CodexStreamMapperTest#equalItemIdsOfTwoInvocationsAreTwoItemsOfTheirOwnNamespaces`, `CodexStreamMapperTest#equalToolItemIdsOfTwoInvocationsAreTwoStreamLocalCallsNeverModelFacingOrResponses`, `IdentityClassificationControlTest` (independent oracle: a forged response identity, a model-facing basis, a call identity on a standalone operation), `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`.

### RULE-45
**MUST** record a tool activity at the lifecycle stage the source reports, so that a started invocation is not recorded as the model's request and a result does not imply an observed start; **MUST** record a tool name only where the source reports one, the source's family or item type separately, and classify a tool by a declared family table, a declared exact-name table or a reviewed binding of its exact namespace and name, never by a bare name that coincides with another namespace's tool; **MUST NOT** infer an additional tool invocation, operation, argument vector or operating-system action from the text of a command, script or pipeline that one reported invocation carries, while recording every operation the source reports separately as its own observation.
**Reason:** An execution observation is not evidence of the model's original request, its arguments, a response owner or a permission decision; an item family is not a tool name; and an MCP tool named like a built-in tool is another tool, so a name table across namespaces assigns a wrong kind that shape validation accepts. A Bash command containing grep, Python or a pipe is one reported invocation: what runs inside it is shell semantics the stream did not report, and classifying it is a consumer's analysis, not an execution fact.
Tests: `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `PackageReaderTest#aPackageWrittenWithExtension050StillReadsAndShowsOnlyTheRevisedMeanings`, `ToolClassificationTest`, `IdentityClassificationControlTest`.

### RULE-46
**MUST** keep a reasoning summary distinct from full and from redacted reasoning, preserve each content observation with its own source identity and order, and **MUST NOT** count or merge content observations into model invocations or responses the source does not report.
**Reason:** A summary is not the model's reasoning, and two observations in one stream are neither two known invocations nor one inferred invocation; their relation stays unknown until a source reports it.
Tests: `SemanticExampleTest#everyReviewedExampleDescribesTheWritersOutputAndNoCounterexampleDoes`, `SemanticExampleProfileTest#theOracleProfileStatesWhatEachReviewedExampleExpects`, `LiveFixtureFeaturesTest#theCodexRecordingExhibitsOperationsFailureRepetitionAndUsage`.

## Tier 1 foundation accounting

### RULE-47
**MUST** account for every field the pinned Tier 1 execution-domain foundations declare (OTel GenAI, OpenInference/Arize and Weave at their pinned revisions) exactly once in the parity ledger, a proposed exclusion included; **MUST** keep a proposed exclusion in the denominator, visible and reported as proposed, until a recorded decision disposes of it; **MUST** report availability at source, definition, mapper implementation, semantic approval and executed evidence apart; **MUST NOT** count raw retention as canonical parity; **MUST NOT** report a facet's concepts verified on a route while an applicable property, owner or occurrence lacks its executed result; and where a facet's support differs by part (a role, or a marking apart from its meaning), **MUST** state each part with one of the facet's own concepts and a route state that agrees with that concept's executed state on that route, and **MUST NOT** report the facet covered while a part is unexercised or retained only on a route.
**Reason:** Tier 1 is the useful union of the three foundations, not their intersection and not what every CLI emits: a capability any one of them represents is in scope, and whether a route supplies it is a separate, per-route fact. An entry that leaves the denominator, an exclusion that disappears or is re-tiered without a decision, or retained raw data counted as a mapping would make parity look complete when it is not; a reference check proves accounting, not semantic correctness.
Tests: `ParityLedgerTest#everyPinnedFieldIsAccountedForExactlyOnce`, `ParityLedgerTest#eachCorruptionIsDetected`, `ParityLedgerTest#everyFacetAspectAgreesWithItsConceptsExecutedStatePerRoute`, `ParityLedgerTest#theLedgerAndTheTopicExplanationAreGenerated`, `MappingCatalogSummaryTest` (executed verification needs every property, owner and occurrence).

## Final assembly semantics, MCP containment and source availability

### RULE-48
**MUST** relate observations of different sources only through identities a reviewed profile declares (namespaced identifiers, bridges through another source, or a declared execution singleton), never by position, timestamp proximity or similar text, and **MUST** leave observations whose identity repeats within one source unpaired and flagged ambiguous.
**Reason:** A guessed pairing fabricates a fact; a repeated identity cannot say which occurrence is which.
Tests: `FinalAssemblyClaudeTest#anIdentityRepeatedWithinOneSourceStaysUnpairedAndFlagged`, `FinalAssemblyCopilotTest#theFourSourcesAssembleIntoOneExecutionTheOracleVerifies` (bridges through the provider call id).

### RULE-49
**MUST** merge compatible reports of one entity field by field with per-field evidence, **MUST** keep every conflicting report listed with its occurrence, and **MUST NOT** apply a universal source-wins rule; a value may be selected only by a field authority the profile declares with its reason.
**Reason:** Silent precedence hides disagreement; a declared authority is a reviewable decision that keeps the other values visible.
Tests: `FinalAssemblyClaudeTest#theOracleRejectsAlteredConsolidation`, `FinalAssemblyCopilotTest#theCanonicalEventsCarryTheUnionOfTheSources`.

### RULE-50
**MUST** carry each reported quantity once per canonical event (one usage observation per scope, aggregation and model, a final observation superseding a provisional one; one cost observation per quantity kind, scope, currency, unit and model), and **MUST NOT** compare, add or convert quantities of different kinds (money, premium requests, model cost multipliers, nano-AIU, credits, or a cost whose unit the source does not name).
**Reason:** Overlapping reports counted twice, or a multiplier read as a request count or a currency, are false accounting.
Tests: `FinalAssemblyCopilotTest#aMultiplierSumAndPremiumRequestsAreDistinctQuantities`, `FinalAssemblyJunieTest#theSessionDirectoryAssemblesWithItsOutputsAndTheOracleVerifiesIt`, `FinalAssemblyClaudeTest#theOracleRejectsAlteredConsolidation`.

### RULE-51
**MUST** identify an imported source by its acquisition origin and its sha256: replaying the same origin and bytes adds nothing, while identical bytes from distinct origins stay distinct sources whose observations are one entity, never extra executions.
**Reason:** Idempotent import prevents double counting; distinct provenance must not be erased by equal bytes.
Tests: `FinalAssemblyClaudeTest#replayingTheSameImportAddsNothingWhileIdenticalBytesFromAnotherOriginStayDistinct`.

### RULE-52
**MUST** declare a final assembly in the event file itself: the profile by value, the engine and its contract, the original packages it derives from, every declared source with its role, origin, extent, hash and verdict, and the result with missing required roles and the assembly status.
**Reason:** The final file must read alone, with native files, `run.json` and schema caches unavailable, and still say how it was assembled and whether it is complete.
Tests: `FinalAssemblyClaudeTest#theFinalFileReadsAloneWithoutNativeFilesManifestOrSchemaCaches`, `FinalAssemblyClaudeTest#aMissingRequiredSourceMakesTheAssemblyIncomplete`.

### RULE-53
**MUST** embed MCP protocol data in typed objects generated from the pinned official versioned MCP schemas, declaring a CLI's partial projection with the required members it lacks, and **MUST NOT** synthesize request ids, protocol revision, capabilities, structured content, isError or success; JSON-RPC, tool-result, CLI-reported and capture errors stay distinct.
**Reason:** MCP semantics belong to the protocol's own definitions; a CLI shows a partial view, and inventing the rest would misstate the wire exchange.
Tests: `McpProtocolContainmentTest`, `FinalAssemblyCopilotTest#theCanonicalEventsCarryTheUnionOfTheSources` (two partial projections merge without inventing members).

### RULE-54
**MUST** state, for each source specification, which fact families the source reports with selectors and their observed match populations, and **MUST** reject an absence claim the inventory contradicts, a zero-match selector marked exercised, and vendor usage hidden under a subtree inventoried as tool data or missing from the specification.
**Reason:** A review once declared a source token-free that carried nested usage; executable gates on the inventory keep onboarding claims tied to what was observed.
Tests: `SourceAvailabilityTest`.

## Change log

2026-10-21, consolidated event-file work order (REQ-1 amendment). Retained identifiers with changed
meaning: RULE-5 adds version discovery to the forbidden uses of the archive; RULE-10 now requires
explicit compatibility entries and single selection; RULE-12 adds the labelling of Journal-generated
values; RULE-24 and RULE-25 move the portable unit from the two-file core to `events.jsonl` alone,
retiring the earlier dependency on a sibling `run.json`; RULE-26 adds `run.json` to the files that
cannot be the only home of a fact; RULE-28 names the distinct evidence classes. Added RULE-33 to
RULE-37. No rule was removed; `RequirementsRosterTest` pins 37 rules.

2026-10-21, two-CLI streaming acceptance (ACCEPT-1): terminology only, no obligation changed: "mapping catalog" replaces "crosswalk" in RULE-35 (the Owner's clearer name for the table of vendor-field to common-concept correspondences); RULE-35's Tests: line now names the Java check.

2026-10-22, foundation comparison (FND-1): RULE-2 clarified (automatic live-capture selection; explicit offline import
is a separate mode), no obligation changed; RULE-34 extended to every field the pinned vendor inventory declares for a
supported route, observed or not, with its tests named; RULE-35's Tests: line names the repaired coverage summary and
the foundation comparison. No rule added or removed; `RequirementsRosterTest` pins 37 rules.

2026-10-22, semantic evidence consolidation (SEM-1). Retained identifier with changed meaning: RULE-36 now also covers a
mapping-catalog concept reported verified, adds every native occurrence to the owed obligations and requires the
owed results to be retained as inspectable evidence. Added RULE-38 (approved support names the Owner-approved claim
revision, digest and route), RULE-39 (claims link pinned evidence, destinations and independently authored examples),
RULE-40 (NOT_APPLICABLE needs the owner's independently established zero occurrences) and RULE-41 (a changed meaning
needs a new revision and approval; preservation and labelled candidate testing continue). No rule removed;
`RequirementsRosterTest` pins 41 rules.

2026-10-22, delegated semantic closure (SEM-CLOSE). Retained identifier with changed meaning: RULE-38 now also accepts a
decision the Project Owner explicitly delegated to an identified reviewer for a decision pass, recorded under that
delegated identity and not as the Owner's direct review of each digest. Added RULE-42 (exact RFC 6901 addressing; a
repeated member is recorded at its containing object's exact location and occurrence and interpreted only in a declared
form there; canonical events repeat no member), RULE-43 (canonical usage tranches; derivation only when the source
establishes it; aggregation apart from scope and finality), RULE-44 (source-local identity, never a call or response
identity), RULE-45 (the reported lifecycle stage; name, family and classification apart) and RULE-46 (reasoning form;
content observations are not invocations). No rule removed; `RequirementsRosterTest` pins 46 rules.

2026-10-22, Tier 1 foundation wave. Retained identifier with changed meaning: RULE-41 now separates the approved
meaning from its tested binding (digest boundary 2): a changed meaning still needs a new revision and approval; a binding
whose relevant definitions or rules changed is refused; a pin-only binding change carries the approval; an added route
awaits binding review. RULE-43 is also retained with changed meaning: where a declared rule establishes it, each usage
component states what the source establishes about its quantity (measured, possibly default, conditional on a possibly
default operand) apart from how it was obtained, the number kept. No rule added or removed; `RequirementsRosterTest`
pins 46 rules.

2026-10-23, requirements consistency reconciliation. Retained identifiers with changed meaning: RULE-12 permits a
declared, source-grounded derivation whose arithmetic and operands the source establishes (its rule and operands
stated), keeps source-reported, derived, Journal-generated and caller-supplied values distinguishable by basis, and no
longer reads as a blanket ban on derivation; an invented fact and an assumed formula remain excluded. RULE-43 writes a
canonical tranche only when copied from a member of that meaning or derived by a rule whose arithmetic, operands and
inclusion the source establishes; adds the lower-bound status (at least the stated value) and keeps a formula that holds
only under named assumptions as the component's conditional value, never the canonical value; and no longer lets a
non-zero cumulative value establish that every contribution was reported (the rejected assumption of the 2026-10-22
candidate). RULE-45 is retained, extended by the no-shell-inference obligation: one reported invocation whose command
text runs grep, Python or a pipeline is one invocation, and no tool call, operation, argv or operating-system action is
derived from that text; separately reported operations stay their own observations; classification may use a declared
exact-name table. RULE-44 is unchanged; its Tests line names the new independent controls. RULE-30 is retained with
changed meaning: it reuses the declared vocabularies to keep what an artifact is (definition, source inspection, live
recording, sanitized real stream, synthetic, publisher-normalized corpus) apart from how a check executed it (live,
replay, synthetic replay), in place of one three-label list that could not represent research evidence. Added RULE-47
(exact field accounting of the pinned Tier 1 union with proposed exclusions kept visible). Annotations only, no
obligation changed: the Tests lines of RULE-1, RULE-5, RULE-7, RULE-9, RULE-10, RULE-23, RULE-28 and RULE-31 no longer
describe implemented and tested behaviour as missing; RULE-3, RULE-4, RULE-6, RULE-8 and RULE-33 gain Tests lines.
RULE-9's status states that pending records are interpreted provisionally today and that whether to interpret them is
an open decision. No rule removed; `RequirementsRosterTest` pins 47 rules.

2026-10-23, review closure. Retained identifiers with changed meaning: RULE-9 no longer permits interpreting records
before a compatibility entry is selected: a declared version bootstrap reads only the version and its location, the
selection is declared before the record that reported a supported version is normalized under it, and an earlier
record stays retained whole, unnormalized and uncredited (the provisional interpretation its Status line described is
replaced, not ratified). RULE-10 is extended: contradictory version assertions within one capture keep both
assertions with their origins, record a persistent conflict, prefer neither, stop vendor normalization for the rest of
the capture and never report the source supported; an identical repeated assertion changes nothing. RULE-47 is
extended: a facet whose support differs by part states each part on one of its own concepts with route states that
agree with that concept's executed state, and is not covered while a part is unexercised or retained only. RULE-43's
reason now states the lower bound in its direction (the counted quantity is at least the total; a reported 100 is
compatible with an actual 150) and that equal tranche prices never recover a missing contribution; its obligation is
unchanged. Annotations only: the Tests lines of RULE-33 and RULE-43. No rule added or removed;
`RequirementsRosterTest` pins 47 rules.

2026-10-24, final Level 1 assembly, first-class MCP and onboarding repair (Owner-authorized). Retained identifiers
with changed meaning: RULE-1 (inputs from the reviewed source profile, no universal stdout preference; was: normalize
the stream), RULE-2 (bind each source to the execution by reported, companion or acquisition identity; was: import a
native session file only when it is the only source), RULE-3 (no substitution outside the reviewed profile), RULE-4
(archive-only material unchanged; originals unchanged by assembly), RULE-5 (assemble a complete capture automatically
into one new file with consolidated canonical events; was: never parse an archived session file to enrich the stream),
RULE-6 (a fact no declared source reports is stated absent, never zero; was: a stream limitation). RULE-22's reason now
names final assembly's declared-identity consolidation. Added RULE-48 (joins on declared identities only; repeated
identity unpaired), RULE-49 (field-level merge, conflicts kept, no source-wins), RULE-50 (each quantity once; quantity
kinds never mixed), RULE-51 (import identity by origin and sha256), RULE-52 (the assembly declared in the file),
RULE-53 (MCP in typed objects from the pinned official schemas; nothing synthesized) and RULE-54 (source availability
gates). No rule removed; `RequirementsRosterTest` pins 54 rules.
