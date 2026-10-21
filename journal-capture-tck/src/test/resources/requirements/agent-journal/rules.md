# Agent Journal rules: stream-first capture, preservation and the Java writer

Normative constraints on how Agent Journal records an agentic CLI execution into its OCSF-based run
package. Each rule states one obligation with an RFC 2119 keyword and a reason. Observable behavior
is specified separately in `criteria.md`; the two documents link by identifier and are not copies of
each other.

These rules are inert test resources. `RequirementsRosterTest` checks that every rule heading below
is parsed exactly once by the Agent Judge reader; a green parse establishes that the roster loaded,
not that the implementation meets any rule. A `Tests:` line names executable checks that exist in
this repository; it is a link, not proof that the check ran or passed.

Terminology: **native session file** is the file a CLI writes on its own account (Claude Code's
session transcript, Codex's rollout file). **Stream** is the structured output a CLI offers while it
runs (`stream-json`, `exec --json`). **Selected input** is the source normalization reads.

## Source selection and the archive boundary

### RULE-1
**MUST** normalize a CLI's stream output as the selected input whenever that CLI offers a stream.
**Reason:** The stream is the public, incrementally available contract; it is what a live recorder can persist before the CLI exits.
Tests: no production recorder exercises this selection yet; `PrefixReplayTest`, `WriterLifecycleTest` replay recorded streams through the same writer entry point.

### RULE-2
**MUST** import a native session file as the selected input only when it is the only data source the CLI offers.
**Reason:** Session-only CLIs have no other evidence; a streaming CLI does, and two inputs for one run create a reconciliation problem the recorder should not own.
Tests: the writer accepts a native session file through the same entry point (`WriterLifecycleTest`); no test yet checks the selection rule itself.

### RULE-3
**MUST NOT** treat a missing stream adapter, an unknown CLI version, or a richer native session file as grounds to select the native session file for a streaming CLI.
**Reason:** Those are implementation gaps or research observations; letting them change the selected input makes the capture boundary depend on adapter maturity instead of on the CLI's offered sources.

### RULE-4
**MUST** archive a streaming CLI's native session file unchanged, with its checksum, as redundancy outside the selected input.
**Reason:** The archive is the emergency fallback; keeping it byte-identical keeps it trustworthy, and keeping it outside the selected input keeps the preservation denominator honest.

### RULE-5
**MUST NOT** parse an archived native session file to supplement, reconcile, deduplicate or enrich a streaming CLI's normalized output, including for final response usage.
**Reason:** Enrichment reintroduces a second input and silently changes which facts the stream is credited with; the archived file stays available for research without entering `events.jsonl`.
Tests: `FullOneReviewRegressionTest#omittingAnEntireSourceIsADefectOnEveryObligationOfThatSource` shows the frozen denominator is the selected input; no test yet rejects enrichment from an archive-only file.

### RULE-6
**MUST** record a fact the selected stream does not report as a stated limitation of that source rather than fill it from another artifact.
**Reason:** A consumer can act on a declared gap; a filled gap hides which source actually reported the value.

## CLI version and mapping applicability

### RULE-7
**MUST** retain the CLI version the stream reports and MUST NOT launch a separate version command when the stream reports one.
**Reason:** A redundant subprocess costs time, can disagree with the running process, and adds nothing the stream did not already say.
Tests: the Claude `system/init` record carries the version into session settings (`OracleFullSourceTest#alteringAReportedSettingOrAddingAnUnreportedOneIsAContextValueDefect`); no test yet observes that no version command is spawned.

### RULE-8
**MUST** use one documented, adapter-specific discovery path when the selected source reports no version, without repeated probes.
**Reason:** Codex's tested exec stream carries no version; discovery must be bounded and explicit so it cannot become a hidden dependency on the native session file.

### RULE-9
**MUST** retain input that arrives before the version is known, without blocking persistence on mapping selection.
**Reason:** The first records of a stream are evidence; waiting for a version to choose a mapper would lose them or force a buffer the recorder cannot bound.
Tests: `WriterLifecycleTest#persistedPrefixIsReadableAndQueryableBeforeEof` persists prefixes; no test yet varies when the version becomes known.

### RULE-10
**MUST** record, with the version, which mapping contract was applied and whether that version is within the contract's tested applicability.
**Reason:** Recording a version does not implement version-based mapping selection; the package has to say which mapping judged it and whether that pairing was ever tested.
Tests: `PinsTest#mappingContractsDeclareEveryRuleTheMappersExecute`, `VendorSpecificationsTest` (declared applicability); runtime applicability status is not yet written.

## Common model and preservation

### RULE-11
**MUST** define portable fields as OCSF extension classes and objects using ACP's interaction vocabulary, informed by Spring AI, OTel/OpenInference, Weave and Arize concepts.
**Reason:** OCSF supplies schema authoring and validation; ACP supplies familiar names; the observability models supply tested field meanings, so none of them needs to be reinvented.
Tests: `SchemaCatalogTest#savedValuesAreCheckedAgainstDeclaredTypesEnumsAndRequirements`, `PinsTest#bundledExtensionSourceListsEveryFileOfTheExtensionDirectory`.

### RULE-12
**MUST NOT** populate a portable field with a value the selected source did not report, even when a framework or SDK defines a field for it.
**Reason:** A field's existence in a model is not evidence that this CLI reported the fact; absence, zero and derived totals must stay distinguishable.
Tests: `OcsfOracleMutationTest#aComponentPresentWhereTheSourceReportsNoneIsADefectAbsenceIsNotZero`, `OcsfOracleValueCheckTest#responseContentPresentWhereTheSourceHasNoneIsADefect`.

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
**Reason:** Those are consumer analyses with their own provenance; the writer records identifiers, order, reported scope and finality and leaves counting to tests and consumers.
Tests: `CodexExecStreamTest#viewsMatchTheIndependentlyAuthoredAnswersAndStateTheirCountUnits` (views are test utilities, not writer output).

### RULE-23
**MUST NOT** depend on Agent Judge, a model runtime or any network service in the writer or the capture path.
**Reason:** Requirements are inert data evaluated by tests; capture must work offline and remain free of evaluation tooling.
Tests: the Agent Judge dependency is test-scoped in `journal-capture-tck/pom.xml`; `journal-ocsf-writer/pom.xml` declares no such dependency.

## Portable output

### RULE-24
**MUST** write `run.json`, `events.jsonl` and linked child cores as the portable core, carrying the selected input's execution facts and the schema and contract definitions a reader needs, once per scope.
**Reason:** A run has to be interpretable after it is copied somewhere else; per-event schema repetition and remote registries both fail that test.
Tests: `IsolatedBootstrapTest#aCopiedCoreWithItsChildBootstrapsWithoutAnythingElse`, `PackageReaderTest#selfContainedExportJoinsTheSharedFactsBackOntoAnEvent`.

### RULE-25
**MUST** be readable by a compatible Java reader from the core alone, without the CLI, a hosted service, optional analysis files or generated accessor code.
**Reason:** Reader software is a versioned dependency; everything else the reader needs must travel with the run.
Tests: `IsolatedBootstrapTest`, `PackageReaderTest#discoversTheManifestFromEventsJsonlAndFromTheDirectory`.

### RULE-26
**MUST NOT** make an optional analysis file, a feedback file or the native archive the only location of a selected-input execution fact.
**Reason:** Core acceptance omits those files; a fact that lives only there is a fact the core lost.
Tests: `OcsfOracleMutationTest#removedWholeRecordIsAGapInCoverageAndReconstruction` judges the core without optional files.

### RULE-27
**MUST** keep externally produced feedback durable with producer, time and target provenance when it is produced, without making it a prerequisite for capture.
**Reason:** Judgments attached to a run are valuable and must survive, but their absence says nothing about whether execution facts were preserved.

## Coverage, provenance and testing

### RULE-28
**MUST** demonstrate each claimed feature with a committed fixture in which the feature actually occurred and an independent assertion that checks it, and MUST report scenarios without such a fixture as gaps.
**Reason:** Asking a model to use a feature does not establish that it did; a finite corpus is scoped support, not universal vendor coverage.
Tests: `CodexExecStreamTest`, `OcsfOracleFixtureTest#sanitizedClaudeStreamWithChildCoreIsJudgedPerCoreWithoutDefect`; interruption and media remain labelled-synthetic or unexercised.

### RULE-29
**MUST** establish acceptance with the independent Java TCK, which MUST NOT call production capture parsers, SDK deserializers or mappers to compute expected values.
**Reason:** A checker built from the code it checks cannot disagree with it.
Tests: `OcsfOracleMutationTest`, `OcsfOracleValueCheckTest`, `FullOneReviewRegressionTest` (independent reader and hand-authored expectations).

### RULE-30
**MUST** label every fixture, report and claim as live capture, real recorded replay or labelled synthetic, and MUST NOT present one class as another.
**Reason:** Replay through a recorded seam is regression evidence; only a live run establishes live lifecycle integration.
Tests: fixture `spec.json` files carry the synthetic label; `PublicFixtureRegenerationTest` regenerates labelled fixtures.

### RULE-31
**MUST** publish only deliberately reviewed and sanitized material as public test data, with a manifest of capture command, CLI version, mode, source, hashes, sanitization and origin.
**Reason:** Even synthetic prompts produce local paths, account metadata and injected instructions that must not leak.
Tests: public fixtures under `src/test/resources/ocsf/fixtures/` carry `spec.json` provenance; no automated privacy check exists.

### RULE-32
**MUST** compare the requirement roster parsed by Agent Judge with every intended identifier in these documents, and MUST NOT infer compliance from a successful parse.
**Reason:** The readers accept specific heading shapes and skip malformed entries; a roster that silently shrank would evaluate a specification nobody wrote.
Tests: `RequirementsRosterTest`, `NativeRequirementTest`, `RequirementsEvidenceTest`.
