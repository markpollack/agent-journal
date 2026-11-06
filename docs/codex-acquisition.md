# Experimental Codex 0.166.0 acquisition

`io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition.qualify(Path sealedManifest,
Path newDirectory)` returns `AcquisitionQualifier.Qualified(Path manifest, Path assemblyExpectation)`.
`CodexAcquisition.QUALIFIER` runs the same qualification as the registered `AcquisitionQualifier` for
this profile, with terminal profile `client-terminal-codex-0.166.0/1`. The destination
must not exist. This read/copy operation launches no process. `validate(Path manifest)` performs
read-only original-byte checks. Both the qualifier and packaged reader are independent of the writer.

The immutable input is `agent-journal/acquired-execution` version0.1, profile
`codex-cli-0.166.0-exec-stdio-rollout`, profile_id `codex-cli/0.166.0/exec-stdio+rollout`.
The complete labelled synthetic example is in
`journal-capture-tck/src/test/resources/ocsf/syn-codex-acquisition-0.166.0/`.
It carries four originals: stdin/stderr archive-only, stdout and native rollout selected required
sources. Original data is never rewritten. Qualification copies those originals, saves the original
caller manifest as `client-acquisition.json`, and writes bound `acquisition.json` and the exact
independent `assembly-expectation.json`.

The supported route is fresh `--no-daemon exec --ignore-user-config --json` with explicit effective
cwd/sandbox/approval/model/effort and final stdin prompt `-` (optional `--` separator). No resume,
fork or ephemeral history. Original UTF-8 prompt bytes are written once and stdin closed. Native
N is stdout thread.started.thread_id matched to native session_meta.id/cwd/version/source exec;
T is independently read from native task_started/turn_context/task_complete. Unknown native identity,
missing originals, unclosed/interrupted capture, preexisting native path, overflow/drop, wrong extent,
wrong prompt or inconsistent terminal usage refuses complete qualification. Capture metadata is an
explicit caller observation, not proof of OS containment. Only actually observed empty stderr is zero.

Exactly one successful stdout turn.completed and native task_complete are required. All five
nonnegative integer usage components must agree with the last native cumulative total. Missing usage
is not zero. Source-local item/response IDs are distinct; the profile invents no cross-source joins
and never sums the two cumulative observations. Only the qualified single native turn permits the
completion join: native cumulative usage is selected as a whole field, keeping intermediate cumulative
and last-response observations and exactly one final prompt-turn contribution. Distinct tool calls,
including equal-valued calls, remain distinct. Public assembly without validated original acquisition
refuses this singleton profile. Native instruction/environment context remains
source data. Unsupported message/history/meaning cannot establish an accepted complete archive.
Current source descriptions are experimental synthetic inventories checked against upstream
rust-v0.166.0 commit593389380740f7b4762771573c447ccfdc59742f, not relabelled older recordings.

For immutable Client association use TerminalReceiver.CODEX_PROFILE. D has original E/D/L/G;
manifest has the same E/D/L/G and source-reported N/T. Initial P can contain an already COMPLETED A
(priorRevisionId null), or PENDING A can receive a later explicit final revision whose parent is the
initial revision. D/P and original ACK never change. Final A lists all four manifest sources plus
acquisition.json, whole extents and hashes, N and G, correct stream labels, and null nativeTurnId/
nativeConnectionId because whole files contain session context too. The manifest separately binds T.
Failed/cancelled/deadline outcomes stay honest terminal evidence and cannot qualify this success profile.

Run FinalAssembly or TerminalReceiver through the installed writer, then verify the resulting archive
in a separate JVM with only the installed TCK and its runtime dependencies, passing both acquisition
and assembly expectation. A detached event file still exposes the exact decision, but reports external
originals unavailable. Qualification alone is not semantic acceptance, live qualification or release
readiness. Existing Claude one-shot/retained profiles and prior artifacts remain independent.

The current mapping also reads source-reported command aggregate output, declared tools and namespaces,
thread settings, and empty/text/opaque reasoning items. Extension `0.16.0-prototype` records the native
combined stdout/stderr aggregate in `tool_operation.combined_output`; it does not invent separated
stdout or stderr. Source-reported identifiers remain at their native occurrence; absent IDs remain
absent. The complete structural synthetic example is
`journal-capture-tck/src/test/resources/ocsf/syn-codex-onboarding-0.166.0/`.

Whole-field authority is explicitly selected with `mode: "whole-field"` on this profile's
`/usage_observations` authority. Existing conflict-only authorities retain their behavior. Equal whole
values retain equal-value semantics; an unknown mode is invalid. The new profile explicitly rejects
repeated canonical final accounting identities. Older profiles retain their declared accounting rules.

Historical extension `0.15.0-prototype` files remain immutable and structurally readable. A historical
Codex tool-bearing package that labelled combined output as `stdout` does not pass the new reader's
current output semantics: it is reported defective, not silently corrected. Prior text-only Codex and
supported Claude one-shot/retained archives remain separate regression obligations.

The original small source-description fixture remains a frozen baseline. Current exact-version
capabilities and unexercised families are recorded separately under
`schema/vendor/codex-cli/{exec-json-stream,rollout}/0.166.0-vendor-inventory.json` and their disposition
catalogs. `CliOnboardingTest` executes registered inventory/route/roster checks even for experimental
routes, computes raw field-bearing occurrences before candidate verification, and requires attributed
checks for every observed obligation. A new route without a mapper may be explicitly partial; missing
registration, a missing known obligation, zero selected occurrences or an unrelated passing check
cannot count as support. `target/mapping-catalog/cli-onboarding.json` in the TCK reports the measured
partial slices and remaining gaps. No live run or new source capability is implied by this offline gate.

The reader selects an exact verification expectation by the saved profile/schema pair: historical
profile `0.1.0-prototype` with extension `0.15.0-prototype`, or current profile `0.2.0-prototype` with
extension `0.16.0-prototype`. Cross-pairings, arbitrary expectations and substituting the historical
expectation for a current archive are refused. Historical text archives retain their original rules;
original-source qualification still applies. This does not waive the old combined-output/stdout defect.
