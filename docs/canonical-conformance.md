# Canonical conformance: which catalog says what

Journal's canonical model is the five OCSF event classes of the `agentic_cli` extension and the
typed objects they contain. Whether a package conforms to that model is judged from the native
input, never from the writer's own labels. Three catalogs take part, and they overlap on purpose:
each answers a different question about the same native field. This page uses one field, the
per-model reported cost of a Claude Code stream, to show which catalog is authoritative for what and
which check enforces it. Every identity below is stable: ids and revisions never change meaning in
place; a changed meaning is a new revision with its own digest and its own approval.

## The example: `modelUsage.{model}.costUSD`

A Claude Code `result` record reports, per model, a running USD cost (`costUSD`) and the pricing
basis of the latest request (`costBasis`). Journal writes it as one entry of `cost_observations[]`
on that record's `session_context` accounting event: scope 6 Model with the model's identity,
currency USD, quantity kind 1 Monetary amount, aggregation 2 Cumulative snapshot, `cost_basis` as
reported and `cost_basis_window` `most_recent_request`.

| Catalog | File | Entry | Question it answers |
|---|---|---|---|
| Claude-specific canonical conformance rules | `schema/vendor/claude-code/stream-json/obligations.json` | `claude.stream.cost.model` revision 2, `meaning_digest sha256:29d6cd8e…`, `relevant_digest sha256:63e35046…` | For this exact source: which native trigger (`result` / `modelUsage.{*}.costUSD`) must reach which destination (`session_context` activity 3, context kind `result/modelUsage`) with which observation values, and under which writer rule and basis label. **Authoritative for what the Claude stream must produce.** |
| Shared expected-fact meanings | `schema/vendor/claude-code/stream-json/expected-facts.json` | `source.cost/v2` revision 2 | Across sources: a reported model cost keeps model identity and the cumulative amount, a reported basis is required with the most-recent-request window, an absent basis stays absent. The same meaning id binds the transcript catalog, where no basis exists at source. |
| Writer mapping contract | `journal-ocsf-writer/src/main/resources/ocsf/mappings/claude-code-2.1.303-stream.json` | rule `claude.stream.result.model_cost->session_context.accounting`, basis prefix `claude.stream.model_cost.v1` | What the producer declares it does. It is an input to the checks, never evidence: a writer label is not trusted by its wording or its version suffix. |

The conformance rule is authoritative because it names everything an approval has to bind: the
source trigger, the destination, the observation, the digest of that meaning, and the digest of the
extension definitions it lands in. The shared meaning is the cross-source requirement the
independent reader derives from native bytes without the writer; it is what makes the Claude result
comparable with a Junie or Copilot cost. The contract rule is the writer's side of the same fact.

## The checks that enforce it

| Check | Where | What it establishes for the example |
|---|---|---|
| `CanonicalConformanceTest` with `CanonicalObligations#judge` | `journal-capture-tck` | Every `costUSD` occurrence in every registered recording of the bound compatibility entries reaches its destination with the stated values, counted from the native record before any candidate output is read; the report is held exactly to `ocsf/conformance/claude-stream-usage-baseline.json`, whose category 6 keeps the review state (`approval:claude.stream.cost.model@r2` is open until an `owner-decisions.json` entry names both digests). |
| `OcsfOracle` population and value checks (`population.cost`, `cost.values`, `population.contexts`, `context.values`) | `journal-capture-tck` | The oracle registers the expected cost from the native bytes through `ClaudeStreamProfile` and compares the candidate's `cost_observations` entry value by value. |
| `CrossCliAccountingTest` and `NativeAccounting` | `journal-capture-tck` | The executed comparison of `source.cost/v2` at each binding is attributed, pinned and non-duplicated in `ocsf/conformance/cross-cli-accounting-baseline.json`; a required comparison that did not execute stays non-passing. |
| `SemanticCorrectionWitnessTest` | `journal-capture-tck` | The literal witness (features recording, record 50: `0.009542775` USD, basis `list`) and the corruptions that must be rejected (basis removed, basis window applied to the whole history). |
| `RequirementsAccountingIT` | `journal-ocsf-writer` | Ties the rule and the basis label to the requirement evidence of the build. |

A passing build means these checks hold for the pinned recordings. It is not semantic approval:
`review.status` in the catalogs and the frozen category-6 findings of the baseline say which
meanings the Owner has accepted, and only an entry in
`journal-capture-tck/src/test/resources/ocsf/mapping-catalog/owner-decisions.json` changes that.

## Terminology

The Claude-stream obligation catalog predates the shared expected-fact meanings; earlier documents
called its entries "legacy Claude obligations". They are the Claude-specific canonical conformance
rules of the current model, not requirements of the retired 1.x record format, and their ids,
revisions, digests and baseline history are unchanged.
