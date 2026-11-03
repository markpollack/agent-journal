# Experimental Codex 0.166.0 acquisition

`io.github.markpollack.journal.tck.ocsf.inventory.CodexAcquisition.qualify(Path sealedManifest,
Path newDirectory)` returns `Qualified(Path manifest, Path assemblyExpectation)`. The destination
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
and never sums the two cumulative observations. Native instruction/environment context remains
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
