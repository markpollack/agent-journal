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
