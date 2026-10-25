# Agent Journal architecture artifacts

This directory contains generated structural views and a reproducible mapping example for the OCSF writer and its
independent Java checker. These artifacts describe specific classes, fixtures and recorded scenarios; they are not
a claim of exhaustive canonical coverage.

## Technical references

- [Component responsibilities and dependencies](generated/component-view.md), with [machine-readable membership](generated/component-view.json) and [diagram source](generated/component-view.puml).
- [One Codex MCP call from native input to checked output](generated/tool-call-story.md).
- [Recorded launch overview](generated/observed-launch-overview.svg), [full trace](generated/observed-launch-sequence.svg) and [recording manifest](generated/observed-launch-recording.json). This is one labelled stand-in replay, not a live CLI run or an exercise of every assembly path.
- [Boundary and independence tests](../../journal-ocsf-writer/src/test/java/io/github/markpollack/journal/architecture/ArchitectureBoundaryTest.java).
- [Normative rules](../../journal-capture-tck/src/test/resources/requirements/agent-journal/rules.md) and [acceptance criteria](../../journal-capture-tck/src/test/resources/requirements/agent-journal/criteria.md).
- [Canonical occurrence checks](../../journal-capture-tck/src/test/java/io/github/markpollack/journal/tck/ocsf/CanonicalObligations.java) and [conformance tests](../../journal-capture-tck/src/test/java/io/github/markpollack/journal/tck/ocsf/CanonicalConformanceTest.java).

The writer and TCK have independent production code paths. Their tests and source-grounded expectations must establish
values, ownership, measurement scope and source completeness separately from schema validity.

## Regeneration

```bash
scripts/architecture-docs.sh
scripts/architecture-docs.sh --appmap
scripts/architecture-docs.sh --docx
```

The default command regenerates the component view and tool-call story and applies the existing presentation checks.
The optional AppMap flag records the selected scenario. The Word flag exports these technical pages and the tool-call
story. Treat an old SVG or observed run as evidence for its recorded scope; the normal build checks freshness of the
declared generated tables and story.

Keep generated files reproducible from their code and fixture inputs. Full build validation uses `./mvnw clean verify`.
