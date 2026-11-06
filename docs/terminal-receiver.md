# Terminal evidence receiver

`TerminalReceiver` receives an already accepted Client decision on a separate recording lane. It
never runs a provider or changes that decision. Its first supported contract is
`journal-terminal-receiver/1`, route `client-terminal-evidence/1`, profile
`client-terminal-evidence-only/1`. These are exact pins, not native CLI qualification.

The profile accepts strict canonical `client-decision/1`, `journal-association/1` and
`journal-terminal-envelope/1` bytes. Objects have fixed lexically sorted fields, explicit nulls,
UTF-8 scalar strings, lowercase four-digit control escapes and signed 64-bit decimal integers.
There is no BOM, whitespace, floating point, duplicate or unknown member. The envelope embeds the
exact decision and association in padded standard base64. A payload is at most 1 MiB; opaque IDs
are nonempty and at most 4096 UTF-16 code units. Unknown versions and failure codes reject.

The accepted decision has separate execution, decision, session and nullable connection IDs,
its original acceptedAt instant, outcome, provider observation and six nullable application
correlations. Configured execution timeout is `FAILED` with failure code `TIMEOUT`; setup timeout
is `SETUP_TIMEOUT`. Explicit cancellation is `CANCELLED` with its reason. Caller wait timeout or
interruption does not create a decision. Current failure spellings are TIMEOUT, SETUP_TIMEOUT,
SCHEDULING, SETUP_FAILED, PROVIDER_TRANSPORT, PROVIDER_PROTOCOL, ADVISOR, RESULT_COPY,
CLIENT_PROCESSING, PERSISTENCE_FAILED, RECORDING_FAILED, RECORDING_TIMEOUT and RECORDING_CONFLICT.

Association declares exact byte extents, whole-file hashes, connection ownership, native identities
when known, missing sources and the acquisition status. Original immutable sources are copied and
checked; paths must stay inside the protected source directory without symlinks. This profile checks
those declarations and bytes, but does not independently infer native turn membership or declare a
complete archive. Native mapping and complete analysis require the corresponding supported source
profile and independent oracle. Pending evidence remains pending even if receipt storage completes.

The receiver creates a closed evidence package through `OcsfRunWriter`, with a versioned0.4 run
binding containing the exact original envelope and association. The ordinary writer still uses0.3;
existing files remain readable. `PackageReader` checks the new declaration and
`TerminalBindingReader.read(package.declarations)` returns independent immutable text snapshots.
A detached events.jsonl interprets this decision without a sibling marker; it cannot prove publication
or verify missing external source files. A valid marker and its checked referenced files separately
prove receiver publication within the qualified local-store contract.

The configured producer namespace plus execution ID chooses one marker by length-delimited UTF-8
SHA-256. The receiver seals and forces candidate files, then uses atomic no-replace hard-link creation.
The candidate ACK is stored before linking. Identical retries validate the original files and return
that exact ACK, including its binding ID, preparedAt and artifacts. Changed bytes for the same key
conflict. A corrupt marker or artifact never permits overwrite. An exception after linking can mean
acceptance is unknown; retry the identical frozen payload to recover the original ACK. Do not replay
the provider. Prepared candidates remain unaccepted evidence until their marker is linked.

`receiveRevision(originalBindingId, associationBytes, sourceRoot)` accepts an immutable evidence revision
whose priorRevisionId is the initial revision. It keeps the original D, publishes under its own
execution/decision/revision key, and cannot replace the initial marker. Its payload retains the
original envelope and ACK identity. The initial ACK payload hash is the exact original P hash; a
revision ACK hashes the exact canonical object `{association,originalBindingId,terminalEnvelope}`.
Every duplicate revision returns its own original ACK. The receiver can accept distinct sibling revision
keys; Client policy schedules at most one final revision. Parent ACK resolution and retry bounds belong
to the caller's bounded delivery lane; the receiver starts no scheduler.

The local filesystem must support same-filesystem no-replace hard links, and store/source directories
must be protected from external mutation while used. Content force and atomic publication do not
establish directory persistence after power loss, a durable Client outcome ledger or eventual success
after process death. There is no fallback to replacement rename, database, broker or repair operation.

## Claude 2.1.316 native archive profile

`TerminalReceiver.CLAUDE_PROFILE` selects `client-terminal-claude-2.1.316/1` under the same
receiver contract and route. The existing evidence-only profile is unchanged. Pending or incomplete
associations remain evidence; they make no complete-native claim. A later `receiveRevision` may
supply the completed archive while retaining the initial envelope and accepted decision.

For a COMPLETED association, sourceRoot contains `acquisition.json`, the immutable output of the
exact Claude source qualifier, and every source it names. Include the manifest and every acquired
source in A.sources with original origin, full `[0,size)` span and exact whole-file hash. The manifest
has profile `claude-code-2.1.316-sdk-stdio-session-file`, profile_id
`claude-code/2.1.316/sdk-stdio+session-file`, and one closed SDK stdio turn. Its acquisition_uid and
run.run_uid equal D.executionId; process.connection_uid equals the single Client connection ID in
D/A. Native session identity comes from the native sources, separately from Client session identity.
All A sources carry that nativeSessionId and clientConnectionId; nativeTurnId/nativeConnectionId are
null because this profile establishes no corresponding native identifiers. Map stdin/stream/stderr
roles to STDIN/STDOUT/STDERR; the manifest and other native files have stream=null.

The complete profile requires original stdin/stdout/stderr and the native transcript, no missing,
interrupted or partial source, matching turn byte extents, exactly one submitted prompt, one native
prompt with the same message and one terminal result. Exact native session/version witnesses must
agree. This first terminal binding supports a string-valued native user message; multimodal/structured
user-message membership, retained multi-turn and reconnect acquisitions are outside its scope. Source completeness
does not establish physical process cessation, permission behavior or Client live qualification.

A retained Claude control record can have no `session_uid` even though its source belongs to an identified
session. The assembly reader checks the record's own reported identity, including its absence, against the
independent native locators. Source membership does not fill a missing record-local identity.

The receiver feeds those sources through the existing final-assembly writer and places the frozen
terminal binding in the same portable events.jsonl. `PackageReader` independently checks terminal
and native association; `TerminalBindingReader` exposes the original decision. Complete semantic
archive acceptance additionally requires the packaged reader's existing `verify` command with
`--acquisition <acquisition.json>` and `--assembly <assembly-expectation.json>` against the protected
original source directory. A valid ACK or detached readable file alone is insufficient. A detached
file retains its declarations and reports unavailable external sources honestly.

`TerminalIntegrityException` is an IOException subtype for positively detected source, artifact,
inventory or closed-canonical integrity defects. It is not retryable repair and does not assert that
publication never happened. Other IOException remains ambiguous. Invalid payload/profile/association
and immutable-key conflicts remain IllegalArgumentException. Existing IOException handlers stay valid.

The independent reader checks each Claude result's permission denial at its exact array occurrence,
including its reported tool identifier, source reference, decision values and retained native object.
Distinct, repeated or absent tool identifiers do not collapse separate array entries. Array position
only identifies the entry within the native record; tool attribution still requires the identifier
that entry reports. Dropped, duplicated or misattributed denials fail validation.

**Heartbeats and sub-agents.** A tool call that runs past 30 s makes Claude Code write a `tool_progress`
record every 30 s. Its `parent_tool_use_id` names the running call, not a sub-agent, so it stays in the root
core and creates no child run. A sub-agent's records carry the spawning `Agent` call in
`parent_tool_use_id` and form a child core under `children/<id>/`. The reader works out the expected split
cores from the acquired stream itself and checks a child's binding by its link to the bound execution
(`run.parent.run_uid` is the `executionId`, and `run.run_uid` is `<executionId>/child/<spawning call>`); the
root-only acquisition checks apply to the root.

Archives with sub-agents are not yet accepted. A child core reads without a defect, but its
final-assembly assertions are not checked yet, so such an archive is INCOMPLETE.

## Qualifier lookup

`AcquisitionQualifier`, in the TCK's `io.github.markpollack.journal.tck.ocsf.inventory` package, lets
a producer qualify a sealed one-shot acquisition without naming its CLI:

```java
AcquisitionQualifier qualifier = AcquisitionQualifier.forProfile(manifestProfile).orElseThrow();
AcquisitionQualifier.Qualified qualified = qualifier.qualify(sealedManifest, newDirectory);
// bind qualified.manifest()'s sources and deliver one initial binding under qualifier.terminalProfile()
```

`manifestProfile` is the sealed manifest's own `profile` member. The registry is fixed, not discovered:

| Acquisition profile | Qualifier | Terminal profile |
|---|---|---|
| `claude-code-2.1.316-sdk-stdio-session-file` | `ClaudeAcquisition.QUALIFIER` | `client-terminal-claude-2.1.316/1` |
| `codex-cli-0.166.0-exec-stdio-rollout` | `CodexAcquisition.QUALIFIER` | `client-terminal-codex-0.166.0/1` |

Any other profile returns empty. A qualifier refuses an acquisition with `IllegalArgumentException`,
whose message is its verdict, before creating the new directory. `IOException` means an input could
not be read or the directory exists or could not be written. Each qualifier runs exactly the checks of
its static `qualify(Path, Path)` and writes the same bytes. Selection uses the acquisition profile
alone: a Codex turn manifest from a retained thread may declare the same profile as a fresh turn, and
the one-shot qualifier refuses its resumed history ("existing history forbidden for a fresh turn"). Both implementations stay producer-independent, so the
terminal profile is a wire identifier and not a writer constant.

## Retained two-text-turn profile

`TerminalReceiver.RETAINED_PROFILE` selects `client-terminal-claude-retained-2.1.316/1` in the
initial envelope. Its initial association is PENDING with no sources and its decision is SUCCESS.
Two distinct successful text turns may share one Client logical session and one connection generation.
Each decision retains those Client identities; native session identity belongs to the evidence.
Results and the next sequential admission do not await secondary Journal delivery. This states no
latency or delivery-order guarantee.

After the generation is sealed with observed resource cessation and natural stdout/stderr EOF, call
`RetainedClaudeAcquisition.qualify(generationManifest, executionId, newDirectory)` in the TCK's
`io.github.markpollack.journal.tck.ocsf.inventory` package for each execution. It returns
`AcquisitionQualifier.Qualified`, whose `manifest()` and `assemblyExpectation()` paths supply the
existing writer and packaged independent reader. The destination must not exist. It preserves the
whole originals under `generation/` and writes explicit per-turn selected-record projections under
`turn/`. No provider or transport is started, retried or awaited.

The exact input format is `journal-retained-generation/1`. The labelled synthetic fixture in
`journal-capture-tck/src/test/resources/retained-text-generation/` gives complete field spellings and
real locator/prefix hashes. Four original sources (STDIN, STDOUT, STDERR, TRANSCRIPT) have whole-file
hashes and captured/observed/dropped counts. Each turn declares prompt/result and native/stdout record
locators, observed stream windows and actual before/after native prefix lengths and hashes. Locators
address zero-based half-open original bytes including newline. Prefixes must have actually been observed;
never manufacture an empty starting prefix or derive a historical observation from the final snapshot.
The qualifier checks consistency of supplied observations; it cannot prove how a caller obtained them.

Every original record is explicitly owned, shared or unassigned. Controls and init are generation shared;
stderr is unassigned. A selected prior shared init travels with each projection under its generation
lineage. A native user/assistant UUID-parent chain, exact unique prompt message, success result text and
native session/version witnesses establish this finite membership. Unknown or ambiguous execution records,
extra/missing results, duplicate prompts, changed prefixes, overlap, gaps, overflow or premature EOF refuse
complete qualification. Tools, children, compaction, reconnect, multimodal turns and general native path
discovery are outside this profile. No native/provider qualification is implied by the synthetic fixture.

For each final revision, include `acquisition.json` and every source it names in A.sources, with exact
origin/hash/full extent; all occurrences carry the native session and Client connection. Only the
`stdin`, `stream`, `stderr` projection roles get STDIN, STDOUT, STDERR stream labels; all other roles
have stream=null. nativeTurnId/nativeConnectionId remain null. A is COMPLETED with empty missing and
names the original initial revision. Call `receiveRevision` with that execution's original binding ID.
Initial D/P/ACK stay unchanged; an old evidence-only profile does not become this profile.

Each final portable file contains the shared generation declaration, explicit selected execution and
immutable terminal binding, then the current canonical Claude assembly for its selected records.
Projection EOF means the end of finite selected bytes, never per-turn transport EOF. Process metadata
copies generation observations without inventing a process exit code or argv. Complete acceptance uses
`OcsfReaderTool verify <events.jsonl> <source-root> --acquisition <acquisition.json> --assembly
<assembly-expectation.json>` with the independent TCK-only classpath. The reader rechecks original
membership/prefixes and every projection before judging canonical values. A detached file still reads
its exact decision and declarations, with unavailable external bytes reported separately.

## Qualification status of the Claude fresh-start retained profiles (2026-11-05 development snapshot)

`client-terminal-claude-retained-fresh-2.1.316/1` (frozen, exactly two turns) and `/2` (any sealed turn count) keep the two-record
text-turn model and were qualified on synthetic generations only. The native Claude Code 2.1.316 transcript of a plain text turn carries
attachment chains and request records around each prompt and response, so both profiles REFUSE a real fresh-start capture.
`client-terminal-claude-retained-fresh-2.1.316/3` admits the native record shape (turn membership by prefix-window tiling, parent
ancestry through admitted intermediate records, in-flight stream messages, a plain transcript tail) and was derived from the one frozen
native two-turn probe specimen. Known qualification gaps of this snapshot: on that specimen the `/3` qualifier's sole finding is
INCOMPLETE at exhaustive identity (the caller's depth-limited candidate scan reported skipped subtrees), so no archive of record exists
from a native Claude fresh-start capture yet; the launch argv of that specimen was observed truncated and `/3` records argv completeness
(`retained_launch_argv`) and falls back to native evidence for the session request. A real two-turn test with an exhaustive scan and a
complete argv is the next-snapshot item.
