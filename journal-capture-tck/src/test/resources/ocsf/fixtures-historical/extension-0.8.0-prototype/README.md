# Packages written with agentic_cli extension 0.8.0-prototype

`syn-claude-untested-version/package` is the committed package of the labelled synthetic no-match stream as the writer
wrote it with extension 0.8.0-prototype (requirements reconciliation, 2026-10-23), before compatibility selection
preceded normalization. Its native artifact is the current fixture's (unchanged). It stays readable from its own
embedded definitions and is never rewritten.

What that writer did, labelled here and replaced in 0.9.0-prototype:

- Records that arrived while the CLI version was unknown were interpreted under the profile's mapper. Its first
  compatibility declaration says so (`version_unknown`, normalizes "provisionally, under the profile's mapper, until the
  version is known"), and record 1, the init record that reported 2.1.999, is a normalized `session_context` although
  the version then matched no entry.
- `records_before_selection` of the later selection counted the record that carried the version (1 here).

Since 0.9.0-prototype a version bootstrap reads only the version and its location, the selection is declared before
that record is interpreted, a record that arrives before any selection is retained whole and unnormalized, and a
no-match leaves every record retained. `PackageReaderTest#aPackageWrittenWithProvisionalPendingInterpretationStillReadsAndIsJudgedByTheCurrentPolicy`
proves that this package still reads, with its own declarations, and that the independent oracle reports the
provisional interpretation of record 1 as a defect under the current policy rather than accepting it.
