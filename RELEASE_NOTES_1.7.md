# Agent Journal 1.7.0

A maintenance and correctness release. No API, event-schema or trace-format change: a 1.6.0
consumer upgrades by changing the version. Two of the fixes below affect what you resolve and
what you receive, so read the first two sections before upgrading.

## Security — Jackson moved to CVE-clear floors

Through 1.6.0, standalone consumers could resolve Jackson **2.21.2** from either capture SDK.
That version is inside the affected range of `CVE-2026-54512` and `CVE-2026-54513` (both CVSS
8.1, published 2026-06-23) — two `PolymorphicTypeValidator` allowlist bypasses — along with
seven further advisories in the same cluster.

1.7.0 manages Jackson 2 at **2.21.6** and publishes `jackson-core` as a direct dependency of
`journal-core`, which is also a direct dependency of both capture modules. The minimum fix for
the headline pair is 2.21.4, but `CVE-2026-59889` and `GHSA-mhm7-754m-9p8w` are only fixed at
2.21.5, so the release takes the head of the patch line.

`claude-code-capture` also acquired Jackson 3 transitively (`claude-code-sdk` → `mcp` →
`mcp-json-jackson3`) at **3.0.3** on the compile path, affected by the same cluster and by
`CVE-2026-29062`. 1.7.0 publishes direct dependencies on Jackson 3 core, databind and YAML at
the managed **3.1.6** line.

The two Jackson BOM imports remain in the parent for this reactor's alignment. The direct
declarations are what make Maven's nearest-wins resolution safe for an ordinary consumer of any
one module without `agentworks-bom`; a repository gate verifies all three consumer shapes.
This is a dependency-resolution correction, not a Jackson 3 migration: Agent Journal's source,
APIs, schemas and stored-journal format continue to use Jackson 2 unchanged.

If you pin Jackson yourself, keep the same accepted floors: 2.21.6 for Jackson 2 and 3.1.6 for
Jackson 3. This release cannot raise a version you manage.

## Licensing — the LICENSE now ships inside the artifacts

Through 1.6.0 no published artifact contained the license text. The binary, source and Javadoc
JARs on Maven Central carried only `META-INF/maven` metadata, while BSL 1.1 requires the license
to be displayed conspicuously on each copy of the Licensed Work.

From 1.7.0 every binary and source JAR carries `META-INF/LICENSE`, and the Javadoc JAR carries
`resources/LICENSE`. The repository root `LICENSE` remains the authoritative copy. Nothing about
the terms changed — this is a packaging correction.

### There is no Apache-to-BSL boundary in this project

For the avoidance of doubt, since several sibling projects do have one: **Agent Journal has been
distributed under the Business Source License 1.1 for its entire published history.** The first
release, 0.9.0 (2026-03-29), and every release since carry BSL 1.1 in both the `LICENSE` file
and the POM metadata. No version was ever published under the Apache License 2.0, so there is no
earlier Apache grant, no last-Apache/first-BSL boundary, and no historical license file to
retain. The project's Maven coordinates and Java packages have been `io.github.markpollack` from
its first commit; it was never published under `org.springaicommunity`. The Maven Wrapper files
shipped in the repository are third-party Apache 2.0 material and keep their own notices.

## Supply chain — a CycloneDX SBOM is now published

Releases now publish one aggregate CycloneDX 1.6 JSON SBOM for the whole reactor, attached to
the parent artifact under the `cyclonedx` classifier:

```
io.github.markpollack:agent-journal-parent:1.7.0:json:cyclonedx
```

It covers all three published modules and the shipped closure at compile, provided, runtime and
system scope; test-scope components are excluded. Child modules do not publish their own SBOMs.
No SBOM accompanied any earlier release.

## Java requirements are now declared correctly

The POMs previously advertised a Java 17 baseline for every module, which the capture modules
could not honour — both bind vendor SDKs published as Java 21 bytecode, so a Java 17 runtime
resolved them and then failed on the first SDK class.

| Module | Requires |
|---|---|
| `journal-core` | **Java 17** (unchanged) |
| `claude-code-capture` | **Java 21** (now declared) |
| `gemini-cli-capture` | **Java 21** (now declared) |

`journal-core` is unaffected and still compiles to a Java 17 target. If you consume only
`journal-core`, nothing changes. If you consume either capture module, you were already
required to run Java 21; the POM now says so. Building the repository from source likewise
requires a Java 21 JDK, and `.sdkmanrc` now pins one.

## Documentation and samples corrected

Every code sample in the repository was checked against the API it documents, and the broken
ones were fixed and then verified by execution:

- the README's example did not compile — it called an `LLMCallEvent.builder().tokens(…)` method,
  a three-argument `CostBreakdown.of`, and a `ToolCallEvent.of` factory, none of which exist,
  and treated the immutable `Summary` record as mutable. The README has been rewritten as a
  proper landing page and its example now compiles;
- documentation samples calling a three-argument `ToolCallEvent.success`, a five-argument
  `TokenUsage.of`, or a single-argument `Summary.get` were corrected to real signatures;
- every documented DuckDB query failed with a binder error: they selected a `line` column that
  `read_ndjson_auto` does not produce and filtered on `$.type` where the serialized
  discriminator is `@type`. All of them were rewritten and now run against a real journal;
- `gemini-cli-capture`, which shipped in 1.5.0, finally has its own guide, including an explicit
  table of where Gemini's coarser typed SDK provides less detail than the Claude path;
- `TokenUsage.total()` is now documented as what it actually sums — input, output and thinking
  tokens, excluding cache and tool-use tokens. Its previous javadoc claimed "all categories",
  which matters because that value is published as `total_tokens`.

## Build and CI

- Snapshot publishing now runs the full test suite. It previously skipped tests, so snapshot
  consumers could pull artifacts no test had exercised on the publishing commit.
- The nightly OWASP/NVD GitHub Actions scan has been removed. It had failed on every run for
  over a month and aborted on the first module, never inventorying the rest. Vulnerability
  analysis runs locally against a prepared database instead; `./mvnw -Powasp verify` remains,
  now with a configurable threshold so a complete non-failing inventory and the enforcing gate
  are the same profile with different flags. `scripts/security-scan.sh` provides a Trivy
  cross-check.
- Reusable build and release workflows are pinned to an immutable commit rather than a moving
  branch ref.

## Compatibility

Additive on the frozen capture contract. Both stream schema versions are unchanged —
`events.jsonl` and `analysis.jsonl` remain schema version 1, the portable trace remains schema
version 2. Journals written by 1.5.0 or 1.6.0 read unchanged, and a 1.5.0 consumer keeps
working. 482 tests pass at the release commit.
