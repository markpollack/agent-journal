# Historical packages, layout 0.2.0-prototype

The packages the incremental writer produced at `a6dce21` (FULL-1R) for the four public fixtures,
kept unchanged as evidence that the reader keeps reading the two-file layout (`run.json` carries the
definition closure and the embedded contracts; a detached `events.jsonl` of this layout is refused).
They are never regenerated. The `spec.json` beside each is a copy of the fixture's spec at that
revision. The native artifacts and frozen expected-sources manifests remain under `../fixtures/<name>/native/`.
